package ai.ruach.goal

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GoalGatewayTest {

    @Test
    fun `valid input produces normalized GoalRequest and routes through submitter`() = runTest {
        val submitted = mutableListOf<GoalRequest>()
        val gateway = GoalGateway { submitted += it }

        val result = gateway.submit("  Open   YouTube. ", GoalSource.TEXT)

        assertThat(result).isInstanceOf(GoalSubmitResult.Accepted::class.java)
        val accepted = result as GoalSubmitResult.Accepted
        assertThat(accepted.goalRequest.goal).isEqualTo("Open YouTube.")
        assertThat(accepted.goalRequest.rawInput).isEqualTo("  Open   YouTube. ")
        assertThat(accepted.goalRequest.source).isEqualTo(GoalSource.TEXT)
        assertThat(submitted).hasSize(1)
        assertThat(submitted[0].goal).isEqualTo("Open YouTube.")
    }

    @Test
    fun `voice source is preserved through gateway`() = runTest {
        val gateway = GoalGateway { }
        val result = gateway.submit("Open YouTube", GoalSource.VOICE)
        val accepted = result as GoalSubmitResult.Accepted
        assertThat(accepted.goalRequest.source).isEqualTo(GoalSource.VOICE)
    }

    @Test
    fun `blank input is rejected and submitter is never called`() = runTest {
        var calls = 0
        val gateway = GoalGateway { calls++ }

        val result = gateway.submit("   ", GoalSource.TEXT)

        assertThat(result).isInstanceOf(GoalSubmitResult.Rejected::class.java)
        assertThat((result as GoalSubmitResult.Rejected).reason).isNotEmpty()
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `input exceeding max length is rejected`() = runTest {
        val gateway = GoalGateway {}
        val longInput = "a".repeat(GoalNormalizer.MAX_GOAL_LENGTH + 1)

        val result = gateway.submit(longInput, GoalSource.TEXT)

        assertThat(result).isInstanceOf(GoalSubmitResult.Rejected::class.java)
    }

    @Test
    fun `whitespace and tab characters are collapsed to single spaces`() = runTest {
        val gateway = GoalGateway {}

        val result = gateway.submit("Open\n\tYouTube.", GoalSource.TEXT)
        val accepted = result as GoalSubmitResult.Accepted

        assertThat(accepted.goalRequest.goal).isEqualTo("Open YouTube.")
        assertThat(accepted.goalRequest.rawInput).isEqualTo("Open\n\tYouTube.")
    }

    @Test
    fun `empty string is rejected`() = runTest {
        val gateway = GoalGateway {}
        val result = gateway.submit("", GoalSource.UI)
        assertThat(result).isInstanceOf(GoalSubmitResult.Rejected::class.java)
    }

    @Test
    fun `goal is trimmed before normalization`() = runTest {
        val gateway = GoalGateway {}
        val result = gateway.submit("\n  Send message  \n", GoalSource.VOICE)
        val accepted = result as GoalSubmitResult.Accepted
        assertThat(accepted.goalRequest.goal).isEqualTo("Send message")
    }
}