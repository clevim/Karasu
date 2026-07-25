package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class UserAgentInterceptor(
    private val defaultUserAgentProvider: () -> String,
    private val hostUserAgentProvider: (String) -> String? = { null },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // A cf_clearance cookie solved by FlareSolverr is only accepted alongside the User-Agent
        // that solved it, so a host bypassed that way has to keep getting that agent — even over
        // one a source set itself, which would otherwise invalidate the cookie we just paid for.
        val hostUserAgent = hostUserAgentProvider(originalRequest.url.host)

        return if (hostUserAgent != null) {
            val newRequest = originalRequest
                .newBuilder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", hostUserAgent)
                .build()
            chain.proceed(newRequest)
        } else if (originalRequest.header("User-Agent").isNullOrEmpty()) {
            val newRequest = originalRequest
                .newBuilder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", defaultUserAgentProvider())
                .build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(originalRequest)
        }
    }
}
