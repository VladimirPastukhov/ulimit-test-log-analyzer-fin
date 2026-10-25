package analyzer

import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.system.exitProcess

const val EXIT_OK = 0
const val EXIT_USAGE = 1
const val EXIT_DIAGNOSIS_FAILED = 2
const val EXIT_LLM_ERROR = 3

private const val USAGE = """usage: java -jar analyzer.jar <log-file>

Environment:
  ANTHROPIC_API_KEY / OPENAI_API_KEY   API key for the provider (one is required)
  LLM_PROVIDER                         anthropic | openai (default: whichever key is set, Anthropic first)
  LLM_MODEL                            model name (default: claude-sonnet-5-5 / gpt-4.1)"""

data class LlmConfig(val provider: String, val model: String, val apiKey: String)

class ConfigException(message: String) : Exception(message)

fun incidentIdOf(path: Path): String? = Regex("""INC-\d+""").find(path.fileName.toString())?.value

fun llmConfig(env: Map<String, String>): LlmConfig {
    val keys = mapOf("anthropic" to "ANTHROPIC_API_KEY", "openai" to "OPENAI_API_KEY")
    val defaults = mapOf("anthropic" to "claude-sonnet-5-5", "openai" to "gpt-4.1")
    fun key(provider: String) = env[keys.getValue(provider)]?.takeIf { it.isNotBlank() }

    val provider = env["LLM_PROVIDER"]?.takeIf { it.isNotBlank() }?.lowercase()
        ?: keys.keys.firstOrNull { key(it) != null }
        ?: throw ConfigException("No LLM API key configured: set ANTHROPIC_API_KEY or OPENAI_API_KEY")
    if (provider !in keys) throw ConfigException("LLM_PROVIDER must be 'anthropic' or 'openai', got '$provider'")
    val apiKey = key(provider) ?: throw ConfigException("LLM_PROVIDER is '$provider' but ${keys.getValue(provider)} is not set")
    val model = env["LLM_MODEL"]?.takeIf { it.isNotBlank() } ?: defaults.getValue(provider)
    return LlmConfig(provider, model, apiKey)
}

fun chatModel(config: LlmConfig): ChatModel = when (config.provider) {
    // No temperature for Anthropic: current Claude models reject it as deprecated.
    "anthropic" -> AnthropicChatModel.builder()
        .apiKey(config.apiKey).modelName(config.model)
        .maxTokens(4096).timeout(Duration.ofSeconds(120))
        .build()
    else -> OpenAiChatModel.builder()
        .apiKey(config.apiKey).modelName(config.model)
        .temperature(0.0).timeout(Duration.ofSeconds(120))
        .build()
}

/** The whole pipeline; stdout receives only a valid diagnosis, everything else goes to [err]. */
fun run(
    args: List<String>,
    env: Map<String, String>,
    out: PrintStream,
    err: PrintStream,
    modelFactory: (LlmConfig) -> ChatModel = ::chatModel,
): Int {
    if (args.size != 1) {
        err.println(USAGE)
        return EXIT_USAGE
    }
    val path = Path.of(args.single())
    if (!path.exists() || !path.isRegularFile()) {
        err.println("error: log file not found: $path")
        return EXIT_USAGE
    }
    val config = try {
        llmConfig(env)
    } catch (e: ConfigException) {
        err.println("error: ${e.message}")
        return EXIT_USAGE
    }

    val snippet = path.readText()
    val incidentId = incidentIdOf(path)
    val log = LogParser.parse(snippet)
    err.println("[1/5] parsed ${log.entries.size} entries, ${log.notes.size} notes, ${log.unparsed.size} unrecognized lines")
    val signals = SignalExtractor.extract(log)
    err.println("[2/5] signals: adapters=${signals.adapters}, layers=${signals.layers}, components=${signals.components}")
    err.println("[3/5] enriched with system context (${SystemContext.adapters.size} adapters, ${SystemContext.tools.size} tools)")
    err.println("[4/5] diagnosing with ${config.provider} ${config.model}")

    val result = try {
        Diagnoser(modelFactory(config), trace = { err.println("[5/5] $it") }).diagnose(log, snippet, signals, incidentId)
    } catch (e: Exception) {
        err.println("error: LLM call failed: ${e.message}")
        return EXIT_LLM_ERROR
    }

    return when (result) {
        is DiagnosisResult.Success -> {
            out.print(Json.write(result.diagnosis))
            out.flush()
            EXIT_OK
        }
        is DiagnosisResult.Failure -> {
            err.println("error: no valid diagnosis after ${result.attempts} attempts")
            err.println("last answer:")
            err.println(result.lastAnswer.trim())
            err.println("validation errors:")
            result.errors.forEach { err.println("- $it") }
            EXIT_DIAGNOSIS_FAILED
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.getenv(), System.out, System.err))
}
