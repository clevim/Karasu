package eu.kanade.tachiyomi.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HttpExceptionTest {

    @Test
    fun `the reason the server gave is part of the message`() {
        val e = HttpException(400, """{"error":"invalid_request"}""")
        assertEquals("""HTTP error 400: {"error":"invalid_request"}""", e.message)
    }

    @Test
    fun `a missing or empty body leaves the old message alone`() {
        assertEquals("HTTP error 400", HttpException(400).message)
        assertEquals("HTTP error 400", HttpException(400, "").message)
        assertEquals("HTTP error 400", HttpException(400, "   ").message)
    }
}
