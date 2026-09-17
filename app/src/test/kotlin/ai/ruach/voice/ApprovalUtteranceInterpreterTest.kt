package ai.ruach.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApprovalUtteranceInterpreterTest {

    private val interpreter = DefaultApprovalUtteranceInterpreter

    private val approvals = listOf(
        "yes", "yeah", "yep", "yup", "ok", "okay", "sure", "go",
        "approve", "confirmed", "confirm", "allow", "affirmative",
    )

    private val denials = listOf(
        "no", "nope", "nah", "deny", "reject", "decline",
        "cancel", "stop", "hang on", "never mind", "don't",
    )

    @Test
    fun `recognizes each approval token`() {
        approvals.forEach { token ->
            assertThat(interpreter.interpret(token)).isEqualTo(ApprovalIntent.APPROVE)
        }
    }

    @Test
    fun `recognizes each denial token`() {
        denials.forEach { token ->
            assertThat(interpreter.interpret(token)).isEqualTo(ApprovalIntent.DENY)
        }
    }

    @Test
    fun `leading decision token wins with trailing words`() {
        assertThat(interpreter.interpret("yes go ahead and send it")).isEqualTo(ApprovalIntent.APPROVE)
        assertThat(interpreter.interpret("no thank you")).isEqualTo(ApprovalIntent.DENY)
    }

    @Test
    fun `does not match a decision word inside a sentence`() {
        assertThat(interpreter.interpret("is that okay though")).isEqualTo(ApprovalIntent.OTHER)
        assertThat(interpreter.interpret("please stop hitting me")).isEqualTo(ApprovalIntent.OTHER)
    }

    @Test
    fun `case and punctuation are normalized`() {
        assertThat(interpreter.interpret("YES.")).isEqualTo(ApprovalIntent.APPROVE)
        assertThat(interpreter.interpret(" nope! ")).isEqualTo(ApprovalIntent.DENY)
    }

    @Test
    fun `anything else is not a decision`() {
        assertThat(interpreter.interpret("what time is it")).isEqualTo(ApprovalIntent.OTHER)
        assertThat(interpreter.interpret("maybe")).isEqualTo(ApprovalIntent.OTHER)
        assertThat(interpreter.interpret("")).isEqualTo(ApprovalIntent.OTHER)
        assertThat(interpreter.interpret("   ")).isEqualTo(ApprovalIntent.OTHER)
    }
}