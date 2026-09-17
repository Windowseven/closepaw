package ai.ruach.goal

/**
 * Where a user goal enters RUACH.
 *
 * Voice, text, and future UI inputs must all funnel into the same [GoalGateway]
 * so the ClosePaw agent pipeline stays single-sourced (02_ARCHITECTURE.md §19).
 */
enum class GoalSource {
    TEXT,
    VOICE,
    UI,
}