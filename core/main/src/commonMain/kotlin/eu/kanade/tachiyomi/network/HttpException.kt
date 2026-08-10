package eu.kanade.tachiyomi.network

/**
 * Exception that handles HTTP codes considered not successful by OkHttp.
 * Use it to have a standardized error message in the app across the extensions.
 *
 * @since extensions-lib 1.5
 * @param code [Int] the HTTP status code
 * @param body [String] the start of the response body, when there is one worth reading
 */
// @JvmOverloads keeps the single-argument constructor that already-compiled extensions call.
class HttpException @JvmOverloads constructor(val code: Int, val body: String? = null) :
    IllegalStateException("HTTP error $code" + if (body.isNullOrBlank()) "" else ": $body")
