package analyzer

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * [diagnosis] is set only when [errors] is empty. [json] is the normalized answer (or null if it
 * was not parseable) and is what gets shown when an attempt is rejected.
 */
data class ValidationResult(val diagnosis: Diagnosis?, val json: String?, val errors: List<String>)

/** Stage 5a: deterministic checks of an LLM answer against the schema, the system and the log evidence. */
object Validator {
    val enums: Map<String, List<String>> = mapOf(
        "category" to SystemContext.categories,
        "fault_layer" to SystemContext.layers,
        "severity" to listOf("low", "medium", "high", "critical"),
        "blast_radius" to listOf("single_merchant", "single_adapter", "multi_adapter", "platform_wide"),
        "probability" to listOf("likely", "possible", "unlikely"),
        "risk" to listOf("safe", "caution", "risky"),
    )

    private val fence = Regex("""```(?:json)?\s*(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    private val adapterId = Regex("""\bcc\d{3}\b""", RegexOption.IGNORE_CASE)

    // Required fields and types come from the non-null Diagnosis data classes; unknown fields are dropped.
    private val reader = Json.mapper.readerFor(Diagnosis::class.java)
        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun check(text: String, signals: Signals, incidentId: String?): ValidationResult {
        val root = try {
            parse(text)
        } catch (e: Exception) {
            return ValidationResult(null, null, listOf("Response is not a valid JSON object: ${e.message?.lineSequence()?.first()}"))
        }
        normalizeEnums(root)
        val json = Json.write(root)
        val parsed = try {
            reader.readValue<Diagnosis>(root)
        } catch (e: MismatchedInputException) {
            return ValidationResult(null, json, listOf("${pathOf(e)}: missing or has the wrong type"))
        }

        // Trivial deviations are fixed here instead of costing a retry.
        val diagnosis = parsed.copy(
            incidentId = incidentId,
            hypotheses = parsed.hypotheses.take(3).map { it.copy(nextSteps = it.nextSteps.take(3)) },
            immediateActions = parsed.immediateActions.take(2),
        )
        val errors = validate(diagnosis, signals)
        return ValidationResult(diagnosis.takeIf { errors.isEmpty() }, json, errors)
    }

    /** Takes the JSON object out of fences or surrounding prose. Throws when there is none. */
    private fun parse(text: String): ObjectNode {
        val body = fence.find(text)?.groupValues?.get(1) ?: text
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON object found" }
        return Json.mapper.readTree(body.substring(start, end + 1)) as? ObjectNode
            ?: throw IllegalArgumentException("top-level value is not an object")
    }

    /** Rewrites enum values that differ only in case or separators (`High`, `single-adapter`) to the allowed spelling. */
    private fun normalizeEnums(node: JsonNode) {
        if (node is ObjectNode) {
            enums.forEach { (field, allowed) ->
                val value = node[field]?.takeIf { it.isTextual }?.asText() ?: return@forEach
                allowed.find { canonical(it) == canonical(value) }?.let { node.put(field, it) }
            }
        }
        node.forEach(::normalizeEnums)
    }

    private fun canonical(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    private fun pathOf(e: MismatchedInputException) = e.path.joinToString("") { ref ->
        if (ref.index >= 0) "[${ref.index}]" else ".${ref.fieldName}"
    }.removePrefix(".")

    private fun validate(d: Diagnosis, signals: Signals): List<String> = buildList {
        fun enum(path: String, field: String, value: String) {
            val allowed = enums.getValue(field)
            if (value !in allowed) add("$path$field: '$value' is not allowed; expected one of ${allowed.joinToString(", ")}")
        }

        // Allowed values and list sizes
        enum("", "category", d.category)
        enum("summary.", "fault_layer", d.summary.faultLayer)
        enum("summary.", "severity", d.summary.severity)
        enum("summary.", "blast_radius", d.summary.blastRadius)
        if (d.hypotheses.isEmpty()) add("hypotheses: expected at least 1 item(s), got 0")
        if (d.immediateActions.isEmpty()) add("immediate_actions: expected at least 1 item(s), got 0")
        d.hypotheses.forEachIndexed { i, h ->
            enum("hypotheses[$i].", "probability", h.probability)
            if (h.nextSteps.size < 2) add("hypotheses[$i].next_steps: expected at least 2 item(s), got ${h.nextSteps.size}")
        }
        d.immediateActions.forEachIndexed { i, a -> enum("immediate_actions[$i].", "risk", a.risk) }

        // Grounding: adapters and tools must exist in the system or the logs
        val loggedAdapters = signals.adapters.mapNotNull { adapterId.find(it)?.value?.lowercase() }.toSet()
        val knownAdapters = SystemContext.adapters.map { it.id } + loggedAdapters
        d.summary.affectedAdapters.filter { adapterId.find(it)?.value?.lowercase() !in knownAdapters }.forEach {
            add("summary.affected_adapters: '$it' is not present in the system or the logs; known adapters: ${SystemContext.adapters.joinToString(", ") { a -> a.label }}")
        }
        val allowedTools = SystemContext.tools + signals.components
        d.hypotheses.forEachIndexed { i, h ->
            h.nextSteps.forEachIndexed { j, step ->
                if (allowedTools.none { it.equals(step.tool.trim(), ignoreCase = true) }) {
                    add("hypotheses[$i].next_steps[$j].tool: '${step.tool}' is not an allowed tool; use one of ${allowedTools.joinToString(", ")}")
                }
            }
        }

        // Contradictions with the log evidence
        if (d.summary.faultLayer == "External" && !hasProviderSideError(signals)) {
            add("summary.fault_layer: 'External' contradicts the logs, which contain no provider-side error (5xx, provider error code, slow response or outbound timeout)")
        }
        d.hypotheses.forEachIndexed { i, h ->
            if (h.probability != "likely") return@forEachIndexed
            SystemContext.components.filter { (name, re) -> re.containsMatchIn(h.title) && name !in signals.components }.keys.forEach {
                add("hypotheses[$i]: likely hypothesis '${h.title}' blames $it, which the logs never mention; lower its probability or ground it in the evidence")
            }
        }
        if (d.summary.blastRadius == "single_adapter" && loggedAdapters.size > 1) {
            add("summary.blast_radius: 'single_adapter' contradicts the logs, which show ${loggedAdapters.size} affected adapters (${signals.adapters.joinToString(", ")})")
        }
    }

    private fun hasProviderSideError(signals: Signals) = signals.errors.any { e ->
        (e.kind == "http_status" && e.value.startsWith("5")) ||
            e.kind == "provider_code" ||
            e.kind == "slow_response" ||
            (e.kind == "exception" && e.value in setOf("SocketTimeoutException", "ConnectException")) ||
            (e.kind == "timeout" && "SDK" in signals.layers)
    }
}
