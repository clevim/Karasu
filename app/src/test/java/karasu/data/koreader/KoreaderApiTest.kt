package karasu.data.koreader

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KoreaderApiTest {

    @Test
    fun `wake splash HTML extracts title instead of dumping CSS`() {
        val html = """
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <style>body { background: #0d1117; color: #c9d1d9; }</style>
              <title>
                acordando
                Yatagarasu...
              </title>
            </head>
            <body><h1>Starting container</h1></body>
            </html>
        """.trimIndent()

        assertEquals("HTTP 503 (acordando Yatagarasu...)", KoreaderApi.formatHttpError(503, html))
    }

    @Test
    fun `HTML without a title returns status code only`() {
        val html = "<html><body>502 Bad Gateway</body></html>"
        assertEquals("HTTP 502", KoreaderApi.formatHttpError(502, html))
    }

    @Test
    fun `plain error bodies collapse whitespace and cap length`() {
        assertEquals("HTTP 401", KoreaderApi.formatHttpError(401, "   "))
        assertEquals(
            """HTTP 401: {"error":"unauthorized"}""",
            KoreaderApi.formatHttpError(401, """  {"error":"unauthorized"}  """),
        )

        val longBody = "a".repeat(200)
        assertEquals("HTTP 500: ${"a".repeat(120)}", KoreaderApi.formatHttpError(500, longBody))
    }

    @Test
    fun `wake status codes match proxy sleep codes`() {
        for (code in listOf(502, 503, 504, 521, 522, 523, 524)) {
            assertTrue(KoreaderApi.isWakeStatusCode(code), "Expected $code to be treated as wake status")
        }
        for (code in listOf(200, 400, 401, 404, 500)) {
            assertFalse(KoreaderApi.isWakeStatusCode(code), "Expected $code not to be treated as wake status")
        }
    }

    @Test
    fun `transient wake exceptions detect wrapped timeout and connect errors`() {
        assertTrue(KoreaderApi.isTransientWakeException(SocketTimeoutException("timeout")))
        assertTrue(KoreaderApi.isTransientWakeException(ConnectException("Connection refused")))
        assertTrue(KoreaderApi.isTransientWakeException(IOException("wrapped", SocketTimeoutException("timeout"))))
        assertTrue(KoreaderApi.isTransientWakeException(IOException("wrapped", ConnectException("refused"))))
        assertFalse(KoreaderApi.isTransientWakeException(IOException("HTTP 500")))
    }
}
