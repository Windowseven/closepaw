package ai.ruach.goal

/**
 * Goal Gateway — RUACH product layer Band B (02_ARCHITECTURE.md §19, 08 Phase 3).
 *
 * Converts a raw user input (text/voice/UI) into a normalized [GoalRequest] and
 * submits it to the agent pipeline through the injected [GoalSubmitter].
 *
 * The gateway is deliberately thin: it contains no planning, no
 * application-specific workflows, and no Android access. Submission routing
 * (existing session vs new session vs creation queue) is owned by the injected
 * submitter / ClosePaw SessionCoordinator, so nothing is duplicated.
 */
class GoalGateway(private val submitter: GoalSubmitter) {

    suspend fun submit(input: String, source: GoalSource): GoalSubmitResult {
        val request = GoalNormalizer.normalize(input, source)
            ?: return GoalSubmitResult.Rejected(nullReasonFor(input))
        submitter.submit(request)
        return GoalSubmitResult.Accepted(request)
    }

    private fun nullReasonFor(input: String): String = when {
        input.isBlank() -> "Goal is empty"
        else -> "Goal is too long (max ${GoalNormalizer.MAX_GOAL_LENGTH} characters)"
    }
}

/** Submission seam into the existing ClosePaw session/coordinator pipeline. */
fun interface GoalSubmitter {
    suspend fun submit(goalRequest: GoalRequest)
}

sealed interface GoalSubmitResult {
    data class Accepted(val goalRequest: GoalRequest) : GoalSubmitResult
    data class Rejected(val reason: String) : GoalSubmitResult
}