package ai.ruach.action

/**
 * Consequence level of a semantic action (03_ACTION_MODEL.md §9).
 *
 * Risk is attached to the action, not merely to the target application:
 * an action on one app may be LOW while a different action on the same app is
 * HIGH. Only [LOW] is used by M1 (OPEN_APP); the remaining levels exist because
 * the risk scale is part of the documented action contract and will be needed
 * by later phases (read=MEDIUM/HIGH splits, send=HIGH, payments=CRITICAL).
 */
enum class ActionRisk {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}