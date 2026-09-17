package ai.ruach.integration

import ai.closepaw.agent.ToolCallRequest
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.DisplayInfo
import ai.closepaw.platform.UIAction
import ai.closepaw.tool.ToolCallResult
import ai.closepaw.trace.TraceArtifactRef
import ai.closepaw.trace.TraceEventRecord
import ai.closepaw.trace.TraceRecorder
import ai.ruach.trace.ActionTrace
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Observer wiring (M1 Step 5): the existing LLM's selected `open_app` tool calls
 * become RUACH semantic product-stage events on the session trace, and the
 * completion is verified against the existing AndroidPlatform state.
 */
class RuachExecutionObserverTest {

    private fun TraceEventRecord.jsonField(key: String): String? =
        (data as? JsonObject)?.get(key)?.jsonPrimitive?.content

    private fun openAppCall(target: String) =
        ToolCallRequest("call-1", "open_app", JSONObject().put("app_name", target))

    // ── action_created ────────────────────────────────────────────────────────

    @Test
    fun `open_app tool call emits action_created with derived action fields`() = runTest {
        val recorder = RecordingTraceRecorder()
        val observer = RuachExecutionObserver(ActionTrace("session-1", recorder))

        observer.onActionCreated("t-1", 1, openAppCall("YouTube"))

        assertThat(recorder.recorded).hasSize(1)
        val event = recorder.recorded.first()
        assertThat(event.type).isEqualTo("action_created")
        assertThat(event.turnId).isEqualTo("t-1")
        assertThat(event.turnNumber).isEqualTo(1)
        assertThat(event.jsonField("action_type")).isEqualTo("open_app")
        assertThat(event.jsonField("target")).isEqualTo("YouTube")
        assertThat(event.jsonField("risk")).isEqualTo("low")
        assertThat(event.jsonField("requires_confirmation")).isEqualTo("false")
    }

    @Test
    fun `non-open-app tool call emits nothing`() = runTest {
        val recorder = RecordingTraceRecorder()
        val observer = RuachExecutionObserver(ActionTrace("session-1", recorder))

        observer.onActionCreated("t-1", 1, ToolCallRequest("call-1", "other_tool", JSONObject()))

        assertThat(recorder.recorded).isEmpty()
    }

    @Test
    fun `open_app with missing app_name emits nothing`() = runTest {
        val recorder = RecordingTraceRecorder()
        val observer = RuachExecutionObserver(ActionTrace("session-1", recorder))

        observer.onActionCreated(
            "t-1", 1, ToolCallRequest("call-1", "open_app", JSONObject())
        )

        assertThat(recorder.recorded).isEmpty()
    }

    // ── action_completed: success + verification ─────────────────────────────

    @Test
    fun `successful open_app verified when current package matches alias resolution`() = runTest {
        val recorder = RecordingTraceRecorder()
        val observer = RuachExecutionObserver(ActionTrace("session-1", recorder))
        // "youtube" alias resolves via AppAliases.PACKAGE_MAP → com.google.android.youtube
        val platform = FakePlatform(currentPackage = "com.google.android.youtube")

        observer.onActionCreated("t-1", 1, openAppCall("youtube"))
        observer.onActionCompleted(
            "t-1", 1, openAppCall("youtube"),
            ToolCallResult.Success(callId = "call-1", output = "YouTube launched"),
            platform,
        )

        assertThat(recorder.recorded.map { it.type }).containsExactly(
            "action_created",
            "action_completed",
        ).inOrder()
        val completed = recorder.recorded.last()
        assertThat(completed.jsonField("action_type")).isEqualTo("open_app")
        assertThat(completed.jsonField("status")).isEqualTo("completed")
        assertThat(completed.jsonField("verified")).isEqualTo("true")
        assertThat(completed.jsonField("summary")).isEqualTo("YouTube launched")
    }

    @Test
    fun `successful open_app verified via installed label match`() = runTest {
        val recorder = RecordingTraceRecorder()
        val observer = RuachExecutionObserver(ActionTrace("session-1", recorder))
        // "Figma" is not a well-known alias, so resolution must go through the
        // installed-app label lookup (mirrors the existing OpenApp resolution order).
        val platform = FakePlatform(
            currentPackage = "com.foo.figma",
            installedAppsValue = listOf(AppInfo(packageName = "com.foo.figma", label = "Figma")),
        )

        observer.onActionCompleted(
            "t-1", 1, openAppCall("Figma"),
            ToolCallResult.Success(callId = "call-1", output = "opened"),
            platform,
        )

        assertThat(recorder.recorded.single().jsonField("verified")).isEqualTo("true")
    }

    @Test
    fun `successful open_app not verified when foreground package differs`() = runTest {
        val recorder = RecordingTraceRecorder()
        val observer = RuachExecutionObserver(ActionTrace("session-1", recorder))
        val platform = FakePlatform(currentPackage = "com.something.else")

        observer.onActionCompleted(
            "t-1", 1, openAppCall("youtube"),
            ToolCallResult.Success(callId = "call-1", output = "opened"),
            platform,
        )

        val completed = recorder.recorded.single()
        assertThat(completed.jsonField("status")).isEqualTo("completed")
        assertThat(completed.jsonField("verified")).isEqualTo("false")
    }

    // ── action_completed: failure paths ──────────────────────────────────────

    @Test
    fun `error result maps to FAILED with error summary`() = runTest {
        val recorder = RecordingTraceRecorder()
        val observer = RuachExecutionObserver(ActionTrace("session-1", recorder))
        val platform = FakePlatform(currentPackage = "com.something.else")

        observer.onActionCompleted(
            "t-1", 1, openAppCall("youtube"),
            ToolCallResult.Error(callId = "call-1", error = "launch failed"),
            platform,
        )

        val completed = recorder.recorded.single()
        assertThat(completed.jsonField("action_type")).isEqualTo("open_app")
        assertThat(completed.jsonField("status")).isEqualTo("failed")
        assertThat(completed.jsonField("verified")).isEqualTo("false")
        assertThat(completed.jsonField("summary")).isEqualTo("launch failed")
    }

    @Test
    fun `cancelled result maps to CANCELLED and is never verified`() = runTest {
        val recorder = RecordingTraceRecorder()
        val observer = RuachExecutionObserver(ActionTrace("session-1", recorder))
        val platform = FakePlatform(currentPackage = "com.google.android.youtube")

        observer.onActionCompleted(
            "t-1", 1, openAppCall("youtube"),
            ToolCallResult.Cancelled(callId = "call-1", reason = "Policy denied"),
            platform,
        )

        val completed = recorder.recorded.single()
        assertThat(completed.jsonField("status")).isEqualTo("cancelled")
        assertThat(completed.jsonField("verified")).isEqualTo("false")
        assertThat(completed.jsonField("summary")).isEqualTo("Policy denied")
    }

    @Test
    fun `non-open-app completion emits nothing`() = runTest {
        val recorder = RecordingTraceRecorder()
        val observer = RuachExecutionObserver(ActionTrace("session-1", recorder))
        val platform = FakePlatform(currentPackage = "com.something.else")

        observer.onActionCompleted(
            "t-1", 1, ToolCallRequest("call-1", "other_tool", JSONObject()),
            ToolCallResult.Success(callId = "call-1", output = "ok"),
            platform,
        )

        assertThat(recorder.recorded).isEmpty()
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    private class FakePlatform(
        var currentPackage: String?,
        private val installedAppsValue: List<AppInfo> = emptyList(),
    ) : AndroidPlatform {
        override val mode: ai.closepaw.protocol.PlatformMode =
            ai.closepaw.protocol.PlatformMode.ACCESSIBILITY

        override suspend fun captureScreen(): ai.closepaw.model.ScreenSnapshot =
            ai.closepaw.model.ScreenSnapshot(timestamp = 0L, elements = emptyList())

        override suspend fun performAction(action: UIAction): ActionResult = ActionResult.Success()

        override fun hasRequiredPermissions(): Boolean = true

        override fun getCurrentPackageName(): String? = currentPackage

        override fun getDisplayInfo(): DisplayInfo =
            DisplayInfo(widthPixels = 1080, heightPixels = 1920, density = 2f)

        override suspend fun getInstalledApps(): List<AppInfo> = installedAppsValue

        override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
    }

    private class RecordingTraceRecorder : TraceRecorder {
        override val enabled: Boolean = true
        override val runId: String? = "test-run"
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