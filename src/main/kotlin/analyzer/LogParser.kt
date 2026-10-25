package analyzer

/** Stage 1: splits a raw snippet into log entries, `...` notes and unrecognized lines. No LLM involved. */
object LogParser {
    private val header = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([^\]]+)] (\w+)\s+(\S+)(?:\s+-\s+(.*))?$""")

    fun parse(text: String): ParsedLog {
        val entries = mutableListOf<LogEntry>()
        val notes = mutableListOf<String>()
        val unparsed = mutableListOf<String>()

        var current: LogEntry? = null
        val message = mutableListOf<String>()

        fun flush() {
            current?.let { entries += it.copy(message = message.joinToString("\n")) }
            current = null
            message.clear()
        }

        for (line in text.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> continue
                trimmed.startsWith("...") -> {
                    flush()
                    notes += trimmed
                }
                header.matches(line) -> {
                    flush()
                    val (ts, thread, level, logger, inline) = header.matchEntire(line)!!.destructured
                    current = LogEntry(ts, thread, level, logger, "")
                    if (inline.isNotEmpty()) message += inline
                }
                line[0].isWhitespace() && current != null ->
                    message += if (message.isEmpty()) trimmed.removePrefix("- ").trim() else trimmed
                else -> {
                    flush()
                    unparsed += trimmed
                }
            }
        }
        flush()
        return ParsedLog(entries, notes, unparsed)
    }
}
