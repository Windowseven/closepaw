package ai.ruach.integration

import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.tool.ToolCallResult
import ai.closepaw.tool.impl.AppAliases
import ai.ruach.action.SemanticAction

/**
 * M1 product-layer verification: was the action's expected goal actually observed on the device?
 *
 * For [SemanticAction.OpenApp] the expected end-state is that the current foreground package
 * equals the resolved target package, using the existing Android execution abstraction
 * ([AndroidPlatform.getCurrentPackageName]) and the existing app-resolution mechanism
 * ([AppAliases], which the existing OpenAppTool uses). No RUACH class manipulates Android directly.
 *
 * Resolution order mirrors the existing pre-flight resolver (ToolRouter / OpenAppInvocation):
 * well-known alias → exact label match → package-shaped input. A shared resolver is a documented
 * follow-up; for M1 this small resolver is the least-duplication honest check.
 */
internal object SemanticActionVerifier {

    suspend fun isVerified(
        action: SemanticAction,
        toolResult: ToolCallResult,
        platform: AndroidPlatform,
    ): Boolean {
        if (toolResult !is ToolCallResult.Success) return false
        val openApp = action as? SemanticAction.OpenApp ?: return false
        val targetPackage = resolvePackage(openApp, platform) ?: return false
        val current = platform.getCurrentPackageName()
        return current != null && current == targetPackage
    }

    private suspend fun resolvePackage(
        action: SemanticAction.OpenApp,
        platform: AndroidPlatform,
    ): String? {
        val term = action.target.trim().lowercase()
        if (term.isEmpty()) return null
        AppAliases.PACKAGE_MAP[term]?.let { return it }
        val apps = platform.getInstalledApps()
        apps.find { it.label.equals(action.target, ignoreCase = true) }?.let { return it.packageName }
        if (looksLikePackageName(term)) {
            apps.find { it.packageName.equals(term, ignoreCase = true) }?.let { return it.packageName }
        }
        return null
    }

    private fun looksLikePackageName(input: String): Boolean =
        input.contains('.') && input.split('.').size >= 2
}