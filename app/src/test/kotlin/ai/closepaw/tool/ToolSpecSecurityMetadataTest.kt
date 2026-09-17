package ai.closepaw.tool

import ai.closepaw.tool.impl.OpenAppTool
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * Tests that the tool contract carries RUACH security metadata correctly
 * (M1 Step 3: ToolSpec risk metadata) without changing existing behavior.
 */
class ToolSpecSecurityMetadataTest {

    // ── Test 1–2: android.open_app explicitly declares LOW / no-confirmation ──

    @Test
    fun `open_app exposes riskLevel LOW`() {
        assertThat(OpenAppTool().riskLevel).isEqualTo(ai.ruach.action.ActionRisk.LOW)
    }

    @Test
    fun `open_app exposes requiresConfirmation false`() {
        assertThat(OpenAppTool().requiresConfirmation).isFalse()
    }

    // ── Test 3: backwards-compatible defaults ──────────────────────────────

    @Test
    fun `existing tool implementation without overrides defaults to LOW and no confirmation`() {
        // A legacy ToolSpec that does not touch riskLevel/requiresConfirmation
        // must compile and still return safe defaults.
        val legacyTool = object : ToolSpec {
            override val name = "legacy_tool"
            override val description = "old tool"
            override val parameterSchema = JSONObject()
            override fun validate(params: JSONObject) = ValidationResult.Valid
            override fun createInvocation(params: JSONObject) = error("not implemented")
        }

        assertThat(legacyTool.riskLevel).isEqualTo(ai.ruach.action.ActionRisk.LOW)
        assertThat(legacyTool.requiresConfirmation).isFalse()
    }

    // ── Test 4: metadata survives registry / lookup round-trip ─────────────

    @Test
    fun `metadata survives registry lookup`() {
        val registry = ToolRegistry()
        registry.register(OpenAppTool())

        val lookedUp = registry.get("open_app")!!
        assertThat(lookedUp.riskLevel).isEqualTo(ai.ruach.action.ActionRisk.LOW)
        assertThat(lookedUp.requiresConfirmation).isFalse()
    }

    // ── Test 5: metadata is retrievable via getAll / filtered copy ──────────

    @Test
    fun `metadata survives ToolRegistry getAll and createFilteredCopy`() {
        val registry = ToolRegistry()
        registry.register(OpenAppTool())

        assertThat(registry.getAll().first().riskLevel)
            .isEqualTo(ai.ruach.action.ActionRisk.LOW)

        val filtered = registry.createFilteredCopy(allowedNames = setOf("open_app"))
        assertThat(filtered.get("open_app")!!.riskLevel)
            .isEqualTo(ai.ruach.action.ActionRisk.LOW)
        assertThat(filtered.get("open_app")!!.requiresConfirmation).isFalse()
    }

    // ── Test 6: LLM schema generation is unaffected ────────────────────────

    @Test
    fun `generateResponsesApiTools produces valid schema for open_app`() {
        val registry = ToolRegistry()
        registry.register(OpenAppTool())

        val responsesTools = registry.generateResponsesApiTools()
        assertThat(responsesTools).hasSize(1)
        val tool = responsesTools.first()
        assertThat(tool.name()).isEqualTo("open_app")
    }
}
