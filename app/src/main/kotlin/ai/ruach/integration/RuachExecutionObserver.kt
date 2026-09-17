package ai.ruach.integration

import ai.closepaw.agent.ToolCallRequest
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.tool.ToolCallResult
import ai.ruach.trace.ActionTrace
import ai.ruach.trace.ActionTraceEvent
import ai.ruach.trace.ActionResultStatus

/**
 * Server-side observer bound to one session's [ActionTrace]: turns the existing agent's selected
 * tool calls into RUACH product-layer trace stages.
 *
 * The semantic action is *observational* in M1 — derived from the existing agent's own `open_app`
 * tool call via [SemanticActionMapper] — because the existing LLM already, independently and
 * authoritatively, produces the tool call through the existing pipeline. No second planner is
 * introduced; when a tool is outside the M1 semantic scope the observer simply records nothing.
 */
internal class RuachExecutionObserver(
    private val actionTrace: ActionTrace,
) : ExecutionActionObserver {

    override suspend fun onActionCreated(turnId: String, turnNumber: Int, toolCall: ToolCallRequest) {
        val action = SemanticActionMapper.fromToolCall(toolCall) ?: return
        actionTrace.recordEvent(
            ActionTraceEvent.ActionCreated(action = action),
            turnId = turnId,
            turnNumber = turnNumber,
        )
    }

    override suspend fun onActionCompleted(
        turnId: String,
        turnNumber: Int,
        toolCall: ToolCallRequest,
        toolResult: ToolCallResult,
        platform: AndroidPlatform,
    ) {
        val action = SemanticActionMapper.fromToolCall(toolCall) ?: return
        val status =
            when (toolResult) {
                is ToolCallResult.Success -> ActionResultStatus.COMPLETED
                is ToolCallResult.Error -> ActionResultStatus.FAILED
                is ToolCallResult.Cancelled -> ActionResultStatus.CANCELLED
            }
        val verified = SemanticActionVerifier.isVerified(action, toolResult, platform)
        actionTrace.recordEvent(
            ActionTraceEvent.ActionCompleted(
                action = action,
                status = status,
                verified = verified,
                summary = safeSummary(toolResult),
            ),
            turnId = turnId,
            turnNumber = turnNumber,
        )
    }

    /** Safe summary: the tool's own result text (no raw user input is copied). */
    private fun safeSummary(toolResult: ToolCallResult): String? =
        when (toolResult) {
            is ToolCallResult.Success -> toolResult.output.trim().takeIf { it.isNotEmpty() }
            is ToolCallResult.Error -> toolResult.error.trim().takeIf { it.isNotEmpty() }
            is ToolCallResult.Cancelled -> toolResult.reason.trim().takeIf { it.isNotEmpty() }
        }
}