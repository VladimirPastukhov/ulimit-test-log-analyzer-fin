package analyzer

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel

sealed interface DiagnosisResult {
    val attempts: Int

    data class Success(val diagnosis: Diagnosis, override val attempts: Int) : DiagnosisResult

    /** [lastAnswer] is the normalized JSON of the last attempt when it parsed, otherwise the raw text. */
    data class Failure(val lastAnswer: String, val errors: List<String>, override val attempts: Int) : DiagnosisResult
}

/** Stage 4 + 5b: asks the LLM for a diagnosis and re-prompts with validation errors until it is valid. */
class Diagnoser(
    private val model: ChatModel,
    private val maxRetries: Int = 3,
    private val trace: (String) -> Unit = {},
) {
    fun diagnose(log: ParsedLog, snippet: String, signals: Signals, incidentId: String?): DiagnosisResult {
        val messages = mutableListOf<ChatMessage>(systemMessage(), userMessage(log, snippet, signals, incidentId))
        var attempt = 1
        while (true) {
            val answer = model.chat(messages).aiMessage().text().orEmpty()
            val result = Validator.check(answer, signals, incidentId)
            trace("attempt $attempt/${maxRetries + 1}: ${if (result.errors.isEmpty()) "valid" else "${result.errors.size} validation error(s)"}")
            result.errors.forEach { trace("  - $it") }

            if (result.diagnosis != null) return DiagnosisResult.Success(result.diagnosis, attempt)
            if (attempt > maxRetries) return DiagnosisResult.Failure(result.json ?: answer, result.errors, attempt)

            messages += AiMessage.from(answer)
            messages += UserMessage.from(repairMessage(result.errors))
            attempt++
        }
    }

    fun systemMessage(): SystemMessage = SystemMessage.from(
        buildString {
            appendLine("You are an incident analysis assistant for on-call engineers at a payment processing company.")
            appendLine("Diagnose payment adapter incidents from log snippets and extracted signals.")
            appendLine()
            appendLine(SystemContext.description.trim())
            appendLine()
            appendLine("## Known adapters")
            SystemContext.adapters.forEach { appendLine("- ${it.label}, HTTP port ${it.port}") }
            appendLine()
            appendLine("## Output rules")
            appendLine("- Respond with a single JSON object matching the schema below and nothing else.")
            appendLine("- category must be one of: ${SystemContext.categories.joinToString("; ")}.")
            appendLine("- Enum fields must use exactly the values listed in the schema.")
            appendLine("- affected_adapters: only adapters from the known list or from the extracted signals, written as \"ccNNN (Provider)\".")
            appendLine("- Up to 3 hypotheses, each with 2-3 next_steps; 1-2 immediate_actions.")
            appendLine("- next_steps[].tool must be one of: ${SystemContext.tools.joinToString(", ")}, or a component listed in the signals' \"components\".")
            appendLine("- Ground every claim in the log evidence. Rate a hypothesis \"likely\" only if the logs support it.")
            appendLine()
            appendLine("## Schema")
            append(schema)
        },
    )

    fun userMessage(log: ParsedLog, snippet: String, signals: Signals, incidentId: String?): UserMessage = UserMessage.from(
        buildString {
            appendLine("Incident id: ${incidentId ?: "unknown"}")
            appendLine()
            appendLine("## Extracted signals")
            appendLine(Json.write(signals).trim())
            appendLine()
            appendLine("## Annotations")
            appendLine(log.notes.joinToString("\n").ifEmpty { "none" })
            appendLine()
            appendLine("## Raw log snippet")
            append(snippet.trim())
        },
    )

    private fun repairMessage(errors: List<String>) =
        "Your previous answer was rejected by validation:\n" +
            errors.joinToString("\n") { "- $it" } +
            "\nReturn the complete corrected JSON object only."

    private companion object {
        val schema: String = Diagnoser::class.java.getResource("/diagnosis-schema.json")!!.readText().trim()
    }
}
