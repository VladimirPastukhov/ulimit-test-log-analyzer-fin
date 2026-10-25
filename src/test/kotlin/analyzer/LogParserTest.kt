package analyzer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogParserTest {
    @Test
    fun `multi-line record becomes one entry, notes and unknown lines are kept`() {
        val log = LogParser.parse(
            """
            random text
            2024-11-28 20:10:05.100 [http-nio-10087-exec-48] ERROR c.c.m.client.payme.ClientService
              - Failed to acquire connection from pool within 5000ms.
                Active connections: 50/50, pending requests: 127.

            ... (all 50 connections held by slow provider responses)
            """.trimIndent(),
        )

        val entry = log.entries.single()
        assertEquals("2024-11-28 20:10:05.100", entry.timestamp)
        assertEquals("http-nio-10087-exec-48", entry.thread)
        assertEquals("ERROR", entry.level)
        assertEquals("c.c.m.client.payme.ClientService", entry.logger)
        assertEquals("Failed to acquire connection from pool within 5000ms.\nActive connections: 50/50, pending requests: 127.", entry.message)
        assertEquals(listOf("... (all 50 connections held by slow provider responses)"), log.notes)
        assertEquals(listOf("random text"), log.unparsed)
    }

    @Test
    fun `all examples parse without unrecognized lines`() {
        for (id in EXAMPLES) {
            val log = LogParser.parse(example(id))
            assertTrue(log.entries.size >= 4, id)
            assertEquals(1, log.notes.size, id)
            assertEquals(emptyList(), log.unparsed, id)
        }
    }
}
