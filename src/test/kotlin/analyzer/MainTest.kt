package analyzer

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MainTest {
    private val key = mapOf("ANTHROPIC_API_KEY" to "test-key")
    private val valid = javaClass.getResource("/valid-diagnosis-INC-201.json")!!.readText()

    private class Run(val exit: Int, val out: String, val err: String)

    private fun run(args: List<String>, env: Map<String, String> = key, factory: (LlmConfig) -> ChatModel = { error("LLM must not be called") }): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = run(args, env, PrintStream(out, true), PrintStream(err, true), factory)
        return Run(exit, out.toString(), err.toString())
    }

    @Test
    fun `incident id comes from the file name`() {
        assertEquals("INC-205", incidentIdOf(Path.of("examples/INC-205.txt")))
        assertEquals(null, incidentIdOf(Path.of("/tmp/snippet.log")))
    }

    @Test
    fun `provider comes from LLM_PROVIDER or the key that is set`() {
        assertEquals(LlmConfig("openai", "gpt-4.1", "o"), llmConfig(mapOf("OPENAI_API_KEY" to "o")))
        assertEquals(LlmConfig("anthropic", "claude-sonnet-5-5", "a"), llmConfig(mapOf("OPENAI_API_KEY" to "o", "ANTHROPIC_API_KEY" to "a")))
        assertEquals(
            LlmConfig("openai", "gpt-4.1-mini", "o"),
            llmConfig(mapOf("OPENAI_API_KEY" to "o", "ANTHROPIC_API_KEY" to "a", "LLM_PROVIDER" to "openai", "LLM_MODEL" to "gpt-4.1-mini")),
        )
        assertFailsWith<ConfigException> { llmConfig(emptyMap()) }
        assertFailsWith<ConfigException> { llmConfig(mapOf("LLM_PROVIDER" to "openai", "ANTHROPIC_API_KEY" to "a")) }
    }

    @Test
    fun `usage, missing file and missing key fail before calling the LLM`() {
        val cases = listOf(
            run(emptyList()) to "usage:",
            run(listOf("examples/INC-999.txt")) to "examples/INC-999.txt",
            run(listOf("examples/INC-201.txt"), env = emptyMap()) to "ANTHROPIC_API_KEY or OPENAI_API_KEY",
        )
        for ((r, message) in cases) {
            assertEquals(EXIT_USAGE, r.exit)
            assertEquals("", r.out)
            assertTrue(message in r.err, r.err)
        }
    }

    @Test
    fun `successful run prints only the diagnosis to stdout`() {
        val r = run(listOf("examples/INC-201.txt"), factory = { FakeChatModel(valid) })
        assertEquals(EXIT_OK, r.exit)
        assertEquals("INC-201", Json.mapper.readTree(r.out)["incident_id"].asText())
        assertTrue("[5/5] attempt 1/4: valid" in r.err)
    }

    @Test
    fun `failed diagnosis and LLM errors print nothing to stdout`() {
        val bad = valid.replace("\"ELK\"", "\"Datadog\"")
        val exhausted = run(listOf("examples/INC-201.txt"), factory = { FakeChatModel(bad, bad, bad, bad) })
        assertEquals(EXIT_DIAGNOSIS_FAILED, exhausted.exit)
        assertEquals("", exhausted.out)
        assertTrue("no valid diagnosis after 4 attempts" in exhausted.err && "'Datadog' is not an allowed tool" in exhausted.err)

        val failing = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse = throw RuntimeException("401 invalid x-api-key")
        }
        val llmError = run(listOf("examples/INC-201.txt"), factory = { failing })
        assertEquals(EXIT_LLM_ERROR, llmError.exit)
        assertEquals("", llmError.out)
        assertTrue("LLM call failed: 401 invalid x-api-key" in llmError.err)
    }
}
