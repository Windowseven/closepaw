package ai.ruach.voice

import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.ruach.goal.GoalRequest
import ai.ruach.goal.GoalSource
import ai.ruach.goal.GoalSubmitResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VoiceConversationControllerTest {

    // ===== Seam fakes =====

    private class FakeMicrophone(var nextAudios: MutableList<ByteArray?> = mutableListOf()) : VoiceMicrophone {
        var failure: VoiceFailure? = null
        var startCount = 0
        var finishCount = 0
        var cancelCount = 0
        override fun start(): VoiceFailure? {
            startCount++
            return failure
        }

        override fun finish(): ByteArray? {
            finishCount++
            return if (nextAudios.isEmpty()) ByteArray(0) else nextAudios.removeAt(0)
        }

        override fun cancel() {
            cancelCount++
        }
    }

    private class RecordingTranscriber(var result: TranscribeResult = TranscribeResult.Success("transcribed text")) :
        VoiceTranscriber {
        val audioSeen = mutableListOf<ByteArray>()
        var calls = 0
        override suspend fun transcribe(audio: ByteArray): TranscribeResult {
            calls++
            audioSeen.add(audio)
            return result
        }
    }

    private class RecordingSynthesizer : VoiceSynthesizer {
        val spoken = mutableListOf<String>()
        var failure: VoiceFailure? = null
        var stopped = false
        override suspend fun speak(text: String): SpeakResult {
            spoken.add(text)
            return failure?.let { SpeakResult.Failure(it) } ?: SpeakResult.Success
        }

        override fun stop() {
            stopped = true
        }
    }

    private class GatedSynthesizer : VoiceSynthesizer {
        val started = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()
        var stopped = false
        override suspend fun speak(text: String): SpeakResult {
            started.complete(Unit)
            gate.await()
            return SpeakResult.Success
        }

        override fun stop() {
            stopped = true
        }

        fun release() {
            gate.complete(Unit)
        }

        suspend fun awaitStarted() = started.await()
    }

    private class RecordingGoalSubmitter(var rejectReason: String? = null) : VoiceGoalSubmitter {
        val transcripts = mutableListOf<String>()
        var calls = 0
        override suspend fun submitGoal(transcript: String): GoalSubmitResult {
            calls++
            transcripts.add(transcript)
            return if (rejectReason != null) {
                GoalSubmitResult.Rejected(rejectReason!!)
            } else {
                GoalSubmitResult.Accepted(
                    GoalRequest(source = GoalSource.VOICE, rawInput = transcript, goal = transcript)
                )
            }
        }
    }

    private class RecordingSink : VoiceResponseSink {
        val asks = mutableListOf<Pair<String, String>>()
        val approvals = mutableListOf<ApprovalRecord>()
        var interruptCount = 0
        override suspend fun respondToAsk(callId: String, response: String) {
            asks.add(callId to response)
        }

        override suspend fun respondToApproval(
            actionId: String,
            decision: ApprovalDecision,
            scope: ApprovalScope,
            packageName: String,
        ) {
            approvals.add(ApprovalRecord(actionId, decision, scope, packageName))
        }

        override suspend fun interruptTask() {
            interruptCount++
        }
    }

    private data class ApprovalRecord(
        val actionId: String,
        val decision: ApprovalDecision,
        val scope: ApprovalScope,
        val packageName: String,
    )

    private fun configuredController(
        mic: FakeMicrophone,
        transcriber: RecordingTranscriber,
        synth: RecordingSynthesizer,
        gateway: RecordingGoalSubmitter,
        sink: RecordingSink,
        plusConfigured: Boolean = true,
    ): Pair<VoiceConversationController, RecordingSink> {
        val controller = VoiceConversationController(
            microphone = mic,
            transcriber = transcriber,
            synthesizer = synth,
            goalSubmitter = gateway,
            responses = sink,
            isConfigured = { plusConfigured },
            onFeedback = {},
        )
        return controller to sink
    }

    private val pendingApproval = PendingApproval(
        actionId = "approval-1",
        packageName = "com.example.app",
        appLabel = "ExampleApp",
        description = "Send a message in ExampleApp",
    )

    private val pendingAsk = PendingAsk(callId = "ask-1", question = "Which contact?")

    // ===== Input: PTT → goal gateway =====

    @Test
    fun `pushes to talk and moves Idle to Listening`() = runTest {
        val (controller, _) = configuredController(myMic(), RecordingTranscriber(), RecordingSynthesizer(), RecordingGoalSubmitter(), RecordingSink())
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.Idle)
        controller.startPushToTalk()
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.Listening)
    }

    @Test
    fun `release submits the final transcript exactly once through the gateway`() = runTest {
        val mic = myMic()
        val transcriber = RecordingTranscriber(TranscribeResult.Success("open whatsapp and message john"))
        val gateway = RecordingGoalSubmitter()
        val (controller, _) = configuredController(mic, transcriber, RecordingSynthesizer(), gateway, RecordingSink())

        controller.startPushToTalk()
        assertThat(controller.stopPushToTalk()).isNull()

        assertThat(gateway.transcripts).containsExactly("open whatsapp and message john")
        assertThat(gateway.calls).isEqualTo(1)
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.WaitingForAgent)
    }

    @Test
    fun `no captured audio rejects without transcribing or submitting`() = runTest {
        val mic = FakeMicrophone(nextAudios = mutableListOf(null))
        val transcriber = RecordingTranscriber()
        val gateway = RecordingGoalSubmitter()
        val (controller, _) = configuredController(mic, transcriber, RecordingSynthesizer(), gateway, RecordingSink())

        controller.startPushToTalk()
        val failure = controller.stopPushToTalk()

        assertThat(failure).isEqualTo(VoiceFailure.TranscriptEmpty)
        assertThat(transcriber.calls).isEqualTo(0)
        assertThat(gateway.calls).isEqualTo(0)
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.Idle)
    }

    @Test
    fun `blank transcript is rejected and never reaches the gateway`() = runTest {
        val transcriber = RecordingTranscriber(TranscribeResult.Success("   "))
        val gateway = RecordingGoalSubmitter()
        val (controller, _) = configuredController(myMic(), transcriber, RecordingSynthesizer(), gateway, RecordingSink())

        controller.startPushToTalk()
        val failure = controller.stopPushToTalk()

        assertThat(failure).isEqualTo(VoiceFailure.TranscriptEmpty)
        assertThat(gateway.calls).isEqualTo(0)
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.Idle)
    }

    @Test
    fun `transcription failure goes to Error and does not submit`() = runTest {
        val transcriber = RecordingTranscriber(TranscribeResult.Failure(VoiceFailure.ProviderUnavailable))
        val gateway = RecordingGoalSubmitter()
        val (controller, _) = configuredController(myMic(), transcriber, RecordingSynthesizer(), gateway, RecordingSink())

        controller.startPushToTalk()
        val failure = controller.stopPushToTalk()

        assertThat(failure).isEqualTo(VoiceFailure.ProviderUnavailable)
        assertThat(gateway.calls).isEqualTo(0)
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.Error)
    }

    @Test
    fun `provider not configured blocks the capture`() = runTest {
        val mic = myMic()
        val (controller, _) = configuredController(mic, RecordingTranscriber(), RecordingSynthesizer(), RecordingGoalSubmitter(), RecordingSink(), plusConfigured = false)

        val failure = controller.startPushToTalk()

        assertThat(failure).isEqualTo(VoiceFailure.ProviderNotConfigured)
        assertThat(mic.startCount).isEqualTo(0)
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.Error)
    }

    @Test
    fun `microphone failure surfaces as Error`() = runTest {
        val mic = FakeMicrophone().apply { failure = VoiceFailure.MicPermissionDenied }
        val (controller, _) = configuredController(mic, RecordingTranscriber(), RecordingSynthesizer(), RecordingGoalSubmitter(), RecordingSink())

        val failure = controller.startPushToTalk()

        assertThat(failure).isEqualTo(VoiceFailure.MicPermissionDenied)
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.Error)
    }

    @Test
    fun `cancelling during listening never submits`() = runTest {
        val mic = myMic()
        val gateway = RecordingGoalSubmitter()
        val (controller, _) = configuredController(mic, RecordingTranscriber(), RecordingSynthesizer(), gateway, RecordingSink())

        controller.startPushToTalk()
        assertThat(controller.cancelPushToTalk()).isTrue()

        assertThat(controller.state.value).isEqualTo(VoiceConversationState.Cancelled)
        assertThat(mic.cancelCount).isEqualTo(1)
        assertThat(gateway.calls).isEqualTo(0)
    }

    @Test
    fun `gateway rejection surfaces feedback and returns to Idle`() = runTest {
        val gateway = RecordingGoalSubmitter("Goal is empty")
        val (controller, _) = configuredController(myMic(), RecordingTranscriber(), RecordingSynthesizer(), gateway, RecordingSink())

        controller.startPushToTalk()
        val failure = controller.stopPushToTalk()

        assertThat(failure).isEqualTo(VoiceFailure.GoalRejected("Goal is empty"))
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.Idle)
    }

    // ===== Confirmation: ask_user =====

    @Test
    fun `pending ask prompt is spoken and answer is delivered verbatim`() = runTest {
        val synth = RecordingSynthesizer()
        val sink = RecordingSink()
        val (controller, _) = configuredController(myMic(), RecordingTranscriber(), synth, RecordingGoalSubmitter(), sink)

        controller.onPendingAskOpen(pendingAsk.callId, pendingAsk.question)

        assertThat(synth.spoken).containsExactly("Which contact?")
        assertThat(controller.hasPendingPrompt).isTrue()
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.WaitingForAgent)

        controller.startPushToTalk()
        controller.stopPushToTalk()

        assertThat(sink.asks).containsExactly(pendingAsk.callId to "transcribed text")
        assertThat(controller.hasPendingPrompt).isFalse()
    }

    @Test
    fun `speaking the ask prompt does not submit a goal`() = runTest {
        val gateway = RecordingGoalSubmitter()
        val (controller, _) = configuredController(myMic(), RecordingTranscriber(), RecordingSynthesizer(), gateway, RecordingSink())

        controller.onPendingAskOpen("ask-2", "Choose one")

        assertThat(gateway.calls).isEqualTo(0)
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.WaitingForAgent)
    }

    // ===== Confirmation: approval =====

    @Test
    fun `pending approval prompt is spoken from pending state`() = runTest {
        val synth = RecordingSynthesizer()
        val (controller, _) = configuredController(myMic(), RecordingTranscriber(), synth, RecordingGoalSubmitter(), RecordingSink())

        controller.onPendingApprovalOpen(pendingApproval)

        assertThat(synth.spoken).containsExactly("Send a message in ExampleApp?")
        assertThat(controller.hasPendingPrompt).isTrue()
        assertThat(controller.state.value).isEqualTo(VoiceConversationState.WaitingForAgent)
    }

    @Test
    fun `voice yes approves only the pending action with SESSION scope`() = runTest {
        val transcriber = RecordingTranscriber(TranscribeResult.Success("yes"))
        val sink = RecordingSink()
        val (controller, _) = configuredController(myMic(), transcriber, RecordingSynthesizer(), RecordingGoalSubmitter(), sink)

        controller.onPendingApprovalOpen(pendingApproval)
        controller.startPushToTalk()
        val failure = controller.stopPushToTalk()

        assertThat(failure).isNull()
        assertThat(sink.approvals).containsExactly(
            ApprovalRecord("approval-1", ApprovalDecision.APPROVED, ApprovalScope.SESSION, "com.example.app")
        )
        assertThat(controller.hasPendingPrompt).isFalse()
    }

    @Test
    fun `voice no denies the pending action`() = runTest {
        val transcriber = RecordingTranscriber(TranscribeResult.Success("no"))
        val sink = RecordingSink()
        val (controller, _) = configuredController(myMic(), transcriber, RecordingSynthesizer(), RecordingGoalSubmitter(), sink)

        controller.onPendingApprovalOpen(pendingApproval)
        controller.startPushToTalk()
        controller.stopPushToTalk()

        assertThat(sink.approvals).containsExactly(
            ApprovalRecord("approval-1", ApprovalDecision.DENIED, ApprovalScope.SESSION, "com.example.app")
        )
    }

    @Test
    fun `non-decision utterance re-asks and never resolves`() = runTest {
        val transcriber = RecordingTranscriber(TranscribeResult.Success("who are you"))
        val synth = RecordingSynthesizer()
        val sink = RecordingSink()
        val (controller, _) = configuredController(myMic(), transcriber, synth, RecordingGoalSubmitter(), sink)

        controller.onPendingApprovalOpen(pendingApproval)
        controller.startPushToTalk()
        controller.stopPushToTalk()

        assertThat(sink.approvals).isEmpty()
        assertThat(synth.spoken).containsExactly(
            "Send a message in ExampleApp?",
            "Send a message in ExampleApp?",
        )
        assertThat(controller.hasPendingPrompt).isTrue()
    }

    @Test
    fun `voice never uses ALWAYS scope`() = runTest {
        val transcriber = RecordingTranscriber(TranscribeResult.Success("approve"))
        val sink = RecordingSink()
        val (controller, _) = configuredController(myMic(), transcriber, RecordingSynthesizer(), RecordingGoalSubmitter(), sink)

        controller.onPendingApprovalOpen(pendingApproval)
        controller.startPushToTalk()
        controller.stopPushToTalk()

        assertThat(sink.approvals).containsExactly(
            ApprovalRecord("approval-1", ApprovalDecision.APPROVED, ApprovalScope.SESSION, "com.example.app")
        )
    }

    @Test
    fun `yes resolves only the currently pending approval`() = runTest {
        val transcriber = RecordingTranscriber(TranscribeResult.Success("yes"))
        val sink = RecordingSink()
        val (controller, _) = configuredController(myMic(), transcriber, RecordingSynthesizer(), RecordingGoalSubmitter(), sink)

        controller.onPendingApprovalOpen(pendingApproval)
        controller.onPendingApprovalOpen(pendingApproval.copy(actionId = "approval-2", packageName = "com.other"))
        controller.startPushToTalk()
        controller.stopPushToTalk()

        assertThat(sink.approvals).containsExactly(
            ApprovalRecord("approval-2", ApprovalDecision.APPROVED, ApprovalScope.SESSION, "com.other")
        )
    }

    // ===== Interruption (distinct from task cancellation) =====

    @Test
    fun `interruptSpeech stops speech without touching the task`() = runTest {
        val synth = GatedSynthesizer()
        val sink = RecordingSink()
        val cockpit = VoiceConversationController(
            microphone = myMic(),
            transcriber = RecordingTranscriber(),
            synthesizer = synth,
            goalSubmitter = RecordingGoalSubmitter(),
            responses = sink,
            isConfigured = { true },
        )

        val speakJob = launch { cockpit.onTaskCompleted(TaskCompletion.ACHIEVED, "Done") }
        synth.awaitStarted()
        assertThat(cockpit.state.value).isEqualTo(VoiceConversationState.Speaking)

        cockpit.interruptSpeech()
        assertThat(synth.stopped).isTrue()
        assertThat(cockpit.state.value).isEqualTo(VoiceConversationState.Interrupted)
        assertThat(sink.interruptCount).isEqualTo(0)

        synth.release()
        speakJob.join()
    }

    @Test
    fun `cancelCurrentTask stops speech and interrupts the session`() = runTest {
        val synth = GatedSynthesizer()
        val sink = RecordingSink()
        val cockpit = VoiceConversationController(
            microphone = myMic(),
            transcriber = RecordingTranscriber(),
            synthesizer = synth,
            goalSubmitter = RecordingGoalSubmitter(),
            responses = sink,
            isConfigured = { true },
        )

        val speakJob = launch { cockpit.onTaskCompleted(TaskCompletion.ACHIEVED, "Done") }
        synth.awaitStarted()

        cockpit.cancelCurrentTask()

        assertThat(sink.interruptCount).isEqualTo(1)
        assertThat(synth.stopped).isTrue()
        assertThat(cockpit.state.value).isEqualTo(VoiceConversationState.Interrupted)

        synth.release()
        speakJob.join()
    }

    // ===== Errors =====

    @Test
    fun `speech failure reports feedback and returns to Idle`() = runTest {
        val synth = RecordingSynthesizer().apply { failure = VoiceFailure.SynthesizeFailed }
        val (controller, _) = configuredController(myMic(), RecordingTranscriber(), synth, RecordingGoalSubmitter(), RecordingSink())

        controller.onTaskCompleted(TaskCompletion.ACHIEVED, "Result")

        assertThat(controller.state.value).isEqualTo(VoiceConversationState.Idle)
    }

    @Test
    fun `barge-in from Speaking starts a fresh capture`() = runTest {
        val synth = GatedSynthesizer()
        val mic = myMic()
        val cockpit = VoiceConversationController(
            microphone = mic,
            transcriber = RecordingTranscriber(),
            synthesizer = synth,
            goalSubmitter = RecordingGoalSubmitter(),
            responses = RecordingSink(),
            isConfigured = { true },
        )

        val speakJob = launch { cockpit.onTaskCompleted(TaskCompletion.ERROR, "Failed") }
        synth.awaitStarted()

        assertThat(cockpit.startPushToTalk()).isNull()
        assertThat(synth.stopped).isTrue()
        assertThat(mic.startCount).isEqualTo(1)
        assertThat(cockpit.state.value).isEqualTo(VoiceConversationState.Listening)

        synth.release()
        speakJob.join()
    }

    private fun myMic(
        audios: MutableList<ByteArray?> = mutableListOf(ByteArray(4)),
    ): FakeMicrophone = FakeMicrophone(nextAudios = audios)
}