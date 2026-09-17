package ai.ruach.voice

import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.ruach.goal.GoalSubmitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pending `ask_user` prompt awaiting a voice answer.
 */
data class PendingAsk(
    val callId: String,
    val question: String,
)

/**
 * Pending app-approval prompt awaiting a voice decision.
 *
 * Fields come from the existing approval lifecycle (CapsuleMode.WaitingForApproval /
 * the pending approval's ApprovalDetails). Scope/freshness is preserved because the
 * decision is only ever applied to [actionId] with [ApprovalScope.SESSION] — never
 * [ApprovalScope.ALWAYS], never a different action, never a different package.
 */
data class PendingApproval(
    val actionId: String,
    val packageName: String,
    val appLabel: String,
    val description: String,
)

/**
 * Voice conversation controller for the MVP push-to-talk slice.
 *
 * Owns ONLY the voice channel lifecycle — [VoiceConversationState]. The agent task
 * lifecycle stays in the session; this controller never routes around the session,
 * never touches Android execution, and never implements planning/execution/policy.
 *
 * Lifecycle responsibilities:
 * - Push-to-talk capture → transcription → Goal Gateway (source=VOICE) submission.
 * - Spoken responses derived from verified agent state ([VoiceResponseFormatter]).
 * - Spoken prompts + voice decisions for the existing `ask_user` / approval model
 *   via [VoiceResponseSink] — the adapter is a presentation channel only.
 * - [interruptSpeech] stops speech playback WITHOUT touching the agent task;
 *   [cancelCurrentTask] stops the task via the existing interrupt op.
 *
 * The controller is pure Kotlin (no Android types) and depends only on injected
 * seams, so it is fully JVM-testable with recording fakes.
 */
class VoiceConversationController(
    private val microphone: VoiceMicrophone,
    private val transcriber: VoiceTranscriber,
    private val synthesizer: VoiceSynthesizer,
    private val goalSubmitter: VoiceGoalSubmitter,
    private val responses: VoiceResponseSink,
    private val isConfigured: () -> Boolean,
    private val interpreter: ApprovalUtteranceInterpreter = DefaultApprovalUtteranceInterpreter,
    private val formatter: VoiceResponseFormatter = VoiceResponseFormatter,
    private val onFeedback: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<VoiceConversationState>(VoiceConversationState.Idle)
    val state: StateFlow<VoiceConversationState> = _state.asStateFlow()

    private var pendingAsk: PendingAsk? = null
    private var pendingApproval: PendingApproval? = null

    /** Whether an answer is currently awaited from the user (ask or approval). */
    val hasPendingPrompt: Boolean
        get() = pendingAsk != null || pendingApproval != null

    // ===== Push-to-talk =====

    /**
     * Begin a PTT capture. Returns null on success, or a [VoiceFailure] explaining
     * why the capture could not start (state is set to [VoiceConversationState.Error]).
     *
     * Barge-in: if speech is currently being spoken, it is stopped first; capturing
     * then begins normally. This never interrupts the agent task.
     */
    fun startPushToTalk(): VoiceFailure? {
        when (_state.value) {
            VoiceConversationState.Listening,
            VoiceConversationState.Transcribing,
            VoiceConversationState.Submitting,
            -> return null // Already recording/finalizing; ignore double-press.
            else -> Unit
        }
        if (!isConfigured()) {
            setState(VoiceConversationState.Error)
            onFeedback(VoiceFailure.ProviderNotConfigured.userMessage)
            return VoiceFailure.ProviderNotConfigured
        }
        // Barge-in: stop any pending speech before opening the microphone.
        if (_state.value == VoiceConversationState.Speaking) {
            synthesizer.stop()
        }
        val micFailure = microphone.start()
        if (micFailure != null) {
            setState(VoiceConversationState.Error)
            onFeedback(micFailure.userMessage)
            return micFailure
        }
        setState(VoiceConversationState.Listening)
        return null
    }

    /**
     * Release PTT: capture → transcribe → dispatch. Once a finalized transcript
     * exists it flows through exactly one existing mechanism:
     *  1. a pending approval decision, or
     *  2. a pending `ask_user` answer, or
     *  3. the Goal Gateway (source=VOICE) as a new goal.
     *
     * Returns null on success, a [VoiceFailure] otherwise. A transcript is produced
     * at most once per capture; failures never resubmit.
     */
    suspend fun stopPushToTalk(): VoiceFailure? {
        if (_state.value != VoiceConversationState.Listening) return null

        val audio = microphone.finish()
        if (audio == null || audio.isEmpty()) {
            setState(VoiceConversationState.Idle)
            return VoiceFailure.TranscriptEmpty
        }

        setState(VoiceConversationState.Transcribing)
        val transcript = when (val result = transcriber.transcribe(audio)) {
            is TranscribeResult.Failure -> {
                setState(VoiceConversationState.Error)
                onFeedback(result.failure.userMessage)
                return result.failure
            }
            is TranscribeResult.Success -> result.transcript.trim()
        }
        if (transcript.isEmpty()) {
            // Nothing recognizable — reject without submitting anything.
            setState(VoiceConversationState.Idle)
            onFeedback(VoiceFailure.TranscriptEmpty.userMessage)
            return VoiceFailure.TranscriptEmpty
        }

        setState(VoiceConversationState.Submitting)
        return dispatchTranscript(transcript)
    }

    /** Cancel an in-flight PTT capture before transcription. */
    fun cancelPushToTalk(): Boolean {
        if (_state.value != VoiceConversationState.Listening) return false
        microphone.cancel()
        setState(VoiceConversationState.Cancelled)
        return true
    }

    private suspend fun dispatchTranscript(transcript: String): VoiceFailure? {
        val approval = pendingApproval
        if (approval != null) {
            return when (interpreter.interpret(transcript)) {
                ApprovalIntent.APPROVE -> {
                    responses.respondToApproval(
                        actionId = approval.actionId,
                        decision = ApprovalDecision.APPROVED,
                        scope = ApprovalScope.SESSION,
                        packageName = approval.packageName,
                    )
                    pendingApproval = null
                    setState(VoiceConversationState.WaitingForAgent)
                    null
                }
                ApprovalIntent.DENY -> {
                    responses.respondToApproval(
                        actionId = approval.actionId,
                        decision = ApprovalDecision.DENIED,
                        scope = ApprovalScope.SESSION,
                        packageName = approval.packageName,
                    )
                    pendingApproval = null
                    setState(VoiceConversationState.WaitingForAgent)
                    null
                }
                ApprovalIntent.OTHER -> {
                    // Not a decision: re-ask, leave the approval unresolved.
                    speak(formatter.approvalPrompt(approval.appLabel, approval.description))
                    setState(VoiceConversationState.WaitingForAgent)
                    null
                }
            }
        }

        val ask = pendingAsk
        if (ask != null) {
            responses.respondToAsk(ask.callId, transcript)
            pendingAsk = null
            setState(VoiceConversationState.WaitingForAgent)
            return null
        }

        return when (val result = goalSubmitter.submitGoal(transcript)) {
            is GoalSubmitResult.Accepted -> {
                setState(VoiceConversationState.WaitingForAgent)
                null
            }
            is GoalSubmitResult.Rejected -> {
                setState(VoiceConversationState.Idle)
                onFeedback(VoiceFailure.GoalRejected(result.reason).userMessage)
                VoiceFailure.GoalRejected(result.reason)
            }
        }
    }

    // ===== Speech control (distinct from task control) =====

    /**
     * Stop speech output immediately. The agent task is NOT touched — this is
     * barge-in only. Use [cancelCurrentTask] to stop the task.
     */
    fun interruptSpeech() {
        synthesizer.stop()
        if (_state.value == VoiceConversationState.Speaking) {
            setState(VoiceConversationState.Interrupted)
        }
    }

    /**
     * Stop speech AND request the agent stop the current task via the existing
     * interrupt op. Both channels are distinct from the voice capture lifecycle.
     */
    suspend fun cancelCurrentTask() {
        responses.interruptTask()
        synthesizer.stop()
        setState(VoiceConversationState.Interrupted)
    }

    // ===== Agent event intake =====

    /** The agent started / is continuing work on a task. */
    fun onTaskStarted() {
        pendingAsk = null
        pendingApproval = null
        if (_state.value == VoiceConversationState.Idle) {
            setState(VoiceConversationState.WaitingForAgent)
        }
    }

    /** A pending `ask_user` prompt is open and should be spoken. */
    suspend fun onPendingAskOpen(callId: String, question: String) {
        pendingAsk = PendingAsk(callId = callId, question = question)
        pendingApproval = null
        speak(formatter.askPrompt(question))
    }

    /** A pending app-approval prompt is open and should be spoken. */
    suspend fun onPendingApprovalOpen(approval: PendingApproval) {
        pendingApproval = approval
        pendingAsk = null
        speak(formatter.approvalPrompt(approval.appLabel, approval.description))
    }

    /** The pending prompt was resolved (through any channel). */
    fun onPendingPromptResolved() {
        val hadPrompt = pendingAsk != null || pendingApproval != null
        pendingAsk = null
        pendingApproval = null
        if (hadPrompt && _state.value == VoiceConversationState.WaitingForAgent) {
            setState(VoiceConversationState.Idle)
        }
    }

    /** A task ended with a verified outcome; speak the derived result. */
    suspend fun onTaskCompleted(completion: TaskCompletion, resultText: String?) {
        pendingAsk = null
        pendingApproval = null
        speak(formatter.taskCompletion(completion, resultText))
    }

    /** A session-level error occurred; speak the derived message. */
    suspend fun onSessionError(message: String) {
        pendingAsk = null
        pendingApproval = null
        speak(formatter.sessionError(message))
    }

    // ===== Internals =====

    private suspend fun speak(text: String) {
        if (!isConfigured()) {
            setState(VoiceConversationState.Idle)
            return
        }
        setState(VoiceConversationState.Speaking)
        when (val result = synthesizer.speak(text)) {
            is SpeakResult.Failure -> {
                setState(VoiceConversationState.Idle)
                onFeedback(result.failure.userMessage)
                return
            }
            SpeakResult.Success -> Unit
        }
        setState(
            if (pendingAsk != null || pendingApproval != null) {
                VoiceConversationState.WaitingForAgent
            } else {
                VoiceConversationState.Idle
            }
        )
    }

    private fun setState(next: VoiceConversationState) {
        _state.value = next
    }
}