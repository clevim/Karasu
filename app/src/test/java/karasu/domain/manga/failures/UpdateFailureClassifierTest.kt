package karasu.domain.manga.failures

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.SourceNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateFailureClassifierTest {

    @Test
    fun `an answer from the server counts against the source`() {
        // 404 is the entry being gone, the rest is the source refusing or breaking.
        assertTrue(shouldCountUpdateFailure(HttpException(404)))
        assertTrue(shouldCountUpdateFailure(HttpException(410)))
        assertTrue(shouldCountUpdateFailure(HttpException(403)))
        assertTrue(shouldCountUpdateFailure(HttpException(503)))
    }

    @Test
    fun `never reaching the server does not count`() {
        // This is the case that matters: with no signal every manga in the library fails at
        // once, and counting it would empty the library into the triage category.
        assertFalse(shouldCountUpdateFailure(UnknownHostException("no dns")))
        assertFalse(shouldCountUpdateFailure(SocketTimeoutException("timeout")))
        assertFalse(shouldCountUpdateFailure(ConnectException("refused")))
        assertFalse(shouldCountUpdateFailure(SSLHandshakeException("handshake")))
    }

    @Test
    fun `a cancelled update is not a failure`() {
        assertFalse(shouldCountUpdateFailure(CancellationException("stopped")))
    }

    @Test
    fun `a missing extension is reported on its own, not as a failure`() {
        assertFalse(shouldCountUpdateFailure(SourceNotFoundException("gone", 1L)))
    }

    @Test
    fun `an unusable answer counts`() {
        // Parse failures mean the source replied with something we could not read.
        assertTrue(shouldCountUpdateFailure(IllegalStateException("no chapters found")))
        assertTrue(shouldCountUpdateFailure(IOException("truncated body")))
    }

    @Test
    fun `entry gone is distinguishable from the source being down`() {
        assertTrue(isEntryGone(HttpException(404)))
        assertTrue(isEntryGone(HttpException(410)))
        assertFalse(isEntryGone(HttpException(503)))
        assertFalse(isEntryGone(SocketTimeoutException("timeout")))
    }
}
