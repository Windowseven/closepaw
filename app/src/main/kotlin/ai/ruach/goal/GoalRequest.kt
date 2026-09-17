package ai.ruach.goal

/**
 * A normalized user goal, ready for the ClosePaw agent pipeline.
 *
 * Per 02_ARCHITECTURE.md Boundary B, the goal layer converts external
 * interaction (text/voice/UI) into an internal task goal. It must NOT
 * manipulate Android or contain application-specific workflows.
 */
data class GoalRequest(
    val source: GoalSource,
    val rawInput: String,
    val goal: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

/** Pure validation + normalization of raw input into a [GoalRequest]. */
internal object GoalNormalizer {
    const val MAX_GOAL_LENGTH = 500

    /**
     * Returns a normalized [GoalRequest], or null when the input cannot form a
     * valid goal (blank or over the length limit).
     */
    fun normalize(input: String, source: GoalSource): GoalRequest? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_GOAL_LENGTH) return null
        return GoalRequest(
            source = source,
            rawInput = input,
            goal = collapseWhitespace(trimmed),
        )
    }

    private fun collapseWhitespace(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()
}