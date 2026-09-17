package ai.ruach.voice

/**
 * Turns verified, structured agent state into the text the voice layer may say
 * (06_ELEVENLABS_INTEGRATION.md §23, §24).
 *
 * Rules enforced here (never claim unverified success):
 * - A completed task is only spoken as success when the completion state is
 *   [TaskCompletion.ACHIEVED] — the agent's post-verification verdict.
 * - The agent-provided result text is spoken verbatim when present; otherwise a
 *   neutral, state-derived sentence is used.
 * - No invented per-command phrasing; no hard-coded command words.
 * - Failure states are reported factually, never as "done".
 */
object VoiceResponseFormatter {

    /** Spoken text for a terminal task outcome. */
    fun taskCompletion(completion: TaskCompletion, resultText: String?): String {
        val result = resultText?.trim().orEmpty()
        return when (completion) {
            TaskCompletion.ACHIEVED ->
                result.ifNotEmpty { it } ?: "I finished the task."
            TaskCompletion.USER_STOPPED ->
                result.ifNotEmpty { it } ?: "Stopped."
            TaskCompletion.IMPOSSIBLE ->
                result.ifNotEmpty { it } ?: "I couldn't complete this task."
            TaskCompletion.ERROR ->
                result.ifNotEmpty { it } ?: "The task failed."
        }
    }

    /** Spoken text for a session-level error. */
    fun sessionError(message: String): String =
        message.trim().ifNotEmpty { it } ?: "Something went wrong."

    /**
     * Spoken question for a pending app approval. The question is derived from the
     * pending action state only — it names what is about to happen (scope, §15) and
     * never pretends approval affects anything beyond the pending action.
     */
    fun approvalPrompt(appLabel: String, description: String): String {
        val detail = description.trim().ifNotEmpty { it }
        return if (detail != null) {
            val stem = detail.removeSuffix("?").removeSuffix(".")
            "$stem?"
        } else {
            "Approve $appLabel?"
        }
    }

    /** Spoken question for a pending `ask_user` request (verbatim agent question). */
    fun askPrompt(question: String): String =
        question.trim().ifNotEmpty { it } ?: "Go ahead, I'm listening."

    private fun String.ifNotEmpty(block: (String) -> String): String? =
        ifEmpty { null }?.let { block(this) }
}