package ai.ruach.trace

import ai.closepaw.trace.TraceEventRecord
import ai.closepaw.trace.TraceRecorder
import ai.closepaw.trace.emit
import ai.ruach.action.SemanticAction
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Bridges RUACH product-layer [ActionTraceEvent]s into the persisted
 * [TraceRecorder] event stream (03_ACTION_MODEL.md §25).
 *
 * The existing [ai.closepaw.trace.AgentTrace] already records execution-layer
 * stages (`tool_call`, `tool_result`, `screen_captured`, …); [ActionTrace]
 * adds the semantic/product stages that sit above them: `goal_received`,
 * `action_created`, `action_completed`.
 *
 * One instance per session; lifecycle managed by the session coordinator
 * (step 5 wiring). [recordEvent] is a hot path — guarded by
 * [TraceRecorder.enabled] / [TraceRecorder.runId] before any JSON is built.
 *
 * Thread-safety: safe to call from any coroutine context; the underlying
 * [TraceRecorder] handles its own synchronisation.
 */
internal class ActionTrace(
    private val sessionId: String,
    private val trace: TraceRecorder,
) {

    fun recordEvent(
        event: ActionTraceEvent,
        turnId: String? = null,
        turnNumber: Int? = null,
    ) {
        if (!trace.enabled) return
        val run = trace.runId ?: return
        val (type, data) = when (event) {
            is ActionTraceEvent.GoalReceived -> goalReceivedData(event)
            is ActionTraceEvent.ActionCreated -> actionCreatedData(event)
            is ActionTraceEvent.ActionCompleted -> actionCompletedData(event)
        }
        trace.emit(
            sessionId = sessionId,
            type = type,
            turnId = turnId,
            turnNumber = turnNumber,
            tsMs = event.tsMs,
            data = data,
        )
    }

    private fun goalReceivedData(event: ActionTraceEvent.GoalReceived): Pair<String, kotlinx.serialization.json.JsonElement> =
        "goal_received" to buildJsonObject {
            put("source", JsonPrimitive(event.request.source.name.lowercase()))
            put("goal", JsonPrimitive(event.request.goal))
        }

    private fun actionCreatedData(event: ActionTraceEvent.ActionCreated): Pair<String, kotlinx.serialization.json.JsonElement> =
        "action_created" to buildJsonObject {
            put("action_type", JsonPrimitive(event.action.type.name.lowercase()))
            put("risk", JsonPrimitive(event.action.risk.name.lowercase()))
            put("requires_confirmation", JsonPrimitive(event.action.requiresConfirmation))
            putActionSpecificFields(event.action)
        }

    private fun actionCompletedData(event: ActionTraceEvent.ActionCompleted): Pair<String, kotlinx.serialization.json.JsonElement> =
        "action_completed" to buildJsonObject {
            put("action_type", JsonPrimitive(event.action.type.name.lowercase()))
            put("status", JsonPrimitive(event.status.name.lowercase()))
            put("verified", JsonPrimitive(event.verified))
            event.summary?.let { put("summary", JsonPrimitive(it)) }
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putActionSpecificFields(action: SemanticAction) {
        when (action) {
            is SemanticAction.OpenApp ->
                put("target", JsonPrimitive(action.target))
        }
    }
}