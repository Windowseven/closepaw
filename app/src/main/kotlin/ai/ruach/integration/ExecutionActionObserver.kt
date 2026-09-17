package ai.ruach.integration

import ai.closepaw.agent.ToolCallRequest
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.tool.ToolCallResult

/**
 * RUACH product-layer observation hook into the existing ClosePaw execution loop (M1).
 *
 * Called from the single existing execution seam — [ai.closepaw.agent.TurnExecutionPhaseRunner],
 * the same place [ai.closepaw.trace.AgentTrace] writes `tool_call` / `tool_result` — so the
 * product layer can observe which semantic action the existing agent actually selected and its
 * verified outcome, WITHOUT a second planner or a parallel executor.
 *
 * In M1 the existing LLM — via the existing tool pipeline — is the *authoritative* producer of
 * the `open_app` tool call; this observer derives the RUACH [ai.ruach.action.SemanticAction]
 * observationally from that call and records the product-layer trace stages on top of the
 * lower-level ClosePaw trace.
 *
 * The observer is attached per-session ([ai.closepaw.session.SessionServices.executionActionObserver])
 * and is null elsewhere, so the ClosePaw runtime behaviour is unchanged when RUACH is not attached.
 */
interface ExecutionActionObserver {

    /** A selected tool call is about to execute in the existing pipeline. */
    suspend fun onActionCreated(turnId: String, turnNumber: Int, toolCall: ToolCallRequest)

    /** The tool call finished; [toolResult] is the final outcome, [platform] for verification. */
    suspend fun onActionCompleted(
        turnId: String,
        turnNumber: Int,
        toolCall: ToolCallRequest,
        toolResult: ToolCallResult,
        platform: AndroidPlatform,
    )
}