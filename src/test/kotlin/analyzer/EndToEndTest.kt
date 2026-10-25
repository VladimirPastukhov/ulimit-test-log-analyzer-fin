package analyzer

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EndToEndTest {
    private fun expected(id: String) = Json.mapper.readTree(Path.of("examples/$id.expected.json").readText())

    /** Runs the real pipeline against the configured LLM; skipped when no API key is set. */
    @ParameterizedTest
    @ValueSource(strings = ["INC-201", "INC-202", "INC-203", "INC-204", "INC-205"])
    fun `real LLM diagnosis matches predictable fields`(id: String) {
        val env = System.getenv()
        assumeTrue(env["ANTHROPIC_API_KEY"].orEmpty().isNotBlank() || env["OPENAI_API_KEY"].orEmpty().isNotBlank(), "no LLM API key set")

        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = run(listOf("examples/$id.txt"), env, PrintStream(out, true), PrintStream(err, true))
        System.err.print(err.toString())
        assertEquals(EXIT_OK, exit, "analyzer failed:\n$err")

        val signals = SignalExtractor.extract(LogParser.parse(example(id)))
        assertEquals(emptyList(), Validator.check(out.toString(), signals, id).errors, "output does not pass the validator")
        assertEquals(emptyList(), Expectations.mismatches(expected(id), Json.mapper.readTree(out.toString())))
    }
}
