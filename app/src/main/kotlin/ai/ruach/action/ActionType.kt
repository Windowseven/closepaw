package ai.ruach.action

/**
 * Broad category of a semantic action (03_ACTION_MODEL.md §8).
 *
 * M1 only implements [OPEN_APP]. Future types (SEARCH, READ, COMPOSE, MODIFY,
 * COMMIT) are extensions of the sealed [SemanticAction] hierarchy and this
 * enum; none of their workflows are modeled yet.
 */
enum class ActionType {
    OPEN_APP,
}