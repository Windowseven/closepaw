package ai.ruach.voice

/**
 * Maps a spoken utterance to a discrete approval intent.
 *
 * This is a *closed, deliberately small* vocabulary used ONLY while a fresh
 * [VoicePendingApproval] is awaiting a voice decision. It is not a command
 * system and it does not run on ordinary goals: an un-matching utterance
 * resolves to [ApprovalIntent.OTHER], which the controller treats as "not a
 * decision" (the prompt is re-spoken and nothing is authorized). 06 doc §14/§43:
 * voice exists to present the pending action — not to grant authority on its own.
 *
 * Scope checks that depend on correctness:
 * - Applied only when a pending approval exists (fresh, scoped to one action).
 * - Anything that is not an unambiguous yes/no leaves the approval unresolved.
 * - It never authorises ANY other action, package, or scope.
 */
enum class ApprovalIntent {
    APPROVE,
    DENY,
    OTHER,
}

fun interface ApprovalUtteranceInterpreter {
    fun interpret(utterance: String): ApprovalIntent
}

object DefaultApprovalUtteranceInterpreter : ApprovalUtteranceInterpreter {

    private val approveTokens = setOf(
        "yes", "yeah", "yep", "yup", "ok", "okay", "sure", "go", "approve",
        "confirmed", "confirm", "allow", "affirmative",
    )

    private val denyTokens = setOf(
        "no", "nope", "nah", "deny", "reject", "decline", "cancel", "stop",
        "hang", "never", "don't", "dont",
    )

    override fun interpret(utterance: String): ApprovalIntent {
        val normalized = utterance
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim('.', ',', '!', '?', ':', ';')

        if (normalized.isEmpty()) return ApprovalIntent.OTHER

        val firstToken = normalized.substringBefore(' ')
        if (firstToken.isEmpty()) return ApprovalIntent.OTHER

        return when {
            firstToken in approveTokens -> ApprovalIntent.APPROVE
            firstToken in denyTokens -> ApprovalIntent.DENY
            else -> ApprovalIntent.OTHER
        }
    }
}