package analyzer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidatorTest {
    private val fixture = javaClass.getResource("/valid-diagnosis-INC-201.json")!!.readText()

    private fun answer(edit: ObjectNode.() -> Unit = {}): String =
        (Json.mapper.readTree(fixture) as ObjectNode).apply(edit).toString()

    private fun check(text: String, id: String = "INC-201") =
        Validator.check(text, SignalExtractor.extract(LogParser.parse(example(id))), id)

    private fun ObjectNode.summary() = this["summary"] as ObjectNode
    private fun ObjectNode.hypothesis() = (this["hypotheses"] as ArrayNode)[0] as ObjectNode
    private fun ObjectNode.step(j: Int) = (hypothesis()["next_steps"] as ArrayNode)[j] as ObjectNode

    private fun assertSingleError(result: ValidationResult, vararg fragments: String) {
        val error = result.errors.singleOrNull() ?: error("expected one error, got ${result.errors}")
        fragments.forEach { assertTrue(it in error, "'$it' not in: $error") }
        assertNull(result.diagnosis)
    }

    @Test
    fun `valid answer passes, also inside fences or prose`() {
        for (text in listOf(answer(), "Here it is:\n```json\n${answer()}\n```", "Sure! ${answer()} Done.")) {
            val result = check(text)
            assertEquals(emptyList(), result.errors)
            assertEquals("cc109 (OPay)", result.diagnosis?.summary?.affectedAdapters?.single())
        }
    }

    @Test
    fun `truncated JSON is a parse error`() {
        assertSingleError(check("""{"category": "External provider degradation", "summary": {"""), "not a valid JSON object")
    }

    @Test
    fun `trivial deviations are normalized without errors`() {
        val result = check(answer {
            put("incident_id", "INC-999")
            put("category", "external provider DEGRADATION")
            summary().put("fault_layer", "external").put("blast_radius", "single-adapter").put("severity", "High")
            val hypotheses = this["hypotheses"] as ArrayNode
            repeat(4) { hypotheses.add(hypothesis().deepCopy() as JsonNode) }
        })
        val diagnosis = assertNotNull(result.diagnosis)
        assertEquals("INC-201", diagnosis.incidentId)
        assertEquals("External provider degradation", diagnosis.category)
        assertEquals("External", diagnosis.summary.faultLayer)
        assertEquals("single_adapter", diagnosis.summary.blastRadius)
        assertEquals("high", diagnosis.summary.severity)
        assertEquals(3, diagnosis.hypotheses.size)
    }

    @Test
    fun `schema violations name the field`() {
        val cases = mapOf<String, ObjectNode.() -> Unit>(
            "summary.severity: 'urgent' is not allowed; expected one of low, medium, high, critical" to { summary().put("severity", "urgent") },
            "summary.blast_radius: missing or has the wrong type" to { summary().remove("blast_radius") },
            "summary.affected_adapters: missing or has the wrong type" to { summary().put("affected_adapters", "cc109") },
            "hypotheses[0].next_steps: expected at least 2 item(s), got 1" to { (hypothesis()["next_steps"] as ArrayNode).remove(1) },
        )
        for ((expected, edit) in cases) assertSingleError(check(answer(edit)), expected)
    }

    @Test
    fun `invented adapter is rejected`() {
        assertSingleError(check(answer { summary().putArray("affected_adapters").add("cc999 (StripeX)") }), "cc999", "not present in the system or the logs")
    }

    @Test
    fun `tools must be allowed or mentioned in the log`() {
        for (tool in listOf("Datadog", "Provider status page")) {
            assertSingleError(check(answer { step(0).put("tool", tool) }), "'$tool' is not an allowed tool")
        }
        val mongoStep = check(answer {
            put("category", "Infrastructure failure (DB/cache/queue)")
            summary().put("fault_layer", "Infrastructure").putArray("affected_adapters").add("cc139 (Halopesa)")
            step(0).put("tool", "MongoDB")
            hypothesis().put("title", "MongoDB unavailable")
        }, "INC-203")
        assertEquals(emptyList(), mongoStep.errors)
    }

    @Test
    fun `External fault layer without provider-side errors is rejected`() {
        val result = check(answer { summary().putArray("affected_adapters").add("cc087 (PayMe)") }, "INC-202")
        assertSingleError(result, "summary.fault_layer", "no provider-side error")
    }

    @Test
    fun `likely hypothesis blaming an unmentioned component is rejected`() {
        assertSingleError(check(answer { hypothesis().put("title", "Stale Redis cache") }), "hypotheses[0]", "Redis")
        assertEquals(emptyList(), check(answer { hypothesis().put("title", "Stale Redis cache").put("probability", "possible") }).errors)
    }

    @Test
    fun `single adapter blast radius with two adapters in the log is rejected`() {
        val result = check(answer {
            put("category", "Routing / terminal configuration issue")
            summary().put("fault_layer", "Core").putArray("affected_adapters").add("cc109 (OPay)").add("cc131 (Cobre)")
        }, "INC-204")
        assertSingleError(result, "summary.blast_radius", "2 affected adapters")
    }
}
