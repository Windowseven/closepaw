package ai.ruach.integration

import ai.closepaw.agent.ToolCallRequest
import ai.closepaw.tool.ToolName
import ai.ruach.action.SemanticAction

/**
 * Maps the existing tool pipeline's selected tool call to a RUACH [SemanticAction] (M1).
 *
 * Generic and domain-driven: only [ToolName.OpenApp] is mapped (M1 scope), the target comes
 * from the tool call's own `app_name` argument, and no application is ever hardcoded. There is
 * deliberately no application-specific classifier here — the existing agent (LLM + tool
 * arbitration) remains the authoritative action selector.
 *
 * Returns null for tools outside the M1 semantic scope; the observer then records nothing,
 * keeping the RUACH trace additive rather than a second execution engine.
 */
internal object SemanticActionMapper {

    fun fromToolCall(toolCall: ToolCallRequest): SemanticAction? {
        if (toolCall.name != ToolName.OpenApp.raw) return null
        val target = toolCall.arguments.optString("app_name", "").trim()
        if (target.isEmpty()) return null
        // Risk / confirmation come from the action model defaults (OpenApp = LOW, no
        // confirmation), matching the metadata declared on the existing OpenAppTool.
        return SemanticAction.OpenApp(target = target)
    }
}