package analyzer

/** Stage 2: deterministic evidence from parsed entries. Its output is also the ground truth for validation. */
object SignalExtractor {
    private val port = Regex("""-10(\d{3})-""")
    private val loggerPackage = Regex("""^c\.c\.m\.(?:client\.)?(\w+)\.""")
    private val qualifiedException = Regex("""\b((?:[a-z]\w*\.)+)[A-Z]\w*(?:Exception|Error)\b""")

    /** Client-library packages whose exceptions mean a database, cache or queue failed. */
    private val infrastructurePackages = listOf(
        "com.mongodb.", "org.postgresql.", "java.sql.", "redis.clients.", "io.lettuce.", "com.rabbitmq.", "org.springframework.amqp.",
    )

    /** (kind, pattern); group 1, when present, is the value. A signal is counted once per entry. */
    private val errorPatterns = listOf(
        "http_status" to Regex("""status=(\d{3})"""),
        "http_status" to Regex("""\breturn(?:ed|ing) (\d{3})\b""", RegexOption.IGNORE_CASE),
        "provider_code" to Regex(""""code"\s*:\s*"(\w+)""""),
        "exception" to Regex("""\b([A-Z]\w*Exception)\b"""),
        "timeout" to Regex("""timed out (?:after )?\(?(\d+)ms""", RegexOption.IGNORE_CASE),
        "pool_exhausted" to Regex("""acquire connection from pool within (\d+)ms"""),
        "slow_response" to Regex("""Slow response from \S+ ([\d,]+)ms"""),
        "signature_mismatch" to Regex("""signature (?:validation failed|mismatch)""", RegexOption.IGNORE_CASE),
        "terminal_link_missing" to Regex("""No terminal link found for terminal=([\w-]+)"""),
        "decline_code" to Regex("""decline(?:_code)?=(\w+)"""),
        "mapped_status" to Regex("""(?:Mapped to|Returning):? ([A-Z][A-Z_]+)\b"""),
    )

    private val payoutOrder = Regex("""\bpyt-""")
    private val paymentMention = Regex("""\bPayment order\b|/payments?/|/charge\b""")

    fun extract(log: ParsedLog): Signals {
        val entries = log.entries
        return Signals(
            adapters = adapters(entries),
            layers = SystemContext.layers.filter { layer -> entries.any { layer in layersOf(it) } },
            errors = errors(entries),
            orderTypes = orderTypes(entries),
            timeWindow = entries.map { it.timestamp }.sorted().let { if (it.isEmpty()) null else TimeWindow(it.first(), it.last()) },
            components = SystemContext.components.filter { (_, re) ->
                entries.any { re.containsMatchIn(it.message) || re.containsMatchIn(it.thread) || re.containsMatchIn(it.logger) }
            }.keys.toList(),
        )
    }

    private fun adapters(entries: List<LogEntry>): List<String> = entries.flatMap { entry ->
        val byPort = port.find(entry.thread)?.groupValues?.get(1)?.let { digits ->
            SystemContext.adapterByPort(("10$digits").toInt())?.label ?: "cc$digits"
        }
        val byLogger = loggerPackage.find(entry.logger)?.groupValues?.get(1)?.let { SystemContext.adapterByProvider(it)?.label }
        listOfNotNull(byPort, byLogger)
    }.distinct().sorted()

    private fun layersOf(entry: LogEntry): List<String> {
        val infraException = qualifiedException.findAll(entry.message)
            .any { match -> infrastructurePackages.any { match.groupValues[1].startsWith(it) } }
        return listOfNotNull(layerOf(entry.logger), "Infrastructure".takeIf { infraException })
    }

    fun layerOf(logger: String): String? = when {
        ".client." in logger -> "SDK"
        ".core." in logger -> "Core"
        ".api." in logger -> "API"
        listOf(".cache.", ".queue.", ".db.", ".rabbit.").any { it in logger } -> "Infrastructure"
        else -> null
    }

    private fun errors(entries: List<LogEntry>): List<ErrorSignal> = entries
        .flatMap { entry ->
            errorPatterns.flatMap { (kind, re) ->
                re.findAll(entry.message).map { kind to clean(it.groupValues.getOrNull(1) ?: "") }.toList()
            }.distinct()
        }
        .groupingBy { it }.eachCount()
        .map { (key, count) -> ErrorSignal(key.first, key.second, count) }
        .sortedWith(compareBy({ it.kind }, { it.value }))

    private fun orderTypes(entries: List<LogEntry>): List<String> = buildSet {
        for (e in entries) {
            if ("Payout" in e.logger || "Payout" in e.message || payoutOrder.containsMatchIn(e.message)) add("PAYOUT")
            if ("Payment" in e.logger || paymentMention.containsMatchIn(e.message)) add("PAYMENT")
        }
    }.sorted()

    private fun clean(number: String) = number.replace(",", "").trimEnd('.', ',')
}
