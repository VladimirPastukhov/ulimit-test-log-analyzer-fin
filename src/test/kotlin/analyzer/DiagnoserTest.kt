package analyzer

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnoserTest {
    private val snippet = example("INC-201")
    private val log = LogParser.parse(snippet)
    private val signals = SignalExtractor.extract(log)
    private val valid = javaClass.getResource("/valid-diagnosis-INC-201.json")!!.readText()

    private fun variant(edit: ObjectNode.() -> Unit) = (Json.mapper.readTree(valid) as ObjectNode).apply(edit).toString()
    private val inventedAdapter = variant { (this["summary"] as ObjectNode).putArray("affected_adapters").add("cc999 (StripeX)") }

    private fun diagnose(model: FakeChatModel) = Diagnoser(model).diagnose(log, snippet, signals, "INC-201")

    private fun text(message: Any) = when (message) {
        is SystemMessage -> message.text()
        is UserMessage -> message.singleText()
        is AiMessage -> message.text()
        else -> error("unexpected message $message")
    }

    @Test
    fun `prompt contains context, signals, snippet and allowed tools`() {
        val model = FakeChatModel(valid)
        diagnose(model)

        val (system, user) = model.requests.single().map(::text)
        assertTrue("Terminals Service" in system, "system context")
        assertTrue("cc109 (OPay), HTTP port 10109" in system, "adapter registry")
        assertTrue(SystemContext.tools.joinToString(", ") in system, "allowed tools")
        assertTrue("\"blast_radius\": \"single_merchant|single_adapter|multi_adapter|platform_wide\"" in system, "schema")
        assertTrue("Incident id: INC-201" in user)
        assertTrue(Json.write(signals).trim() in user, "signals")
        assertTrue("... (pattern repeats for 200+ requests over 45 minutes)" in user, "notes")
        assertTrue(snippet.trim() in user, "raw snippet")
    }

    @Test
    fun `invalid JSON and invented adapter are re-prompted with the previous answer and the error`() {
        val broken = """{"category": "External provider degradation", "summary": {"""
        val model = FakeChatModel(broken, inventedAdapter, valid)
        val result = assertIs<DiagnosisResult.Success>(diagnose(model))
        assertEquals(3, result.attempts)

        val (_, _, firstAnswer, firstRepair) = model.requests[1]
        assertEquals(broken, text(firstAnswer))
        assertTrue("not a valid JSON object" in text(firstRepair))
        val secondRepair = model.requests[2].last()
        assertTrue("cc999" in text(secondRepair) && "not present in the system or the logs" in text(secondRepair))
    }

    @Test
    fun `four failing answers fail after exactly four calls with the last errors`() {
        val datadog = variant {
            (((this["hypotheses"])[0] as ObjectNode)["next_steps"][0] as ObjectNode).put("tool", "Datadog")
        }
        val model = FakeChatModel(inventedAdapter, inventedAdapter, inventedAdapter, datadog)
        val result = assertIs<DiagnosisResult.Failure>(diagnose(model))

        assertEquals(4, model.requests.size)
        assertEquals(4, result.attempts)
        assertEquals(1, result.errors.size)
        assertTrue("Datadog" in result.errors.single())
        assertTrue("Datadog" in result.lastAnswer)
        assertEquals(2 + 3 * 2, model.requests.last().size, "conversation keeps every previous answer and repair request")
    }
}
