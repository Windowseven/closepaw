package ai.ruach.voice

/**
 * Minimal voice-conversation state model (06_ELEVENLABS_INTEGRATION.md §10, §19).
 *
 * This is the *voice channel* state table, intentionally separate from the agent
 * task lifecycle: one voice conversation may contain many tasks, and one task may
 * span many voice turns. A voice conversation can end while an agent task keeps
 * running, and vice versa.
 *
 * The nine states correspond to the MVP push-to-talk slice:
 * - [Idle] — nothing active; PTT can begin a capture.
 * - [Listening] — PTT is held, audio is being captured.
 * - [Transcribing] — audio captured, waiting on the speech provider.
 * - [Submitting] — transcript normalized through the Goal Gateway.
 * - [WaitingForAgent] — speech submitted; the agent is working or waiting on input.
 * - [Speaking] — a spoken response (or a pending prompt) is playing back.
 * - [Interrupted] — speech output was stopped by the user (barge-in) — the agent
 *   task is untouched. Distinct from [Cancelled].
 * - [Cancelled] — a PTT capture was cancelled before transcription.
 * - [Error] — a voice-side failure occurred; a retry starts a fresh capture.
 */
enum class VoiceConversationState {
    Idle,
    Listening,
    Transcribing,
    Submitting,
    WaitingForAgent,
    Speaking,
    Interrupted,
    Cancelled,
    Error,
}