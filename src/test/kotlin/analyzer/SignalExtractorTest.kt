package analyzer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalExtractorTest {
    private fun signalsOf(id: String) = SignalExtractor.extract(LogParser.parse(example(id)))
    private fun Signals.has(kind: String, value: String = "") = errors.any { it.kind == kind && it.value.startsWith(value) }

    @Test
    fun `provider outage - adapter from port and logger, 503 and provider code`() {
        val s = signalsOf("INC-201")
        assertEquals(listOf("cc109 (OPay)"), s.adapters)
        assertEquals(listOf("SDK", "Core"), s.layers)
        assertEquals(listOf("PAYMENT"), s.orderTypes)
        assertTrue(ErrorSignal("provider_code", "MAINTENANCE", 2) in s.errors)
        assertTrue(s.errors.single { it.kind == "http_status" && it.value == "503" }.count >= 2)
    }

    @Test
    fun `signature mismatch is an API layer signal`() {
        val s = signalsOf("INC-202")
        assertEquals(listOf("cc087 (PayMe)"), s.adapters)
        assertEquals(listOf("API"), s.layers)
        assertTrue(s.has("signature_mismatch"))
    }

    @Test
    fun `payout incident - window, components and Infrastructure from a qualified MongoDB exception`() {
        val s = signalsOf("INC-203")
        assertEquals(listOf("PAYOUT"), s.orderTypes)
        assertEquals(TimeWindow("2024-10-22 11:30:15.200", "2024-10-22 11:40:00.000"), s.timeWindow)
        assertEquals(listOf("RabbitMQ", "MongoDB"), s.components)
        assertEquals(listOf("SDK", "Core", "Infrastructure"), s.layers)
    }

    @Test
    fun `unqualified exception does not add Infrastructure`() {
        val s = SignalExtractor.extract(
            LogParser.parse(
                """
                2024-10-22 11:40:00.000 [scheduling-1] ERROR c.c.m.halopesa.core.StatusCheckService
                  - MongoDB health check failed: MongoTimeoutException
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Core"), s.layers)
    }

    @Test
    fun `terminal link incident spans exactly two adapters`() {
        val s = signalsOf("INC-204")
        assertEquals(listOf("cc109 (OPay)", "cc131 (Cobre)"), s.adapters)
        assertEquals(listOf("Terminals Service", "Redis"), s.components)
        assertTrue(s.has("terminal_link_missing", "T-4420") && s.has("terminal_link_missing", "T-7712"))
    }

    @Test
    fun `pool exhaustion - exceptions, timeouts and slow responses`() {
        val s = signalsOf("INC-205")
        assertTrue(s.has("pool_exhausted", "5000"))
        assertTrue(s.has("exception", "SocketTimeoutException"))
        assertTrue(s.has("timeout", "30000"))
        assertTrue(s.has("slow_response", "28450"))
    }
}
