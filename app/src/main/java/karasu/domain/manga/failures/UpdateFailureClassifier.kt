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

/**
 * What a stored failure message actually means, in terms a reader can act on.
 *
 * Only the message survives in the database, not the exception, so this matches on text. That is
 * crude, but the alternative is storing a classification made at record time, which would freeze
 * the wording of failures already on disk and re-classify nothing when this list improves.
 */
enum class FailureCause {
    /** The source answered, but not in the shape the extension expects. */
    OUTDATED_EXTENSION,

    /** The source answered with a refusal or an error of its own. */
    SOURCE_REFUSED,

    /** Nothing matched, so the raw message is all there is. */
    UNKNOWN,
}

fun causeOf(message: String?): FailureCause {
    val text = message?.lowercase() ?: return FailureCause.UNKNOWN
    return when {
        // kotlinx-serialization complaining about the payload is the signature of a site that
        // changed its API while the extension still expects the old one.
        "serial name" in text ||
            "are required for type" in text ||
            "unexpected json token" in text ||
            "missing at path" in text -> FailureCause.OUTDATED_EXTENSION
        "http error" in text ||
            "cloudflare" in text ||
            "403" in text ||
            "503" in text -> FailureCause.SOURCE_REFUSED
        else -> FailureCause.UNKNOWN
    }
}

/** True for the answers that mean this entry is gone rather than the source being down. */
fun isEntryGone(error: Throwable): Boolean =
    error is HttpException && (error.code == HTTP_NOT_FOUND || error.code == HTTP_GONE)

private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
