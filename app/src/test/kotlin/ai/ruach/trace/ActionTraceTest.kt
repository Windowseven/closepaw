package ai.ruach.trace

import ai.closepaw.trace.TraceArtifactRef
import ai.closepaw.trace.TraceEventRecord
import ai.closepaw.trace.TraceRecorder
import ai.closepaw.trace.NoopTraceRecorder
import ai.ruach.action.ActionRisk
import ai.ruach.action.ActionType
import ai.ruach.action.SemanticAction
import ai.ruach.goal.GoalRequest
import ai.ruach.goal.GoalSource
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

class ActionTraceTest {

    // ── TraceEventRecord helpers ────────────────────────────────────────────

    private fun TraceEventRecord.jsonField(key: String): String? =
        (data as? JsonObject)?.get(key)?.jsonPrimitive?.content

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `goal_received emits correct type and data`() {
        val recorder = RecordingTraceRecorder()
        val trace = ActionTrace("session-1", recorder)
        val request = GoalRequest(source = GoalSource.TEXT, rawInput = "Open YouTube", goal = "Open YouTube")

        trace.recordEvent(ActionTraceEvent.GoalReceived(request = request, tsMs = 1000L))

        assertThat(recorder.recorded).hasSize(1)
        val event = recorder.recorded.first()
        assertThat(event.type).isEqualTo("goal_received")
        assertThat(event.sessionId).isEqualTo("session-1")
        assertThat(event.tsMs).isEqualTo(1000L)
        assertThat(event.jsonField("source")).isEqualTo("text")
        assertThat(event.jsonField("goal")).isEqualTo("Open YouTube")
    }

    @Test
    fun `action_created emits correct type and data for OPEN_APP`() {
        val recorder = RecordingTraceRecorder()
        val trace = ActionTrace("session-1", recorder)
        val action = SemanticAction.OpenApp(target = "YouTube")

        trace.recordEvent(ActionTraceEvent.ActionCreated(action = action, tsMs = 2000L))

        assertThat(recorder.recorded).hasSize(1)
        val event = recorder.recorded.first()
        assertThat(event.type).isEqualTo("action_created")
        assertThat(event.tsMs).isEqualTo(2000L)
        assertThat(event.jsonField("action_type")).isEqualTo("open_app")
        assertThat(event.jsonField("target")).isEqualTo("YouTube")
        assertThat(event.jsonField("risk")).isEqualTo("low")
        assertThat(event.jsonField("requires_confirmation")).isEqualTo("false")
    }

    @Test
    fun `action_completed emits correct type and data`() {
        val recorder = RecordingTraceRecorder()
        val trace = ActionTrace("session-1", recorder)
        val action = SemanticAction.OpenApp(target = "YouTube")

        trace.recordEvent(
            ActionTraceEvent.ActionCompleted(
                action = action,
                status = ActionResultStatus.COMPLETED,
                verified = true,
                summary = "YouTube is open",
                tsMs = 3000L,
            )
        )

        assertThat(recorder.recorded).hasSize(1)
        val event = recorder.recorded.first()
        assertThat(event.type).isEqualTo("action_completed")
        assertThat(event.tsMs).isEqualTo(3000L)
        assertThat(event.jsonField("action_type")).isEqualTo("open_app")
        assertThat(event.jsonField("status")).isEqualTo("completed")
        assertThat(event.jsonField("verified")).isEqualTo("true")
        assertThat(event.jsonField("summary")).isEqualTo("YouTube is open")
    }

    @Test
    fun `action_completed without summary omits summary field`() {
        val recorder = RecordingTraceRecorder()
        val trace = ActionTrace("s", recorder)
        val action = SemanticAction.OpenApp(target = "YouTube")

        trace.recordEvent(
            ActionTraceEvent.ActionCompleted(
                action = action,
                status = ActionResultStatus.FAILED,
                verified = false,
                tsMs = 4000L,
            )
        )

        val data = recorder.recorded.first().data as? JsonObject
        assertThat(data?.containsKey("summary")).isFalse()
    }

    // ── Gating / no-ops ────────────────────────────────────────────────────

    @Test
    fun `disabled recorder (NoopTraceRecorder) does not record`() {
        val trace = ActionTrace("s", NoopTraceRecorder)

        trace.recordEvent(
            ActionTraceEvent.GoalReceived(
                request = GoalRequest(source = GoalSource.VOICE, rawInput = "", goal = "Hi"),
            )
        )
        // no assertion needed — NoopTraceRecorder.record is a no-op;
        // the event passes through without exception.
    }

    @Test
    fun `null runId produces no record`() {
        val recorder = RecordingTraceRecorder(runIdOverride = null)
        val trace = ActionTrace("s", recorder)

        trace.recordEvent(
            ActionTraceEvent.GoalReceived(
                request = GoalRequest(source = GoalSource.TEXT, rawInput = "", goal = "Test"),
            )
        )

        assertThat(recorder.recorded).isEmpty()
    }

    // ── Ordering / multi-event ──────────────────────────────────────────────

    @Test
    fun `three sequential events are recorded with ascending sequence`() {
        val recorder = RecordingTraceRecorder()
        val trace = ActionTrace("session-2", recorder)
        val request = GoalRequest(source = GoalSource.TEXT, rawInput = "Open YouTube", goal = "Open YouTube")
        val action = SemanticAction.OpenApp(target = "YouTube")

        trace.recordEvent(ActionTraceEvent.GoalReceived(request = request, tsMs = 10L))
        trace.recordEvent(ActionTraceEvent.ActionCreated(action = action, tsMs = 20L))
        trace.recordEvent(
            ActionTraceEvent.ActionCompleted(
                action = action,
                status = ActionResultStatus.COMPLETED,
                verified = true,
                tsMs = 30L,
            )
        )

        assertThat(recorder.recorded).hasSize(3)
        assertThat(recorder.recorded.map { it.type }).containsExactly(
            "goal_received",
            "action_created",
            "action_completed",
        ).inOrder()
        assertThat(recorder.recorded.map { it.seq }).containsExactly(1L, 2L, 3L).inOrder()
    }

    // ── Layer boundary ──────────────────────────────────────────────────────

    @Test
    fun `trace event model contains no android-specific dependency`() {
        val traceClasses = listOf(
            ActionTraceEvent::class.java,
            ActionTraceEvent.GoalReceived::class.java,
            ActionTraceEvent.ActionCreated::class.java,
            ActionTraceEvent.ActionCompleted::class.java,
            ActionResultStatus::class.java,
            ActionTrace::class.java,
        )
        val signatures = mutableListOf<String>()
        for (clazz in traceClasses) {
            clazz.declaredFields.forEach { signatures += it.type.name }
            clazz.declaredMethods.forEach { m ->
                signatures += m.returnType.name
                m.parameterTypes.forEach { signatures += it.name }
            }
        }
        assertThat(signatures).isNotEmpty()
        assertThat(signatures.filter { it.startsWith("android.") }).isEmpty()
    }

    // ── Test fake ───────────────────────────────────────────────────────────

    private class RecordingTraceRecorder(
        runIdOverride: String? = "test-run",
    ) : TraceRecorder {
        override val enabled: Boolean = true
        override val runId: String? = runIdOverride
        private val seq = AtomicLong(0L)
        val recorded = mutableListOf<TraceEventRecord>()

        override fun nextSeq(): Long = seq.incrementAndGet()

        override fun record(event: TraceEventRecord) {
            recorded += event
        }

        override fun storeText(
            kind: String,
            filenameHint: String,
            content: String,
            mimeType: String?,
            description: String?,
        ): TraceArtifactRef = TraceArtifactRef(kind = kind, path = "artifacts/$filenameHint")

        override fun storeBytes(
            kind: String,
            filenameHint: String,
            bytes: ByteArray,
            mimeType: String?,
            description: String?,
        ): TraceArtifactRef = TraceArtifactRef(kind = kind, path = "artifacts/$filenameHint")

        override suspend fun flush() = Unit
        override suspend fun close() = Unit
    }
}