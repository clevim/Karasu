package eu.kanade.tachiyomi.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the parsing/mapping half of [FlareSolverr] — the half that silently produces a useless
 * cookie if it drifts. The HTTP half needs a live instance and is not tested here.
 */
class FlareSolverrTest {

    private val url = "https://manga.example.com/series/1".toHttpUrl()

    private fun parse(body: String) =
        FlareSolverr.json.decodeFromString(FlareSolverr.SolveResponse.serializer(), body)

    @Test
    fun `solved response yields cookie and agent`() {
        val parsed = parse(
            """
            {
              "status": "ok",
              "message": "Challenge solved!",
              "startTimestamp": 1594872947467,
              "solution": {
                "url": "https://manga.example.com/",
                "status": 200,
                "response": "<html></html>",
                "cookies": [
                  {
                    "name": "cf_clearance", "value": "abc123",
                    "domain": ".example.com", "path": "/",
                    "expires": 1610684149.307722,
                    "size": 178, "httpOnly": true, "secure": true,
                    "session": false, "sameSite": "None"
                  }
                ],
                "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120"
              }
            }
            """.trimIndent(),
        )

        assertEquals("ok", parsed.status)
        val solution = parsed.solution!!
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120", solution.userAgent)

        val cookie = solution.toCookies(url).single()
        assertEquals("cf_clearance", cookie.name)
        assertEquals("abc123", cookie.value)
        // Leading dot stripped, and NOT host-only so subdomains keep matching.
        assertEquals("example.com", cookie.domain)
        assertTrue(!cookie.hostOnly)
        assertTrue(cookie.httpOnly)
        assertTrue(cookie.secure)
        // Seconds -> millis; truncation is fine, being off by <1s does not matter.
        assertEquals(1610684149307L, cookie.expiresAt)
    }

    @Test
    fun `flaresolverr 3_x expiry field is honoured`() {
        // Captured from a live FlareSolverr 3.4.6: it speaks Selenium's cookie format, where the
        // expiry is an integer under `expiry`. Reading only `expires` made every cookie a session
        // cookie, so the clearance died with the process.
        val parsed = parse(
            """
            {
              "status": "ok",
              "message": "Challenge solved!",
              "solution": {
                "url": "https://manga.example.com/",
                "status": 200,
                "cookies": [
                  {
                    "name": "cf_clearance", "value": "abc123",
                    "domain": ".example.com", "path": "/",
                    "expiry": 1816906932,
                    "httpOnly": true, "secure": true, "sameSite": "None"
                  }
                ],
                "userAgent": "Mozilla/5.0 (X11; Linux x86_64) Chrome/142.0.0.0"
              }
            }
            """.trimIndent(),
        )

        val cookie = parsed.solution!!.toCookies(url).single()
        assertTrue(cookie.persistent)
        assertEquals(1816906932000L, cookie.expiresAt)
    }

    @Test
    fun `unknown fields and failures do not blow up`() {
        // Newer FlareSolverr versions add fields; ignoring them must not fail the whole parse.
        val parsed = parse(
            """{"status":"error","message":"Challenge not detected!","brandNewField":42}""",
        )
        assertEquals("error", parsed.status)
        assertNull(parsed.solution)
    }

    @Test
    fun `session cookie and blank domain fall back sensibly`() {
        val parsed = parse(
            """
            {
              "status": "ok",
              "solution": {
                "userAgent": "UA",
                "cookies": [
                  { "name": "s", "value": "1", "domain": "", "path": "", "expires": -1 }
                ]
              }
            }
            """.trimIndent(),
        )

        val cookie = parsed.solution!!.toCookies(url).single()
        assertEquals("manga.example.com", cookie.domain)
        assertEquals("/", cookie.path)
        // No expiry given -> session cookie. okhttp flags that as non-persistent (and parks
        // expiresAt on its own MAX_DATE sentinel, which is not Long.MAX_VALUE).
        assertTrue(!cookie.persistent)
    }

    @Test
    fun `malformed cookie is dropped instead of killing the batch`() {
        val parsed = parse(
            """
            {
              "status": "ok",
              "solution": {
                "userAgent": "UA",
                "cookies": [
                  { "name": "bad", "value": "x", "domain": "not a host" },
                  { "name": "good", "value": "y", "domain": "example.com" }
                ]
              }
            }
            """.trimIndent(),
        )

        val cookies = parsed.solution!!.toCookies(url)
        assertEquals(listOf("good"), cookies.map { it.name })
    }
}
