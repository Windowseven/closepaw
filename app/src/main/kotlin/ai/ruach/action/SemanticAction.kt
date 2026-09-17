package ai.ruach.action

/**
 * Semantic action — RUACH product layer (03_ACTION_MODEL.md §6, §28).
 *
 * A [SemanticAction] is what RUACH wants to accomplish, expressed against the
 * user's intent rather than against screen coordinates, accessibility nodes,
 * or packages. It carries the risk and confirmation metadata consumed by
 * policy evaluation downstream.
 *
 * This model sits ABOVE ClosePaw execution primitives and reuses them
 * unchanged. It must NOT contain: screen coordinates, accessibility nodes,
 * Android Context/Activity/Service references, package-launch implementations,
 * UI selectors, direct tool execution, or LLM calls. Tool selection and Android
 * execution happen later in the existing flow:
 * SemanticAction → tool selection → ToolRouter → PolicyEngine → Executor →
 * AndroidPlatform.
 *
 * Unlike [ai.closepaw.platform.UIAction], which describes low-level Android
 * operations (ClickNodeAt, TapAt, SetTextOnFocused, ...), this model describes
 * intent-level operations only.
 */
sealed interface SemanticAction {

    /** Broad category of the action. */
    val type: ActionType

    /** Consequence of performing this action. */
    val risk: ActionRisk

    /** True when policy must obtain explicit user authorization before executing. */
    val requiresConfirmation: Boolean

    /**
     * Open an application by user-facing name (Navigation; 03_ACTION_MODEL.md
     * §8.1). The execution layer later resolves [target] to a package and
     * launches it via the existing OpenApp tool (e.g. "YouTube" →
     * com.google.android.youtube); this model never holds the package itself.
     *
     * M1 baseline: OPEN_APP → LOW risk → no confirmation required.
     */
    data class OpenApp(
        val target: String,
        override val risk: ActionRisk = ActionRisk.LOW,
        override val requiresConfirmation: Boolean = false,
    ) : SemanticAction {
        override val type: ActionType get() = ActionType.OPEN_APP
    }
}