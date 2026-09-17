package ai.closepaw.tool

import android.util.Log
import ai.closepaw.protocol.AppTier
import ai.closepaw.protocol.ApprovalMode
import ai.ruach.action.ActionRisk
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * PolicyEngine — Decides whether tool calls should be allowed, denied, or require approval.
 *
 * Persistent per-package policy lives on [AppClassifier] as user overrides; the engine reads
 * the *effective* tier and applies the canonical check order below. Only the session-scoped
 * allow-list (transient, never persisted) stays here.
 *
 * Canonical ordering (see design doc):
 *  1. Non-screen-changing tool                     → Allow
 *  2. Escape (back/home)                            → Allow
 *  3. effective tier == BLOCKED                     → Deny       (preserves "stricter wins" for open_app)
 *  4. action-risk layer (M1 Step 6)                 → CRITICAL = Deny; requiresConfirmation / HIGH = AskUser
 *  5. tool == browser_script                        → browser script matrix (NORMAL override does NOT bypass)
 *  6. mode != ALWAYS_ASK && session-allowed         → Allow      (session allow-list, gated by ALWAYS_ASK)
 *  7. ApprovalMode dispatch on the effective tier
 *
 * Action-risk comes from the registered tool definition only (see [ai.closepaw.tool.ToolSpec.riskLevel]),
 * passed in by [ToolRouter] — never from LLM-supplied arguments. The risk layer is an absolute floor:
 * CRITICAL is blocked for MVP (no confirmation path may make it executable) and HIGH always requires
 * explicit user confirmation, regardless of approval mode or session allow-list.
 */
class PolicyEngine(
    initialApprovalMode: ApprovalMode = ApprovalMode.SMART,
    val appClassifier: AppClassifier
) {
    private val approvalMode = AtomicReference(initialApprovalMode)

    /** Session-scoped allow-list — cleared on reset(). */
    private val sessionAllowedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    companion object {
        private const val TAG = "PolicyEngine"
    }

    /**
     * Check if a tool call should be allowed, denied, or requires approval.
     *
     * @param toolName The name of the tool
     * @param params The parameters for the tool call
     * @param packageName Current foreground app package name
     * @param destinationPackage Target package for navigation tools (e.g. open_app).
     *        When non-null, the effective tier is the stricter of current and destination.
     * @param riskLevel Declared action risk of the registered tool (M1 Step 6). MUST come from
     *        the registered [ToolSpec] definition — callers must never derive it from LLM
     *        arguments, and concrete callers should pass the resolved tool's declaration
     *        explicitly rather than relying on the LOW default.
     * @param requiresConfirmation Declared confirmation requirement of the registered tool.
     * @return PolicyDecision indicating how to proceed
     */
    fun check(
        toolName: String,
        params: JSONObject = JSONObject(),
        packageName: String? = null,
        destinationPackage: String? = null,
        riskLevel: ActionRisk = ActionRisk.LOW,
        requiresConfirmation: Boolean = false
    ): PolicyDecision {
        val currentMode = approvalMode.get()
        val currentTier = appClassifier.classify(packageName)
        val destTier = destinationPackage?.let { appClassifier.classify(it) }
        // Effective tier = stricter of the two (lower ordinal = stricter)
        val effectiveTier = if (destTier != null) minOf(currentTier, destTier) else currentTier
        val approvalSubject = destinationPackage ?: packageName
        Log.d(TAG, "Policy check: tool=$toolName, pkg=$packageName, dest=$destinationPackage, tier=$effectiveTier, mode=$currentMode, risk=$riskLevel, confirm=$requiresConfirmation")

        val tool = ToolName.from(toolName)

        // 1. Non-screen-changing tools → always allow.
        if (!tool.isScreenChanging) return PolicyDecision.Allow

        // 2. Escape actions (back/home) → always allow (agent must not be trapped).
        if (isEscape(tool, params)) return PolicyDecision.Allow

        // 3. Effective tier of BLOCKED denies — even AUTO_APPROVE cannot bypass at this layer.
        //    Bundled-BLOCKED is the absolute floor: AppClassifier refuses any non-BLOCKED
        //    override at write time and pins classify() to BLOCKED at read time, so a stale
        //    override entry cannot reach this step. Still applies to EITHER current or
        //    destination ("stricter wins" rule for open_app).
        if (effectiveTier == AppTier.BLOCKED) {
            return PolicyDecision.Deny("Blocked: financial/auth app ($packageName)")
        }

        // 4. Action-risk layer (M1 Step 6). The declared risk is loaded from the registered tool
        //    definition by ToolRouter — never trusted from LLM arguments — so an LLM call cannot
        //    re-classify a tool's risk. Application-level and action-level policy stay separate:
        //    this layer gates the *action*, the tier layer above gates the *app*.
        //    - CRITICAL is an absolute floor for MVP: no approval mode and no user confirmation
        //      may make it executable within this phase.
        //    - requiresConfirmation=true always asks, no matter the declared risk (a tool that
        //      declares it cannot silently run).
        //    - HIGH always requires explicit confirmation — neither AUTO_APPROVE nor the session
        //      allow-list can auto-approve it.
        //    - LOW/MEDIUM continue through the app-tier / mode policy unchanged (a tool cannot
        //      downgrade its own declared HIGH risk via requiresConfirmation=false).
        if (riskLevel == ActionRisk.CRITICAL) {
            Log.w(TAG, "CRITICAL-risk action denied for tool=$toolName")
            return PolicyDecision.Deny(
                "Blocked: CRITICAL-risk action is not supported in this phase (${toolName})"
            )
        }
        if (requiresConfirmation || riskLevel == ActionRisk.HIGH) {
            val reason =
                if (riskLevel == ActionRisk.HIGH) {
                    "HIGH-risk action requires explicit confirmation"
                } else {
                    "This action requires explicit confirmation"
                }
            return PolicyDecision.AskUser(
                reason = reason,
                appTier = effectiveTier,
                risk = riskLevel
            )
        }

        // 5. browser_script mutates the user's real Chrome profile through CDP. Chrome is a NORMAL
        //    app, but the browser runtime needs its own SMART-mode approval rule and must not be
        //    bypassed by a NORMAL user override or the session allow-list.
        if (tool == ToolName.BrowserScript) {
            return browserScriptDecision(currentMode, effectiveTier)
        }

        // 6. Session allow-list — capsule "Session" button writes here. Gated by ALWAYS_ASK so
        //    the user's "ask me everything" pref always wins over a prior session approval.
        if (currentMode != ApprovalMode.ALWAYS_ASK && isSessionAllowed(approvalSubject)) {
            return PolicyDecision.Allow
        }

        // 7. Apply approval mode using the effective tier. A user NORMAL override on a
        //    bundled-CAUTIOUS app produces NORMAL here, which SMART auto-approves but
        //    ALWAYS_ASK still asks for. (NORMAL overrides on bundled-BLOCKED are impossible —
        //    refused at write time and pinned by classify().)
        return when (currentMode) {
            ApprovalMode.ALWAYS_ASK -> PolicyDecision.AskUser(
                reason = "User requested approval for all actions",
                appTier = effectiveTier
            )
            ApprovalMode.AUTO_APPROVE -> PolicyDecision.Allow
            ApprovalMode.SMART -> when (effectiveTier) {
                AppTier.CAUTIOUS -> PolicyDecision.AskUser(
                    reason = "Unknown app — action requires approval",
                    appTier = effectiveTier
                )
                AppTier.NORMAL -> PolicyDecision.Allow
                AppTier.BLOCKED -> PolicyDecision.Deny("unreachable")  // handled in step 3
            }
        }
    }

    fun setApprovalMode(mode: ApprovalMode) {
        val oldMode = approvalMode.getAndSet(mode)
        Log.d(TAG, "Approval mode changed: $oldMode -> $mode")
    }

    fun getApprovalMode(): ApprovalMode = approvalMode.get()

    fun reset() {
        approvalMode.set(ApprovalMode.SMART)
        sessionAllowedPackages.clear()
    }

    // ===== Session allow-list =====

    fun allowPackageForSession(packageName: String) {
        if (!isValidPackageName(packageName)) {
            Log.w(TAG, "Ignoring invalid package for session allow-list: $packageName")
            return
        }
        sessionAllowedPackages.add(packageName)
        Log.d(TAG, "Session allow-list: +$packageName")
    }

    private fun isValidPackageName(name: String): Boolean =
        name.isNotBlank() && '.' in name

    private fun isSessionAllowed(packageName: String?): Boolean =
        packageName != null && packageName in sessionAllowedPackages

    private fun isEscape(tool: ToolName, params: JSONObject): Boolean {
        // system_button(button="back"|"home")
        if (tool == ToolName.SystemButton) {
            val button = params.optString("button", "").lowercase()
            return button == "back" || button == "home"
        }
        return false
    }

    private fun browserScriptDecision(mode: ApprovalMode, tier: AppTier): PolicyDecision {
        return when (mode) {
            ApprovalMode.ALWAYS_ASK -> PolicyDecision.AskUser(
                reason = "User requested approval for all actions",
                appTier = tier
            )
            ApprovalMode.AUTO_APPROVE -> PolicyDecision.Allow
            ApprovalMode.SMART -> PolicyDecision.AskUser(
                reason = "Browser automation requires approval",
                appTier = tier
            )
        }
    }
}

/**
 * PolicyDecision — Result of policy evaluation.
 */
sealed interface PolicyDecision {
    /** Tool call is allowed to execute immediately */
    data object Allow : PolicyDecision

    /** Tool call is forbidden by policy */
    data class Deny(val reason: String) : PolicyDecision

    /** Tool call requires user approval */
    data class AskUser(
        val reason: String,
        val appTier: AppTier? = null,
        /** Action risk that triggered the approval (M1 Step 6). */
        val risk: ActionRisk? = null
    ) : PolicyDecision
}
