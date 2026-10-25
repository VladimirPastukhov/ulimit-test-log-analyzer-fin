package analyzer

data class Adapter(val id: String, val provider: String, val port: Int) {
    /** Output form used in diagnoses, e.g. `cc109 (OPay)`. */
    val label get() = "$id ($provider)"
}

/** Static knowledge about the platform: the grounding for signal extraction, prompting and validation. */
object SystemContext {
    val adapters = listOf(
        Adapter("cc087", "PayMe", 10087),
        Adapter("cc109", "OPay", 10109),
        Adapter("cc131", "Cobre", 10131),
        Adapter("cc139", "Halopesa", 10139),
    )

    val layers = listOf("SDK", "Core", "API", "Infrastructure", "External")

    val categories = listOf(
        "External provider degradation",
        "Internal configuration / credential issue",
        "Infrastructure failure (DB/cache/queue)",
        "Connection pool / resource exhaustion",
        "Routing / terminal configuration issue",
    )

    /** Tools a diagnostic next step may reference, besides components mentioned in the log. */
    val tools = listOf("ELK", "Grafana", "Prometheus", "Consul", "Vault", "Redis", "RabbitMQ", "PostgreSQL")

    /** Components recognized in log text, with the patterns that indicate them. */
    val components: Map<String, Regex> = linkedMapOf(
        "MI" to Regex("""\bMI\b"""),
        "Terminals Service" to Regex("""Terminals Service""", RegexOption.IGNORE_CASE),
        "Consul" to Regex("""\bconsul\b""", RegexOption.IGNORE_CASE),
        "Vault" to Regex("""\bvault\b""", RegexOption.IGNORE_CASE),
        "Redis" to Regex("""\bredis""", RegexOption.IGNORE_CASE),
        "RabbitMQ" to Regex("""\brabbit|\bDLQ\b""", RegexOption.IGNORE_CASE),
        "PostgreSQL" to Regex("""\bpostgres""", RegexOption.IGNORE_CASE),
        "MongoDB" to Regex("""\bmongo""", RegexOption.IGNORE_CASE),
    )

    val description: String by lazy {
        SystemContext::class.java.getResource("/system-context.md")!!.readText()
    }

    fun adapterByPort(port: Int): Adapter? = adapters.find { it.port == port }

    fun adapterByProvider(name: String): Adapter? = adapters.find { it.provider.equals(name, ignoreCase = true) }
}
