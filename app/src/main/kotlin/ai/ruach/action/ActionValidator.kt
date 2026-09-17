package ai.ruach.action

/**
 * Deterministic, type-level validation of semantic actions
 * (03_ACTION_MODEL.md §13 VALIDATED step).
 *
 * Validation is pure: it never inspects Android state and never evaluates
 * policy. Policy evaluation happens downstream in the reused ClosePaw
 * PolicyEngine. The exhaustive [when] ensures a future [SemanticAction]
 * subtype is consciously handled here before it can be used.
 */
object SemanticActionValidator {

    fun validate(action: SemanticAction): ActionValidation = when (action) {
        is SemanticAction.OpenApp -> validateOpenApp(action)
    }

    private fun validateOpenApp(action: SemanticAction.OpenApp): ActionValidation {
        val errors = mutableListOf<ActionValidationError>()
        if (action.target.isBlank()) {
            errors += ActionValidationError.BLANK_TARGET
        }
        if ((action.risk == ActionRisk.HIGH || action.risk == ActionRisk.CRITICAL)
            && !action.requiresConfirmation
        ) {
            errors += ActionValidationError.HIGH_RISK_REQUIRES_CONFIRMATION
        }
        return if (errors.isEmpty()) {
            ActionValidation.Valid(action)
        } else {
            ActionValidation.Invalid(errors)
        }
    }
}

sealed interface ActionValidation {
    data class Valid(val action: SemanticAction) : ActionValidation
    data class Invalid(val errors: List<ActionValidationError>) : ActionValidation
}

enum class ActionValidationError(val message: String) {
    BLANK_TARGET("OPEN_APP requires a non-blank target"),
    HIGH_RISK_REQUIRES_CONFIRMATION("HIGH and CRITICAL risk actions require confirmation"),
}