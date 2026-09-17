package ai.ruach.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceResponseFormatterTest {

    @Test
    fun `achieved with result speaks the result verbatim`() {
        assertThat(VoiceResponseFormatter.taskCompletion(TaskCompletion.ACHIEVED, "Report saved to /tmp/report.pdf"))
            .isEqualTo("Report saved to /tmp/report.pdf")
    }

    @Test
    fun `achieved without result speaks a neutral completion`() {
        assertThat(VoiceResponseFormatter.taskCompletion(TaskCompletion.ACHIEVED, null))
            .isEqualTo("I finished the task.")
    }

    @Test
    fun `user stopped speaks a stop outcome`() {
        assertThat(VoiceResponseFormatter.taskCompletion(TaskCompletion.USER_STOPPED, null))
            .isEqualTo("Stopped.")
    }

    @Test
    fun `impossible task reports the agent reason when present`() {
        assertThat(VoiceResponseFormatter.taskCompletion(TaskCompletion.IMPOSSIBLE, "Deleting system files is not allowed"))
            .isEqualTo("Deleting system files is not allowed")
    }

    @Test
    fun `impossible task falls back to a factual statement`() {
        assertThat(VoiceResponseFormatter.taskCompletion(TaskCompletion.IMPOSSIBLE, null))
            .isEqualTo("I couldn't complete this task.")
    }

    @Test
    fun `error never claims success`() {
        assertThat(VoiceResponseFormatter.taskCompletion(TaskCompletion.ERROR, null))
            .isEqualTo("The task failed.")
        assertThat(VoiceResponseFormatter.taskCompletion(TaskCompletion.ERROR, "Network unreachable"))
            .isEqualTo("Network unreachable")
    }

    @Test
    fun `session error speaks the message or a fallback`() {
        assertThat(VoiceResponseFormatter.sessionError("The session ran out"))
            .isEqualTo("The session ran out")
        assertThat(VoiceResponseFormatter.sessionError("   "))
            .isEqualTo("Something went wrong.")
    }

    @Test
    fun `approval prompt uses the pending description when present`() {
        assertThat(
            VoiceResponseFormatter.approvalPrompt(
                appLabel = "WhatsApp",
                description = "Send a message in WhatsApp",
            )
        ).isEqualTo("Send a message in WhatsApp?")
    }

    @Test
    fun `approval prompt falls back to the app label`() {
        assertThat(
            VoiceResponseFormatter.approvalPrompt(
                appLabel = "Telegram",
                description = "   ",
            )
        ).isEqualTo("Approve Telegram?")
    }

    @Test
    fun `ask prompt is the verbatim agent question`() {
        assertThat(VoiceResponseFormatter.askPrompt("Which folder?")).isEqualTo("Which folder?")
    }
}