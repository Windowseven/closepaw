package ai.ruach.voice

import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.ruach.goal.GoalSubmitResult

/**
 * Adapter seams between the pure voice conversation controller and the
 * Android / ElevenLabs-specific implementations.
 *
 * Everything in this file is Android-free so the controller can be
 * exercised in JVM unit tests with recording fakes.
 */

/** Raw audio capture boundary (push-to-talk). */
interface VoiceMicrophone {
    /**
     * Begin capturing audio. Called on main thread.
     * @return null on success, a terminal [VoiceFailure] on immediate failure.
     */
    fun start(): VoiceFailure?

    /**
     * Finish capturing and return the captured audio, or null when nothing was
     * captured (cancelled before first buffer).
     */
    fun finish(): ByteArray?

    /** Abort the current capture without returning audio. */
    fun cancel()
}

/** Speech-to-text boundary. */
interface VoiceTranscriber {
    /** Transcribe raw audio captured by PTT. */
    suspend fun transcribe(audio: ByteArray): TranscribeResult
}

sealed interface TranscribeResult {
    data class Success(val transcript: String) : TranscribeResult
    data class Failure(val failure: VoiceFailure) : TranscribeResult
}

/** Speech synthesis / playback boundary. */
interface VoiceSynthesizer {
    /**
     * Synthesize and play [text] synchronously until the utterance finishes or
     * is stopped via [stop]. Returns after playback completes.
     */
    suspend fun speak(text: String): SpeakResult

    /** Stop any in-flight speech output immediately (barge-in). */
    fun stop()
}

sealed interface SpeakResult {
    data object Success : SpeakResult
    data class Failure(val failure: VoiceFailure) : SpeakResult
}

/**
 * Submit a finalized user utterance through the Goal Gateway.
 * The implementation MUST use source = [GoalSource][ai.ruach.goal.GoalSource].VOICE;
 * callers in the core never supply the source — the Android wiring does.
 */
fun interface VoiceGoalSubmitter {
    suspend fun submitGoal(transcript: String): GoalSubmitResult
}

/**
 * Discrete task-outcome vocabulary used by the controller.
 *
 * Mapped from the ClosePaw task outcome enums by the Android coordinator.
 * Kept here so the pure controller never imports foundation protocol types
 * unnecessarily.
 */
enum class TaskCompletion {
    /** The agent reports the goal was achieved (post-verification). */
    ACHIEVED,
    /** The user or system explicitly stopped the task. */
    USER_STOPPED,
    /** The agent judged the task impossible. */
    IMPOSSIBLE,
    /** An error prevented completion. */
    ERROR,
}

/**
 * Minimal response channel from the controller back into the agent session.
 *
 * Only existing ClosePaw operations are used — no new security model is added.
 * The voice adapter is purely a presentation channel:
 * - Voice input can express intent but cannot grant authority by itself.
 * - Scope of voice-approved actions is always [ApprovalScope.SESSION] (single
 *   authorization); the voice adapter never uses [ApprovalScope.ALWAYS].
 */
interface VoiceResponseSink {
    /** Deliver a user response to a pending `ask_user` request. */
    suspend fun respondToAsk(callId: String, response: String)

    /**
     * Deliver an approval decision for the currently pending action.
     * Implementations MUST map this to [ai.closepaw.protocol.Op.Approve]
     * with exactly the provided [actionId] — fresh, scoped, one-shot.
     */
    suspend fun respondToApproval(
        actionId: String,
        decision: ApprovalDecision,
        scope: ApprovalScope,
        packageName: String,
    )

    /** Request the agent stop the current task ([ai.closepaw.protocol.Op.Interrupt]). */
    suspend fun interruptTask()
}