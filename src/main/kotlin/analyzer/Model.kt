package analyzer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** One log record: header line plus its indented continuation lines joined into [message]. */
data class LogEntry(
    val timestamp: String,
    val thread: String,
    val level: String,
    val logger: String,
    val message: String,
)

/** Stage 1 output. [notes] are `...` annotation lines, [unparsed] are lines matching no known format. */
data class ParsedLog(
    val entries: List<LogEntry>,
    val notes: List<String>,
    val unparsed: List<String>,
)

data class ErrorSignal(val kind: String, val value: String, val count: Int)

data class TimeWindow(val start: String, val end: String)

/** Stage 2 output: deterministic evidence extracted from a [ParsedLog]. */
data class Signals(
    val adapters: List<String>,
    val layers: List<String>,
    val errors: List<ErrorSignal>,
    val orderTypes: List<String>,
    val timeWindow: TimeWindow?,
    val components: List<String>,
)

// Diagnosis mirrors the task's output schema. Enum-like fields stay strings: the validator,
// not deserialization, decides whether a value is allowed.

data class Diagnosis(
    val incidentId: String?,
    val category: String,
    val summary: Summary,
    val hypotheses: List<Hypothesis>,
    val immediateActions: List<ImmediateAction>,
)

data class Summary(
    val description: String,
    val affectedAdapters: List<String>,
    val affectedOrderTypes: List<String>,
    val faultLayer: String,
    val severity: String,
    val severityReasoning: String,
    val blastRadius: String,
)

data class Hypothesis(
    val title: String,
    val reasoning: String,
    val probability: String,
    val nextSteps: List<NextStep>,
)

data class NextStep(val action: String, val tool: String, val detail: String)

data class ImmediateAction(val action: String, val risk: String, val reasoning: String)

/** Shared JSON settings: snake_case fields, pretty output. */
object Json {
    val mapper: ObjectMapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun write(value: Any): String = mapper.writeValueAsString(value) + "\n"
}
