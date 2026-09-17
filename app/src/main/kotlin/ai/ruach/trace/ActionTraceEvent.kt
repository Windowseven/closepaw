package ai.ruach.trace

import ai.ruach.action.SemanticAction
import ai.ruach.goal.GoalRequest

/**
 * RUACH pipeline trace event (03_ACTION_MODEL.md §25).
 *
 * Represents one observable stage of the RUACH product-layer pipeline:
 * goal received → action created → result verified / goal completed.
 *
 * This is the persisted trace model; it is converted to [TraceEventRecord]s
 * by [ActionTrace]. The existing ClosePaw trace already records the
 * execution-layer stages (`tool_call`, `tool_result`, `tool_arbitration`,
 * `screen_captured`); this model adds the semantic/product stages above them.
 *
 * Pure domain: no Android dependencies, no LLM dependencies, no serialization
 * annotations (the trace encoder serializes to JsonElement manually, matching
 * the [ai.closepaw.trace.AgentTrace] pattern).
 */
sealed interface ActionTraceEvent {

    /** When this event occurred. */
    val tsMs: Long

    /**
     * The goal gateway produced a normalised [GoalRequest].
     *
     * Trace data: `source`, `goal`. The raw input is intentionally excluded
     * for data minimisation (03_ACTION_MODEL.md §24).
     */
    data class GoalReceived(
        val request: GoalRequest,
        override val tsMs: Long = System.currentTimeMillis(),
    ) : ActionTraceEvent

    /**
     * The pipeline produced a concrete [SemanticAction] from the goal.
     *
     * Trace data: `action_type`, `risk`, `requires_confirmation`, plus
     * action-specific fields (e.g. `target` for OPEN_APP).
     */
    data class ActionCreated(
        val action: SemanticAction,
        override val tsMs: Long = System.currentTimeMillis(),
    ) : ActionTraceEvent

    /**
     * The action finished execution; observed outcome is known.
     *
     * Trace data: `action_type`, `status`, `verified`, `summary`.
     */
    data class ActionCompleted(
        val action: SemanticAction,
        val status: ActionResultStatus,
        val verified: Boolean,
        val summary: String? = null,
        override val tsMs: Long = System.currentTimeMillis(),
    ) : ActionTraceEvent
}

/**
 * Final state of an action (03_ACTION_MODEL.md §13, §15).
 *
 * M1 covers the minimum set for a single navigation action. The full set
 * (DENIED, BLOCKED, NEEDS_USER_INPUT) arrives with policy/action-risk wiring
 * in later phases.
 */
enum class ActionResultStatus {
    COMPLETED,
    FAILED,
    CANCELLED,
    VERIFICATION_FAILED,
}