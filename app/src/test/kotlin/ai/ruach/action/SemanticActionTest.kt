package ai.ruach.action

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SemanticActionTest {

    // ── M1 scope: OPEN_APP / YouTube ───────────────────────────────────────

    @Test
    fun `OPEN_APP represents YouTube as a semantic action`() {
        val action = SemanticAction.OpenApp(target = "YouTube")
        assertThat(action.type).isEqualTo(ActionType.OPEN_APP)
        assertThat(action.target).isEqualTo("YouTube")
    }

    @Test
    fun `M1 action has LOW risk`() {
        val action = SemanticAction.OpenApp("YouTube")
        assertThat(action.risk).isEqualTo(ActionRisk.LOW)
    }

    @Test
    fun `M1 action does not require confirmation`() {
        val action = SemanticAction.OpenApp("YouTube")
        assertThat(action.requiresConfirmation).isFalse()
    }

    // ── Validation ─────────────────────────────────────────────────────────

    @Test
    fun `valid OPEN_APP passes validation`() {
        val action = SemanticAction.OpenApp("YouTube")
        assertThat(SemanticActionValidator.validate(action)).isEqualTo(ActionValidation.Valid(action))
    }

    @Test
    fun `blank target is rejected`() {
        val validation = SemanticActionValidator.validate(SemanticAction.OpenApp(""))
        assertThat(validation).isInstanceOf(ActionValidation.Invalid::class.java)
        assertThat((validation as ActionValidation.Invalid).errors)
            .contains(ActionValidationError.BLANK_TARGET)
    }

    @Test
    fun `whitespace-only target is rejected`() {
        val validation = SemanticActionValidator.validate(SemanticAction.OpenApp("   "))
        assertThat(validation).isInstanceOf(ActionValidation.Invalid::class.java)
        assertThat((validation as ActionValidation.Invalid).errors)
            .contains(ActionValidationError.BLANK_TARGET)
    }

    @Test
    fun `HIGH risk without confirmation is inconsistent and rejected`() {
        val action = SemanticAction.OpenApp("YouTube", risk = ActionRisk.HIGH)
        val validation = SemanticActionValidator.validate(action)
        assertThat(validation).isInstanceOf(ActionValidation.Invalid::class.java)
        assertThat((validation as ActionValidation.Invalid).errors)
            .contains(ActionValidationError.HIGH_RISK_REQUIRES_CONFIRMATION)
    }

    @Test
    fun `HIGH risk with confirmation is consistent and accepted`() {
        val action = SemanticAction.OpenApp(
            target = "YouTube",
            risk = ActionRisk.HIGH,
            requiresConfirmation = true,
        )
        assertThat(SemanticActionValidator.validate(action)).isEqualTo(ActionValidation.Valid(action))
    }

    @Test
    fun `CRITICAL risk without confirmation is rejected`() {
        val action = SemanticAction.OpenApp("YouTube", risk = ActionRisk.CRITICAL)
        assertThat(SemanticActionValidator.validate(action))
            .isInstanceOf(ActionValidation.Invalid::class.java)
    }

    // ── Layer boundaries ───────────────────────────────────────────────────

    @Test
    fun `semantic action contains no android-specific dependency`() {
        val actionClasses = listOf(
            SemanticAction::class.java,
            SemanticAction.OpenApp::class.java,
            ActionRisk::class.java,
            ActionType::class.java,
            SemanticActionValidator::class.java,
        )

        val referencedTypeNames = mutableListOf<String>()
        for (clazz in actionClasses) {
            clazz.declaredFields.forEach { referencedTypeNames += it.type.name }
            clazz.declaredMethods.forEach { method ->
                referencedTypeNames += method.returnType.name
                method.parameterTypes.forEach { referencedTypeNames += it.name }
            }
        }

        assertThat(referencedTypeNames).isNotEmpty()
        assertThat(referencedTypeNames.filter { it.startsWith("android.") }).isEmpty()
    }

    @Test
    fun `model is single-point extensible without execution infrastructure`() {
        // M1 scope: exactly one action type and one semantic action, both
        // defined in this package. Adding SEARCH/READ/... later = add one
        // enum value + one sealed [SemanticAction] subtype here — nothing in
        // ToolRouter/Executor/AndroidPlatform changes.
        assertThat(ActionType.entries).containsExactly(ActionType.OPEN_APP).inOrder()
        assertThat(SemanticAction::class.java.packageName).isEqualTo("ai.ruach.action")
        assertThat(SemanticAction.OpenApp::class.java.packageName).isEqualTo("ai.ruach.action")

        // The validator is exhaustive over the sealed hierarchy (see [when]),
        // so any future subtype is forced to be consciously validated.
        assertThat(SemanticActionValidator.validate(SemanticAction.OpenApp("YouTube")))
            .isEqualTo(ActionValidation.Valid(SemanticAction.OpenApp("YouTube")))
    }
}