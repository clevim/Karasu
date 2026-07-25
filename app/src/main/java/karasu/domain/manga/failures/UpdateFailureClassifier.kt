package karasu.domain.manga.failures

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.SourceNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/**
 * Whether a failed update attempt says anything about the source.
 *
 * The counter this feeds decides when an entry gets pulled out of the user's reading category,
 * so it must only count failures the source is responsible for. A phone with no signal fails
 * every manga in the library at once; counting those would empty the library into the
 * triage category on the first flight and make it useless.
 *
 * The rule is therefore: anything that failed before we got an answer from the server is the
 * connection's fault and does not count. Anything the server itself answered — including a 404
 * for an entry that no longer exists — does.
 */
fun shouldCountUpdateFailure(error: Throwable): Boolean = when (error) {
    // The user navigated away or the job was stopped.
    is CancellationException -> false
    // The extension is gone. Already visible on its own, and a missing source can't be
    // blamed for failing to answer.
    is SourceNotFoundException -> false
    // The server answered, so it is the source talking: 404 and 410 mean the entry is gone,
    // the rest mean the source is refusing or broken. Both are worth noticing.
    is HttpException -> true
    // Never reached the server.
    is UnknownHostException,
    is SocketTimeoutException,
    is InterruptedIOException,
    is ConnectException,
    is NoRouteToHostException,
    is SSLException,
    is SocketException,
    -> false
    // Parse failures and the like: the source answered with something unusable.
    else -> true
}

/** True for the answers that mean this entry is gone rather than the source being down. */
fun isEntryGone(error: Throwable): Boolean =
    error is HttpException && (error.code == HTTP_NOT_FOUND || error.code == HTTP_GONE)

private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
