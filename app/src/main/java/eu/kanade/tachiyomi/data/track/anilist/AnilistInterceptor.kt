package eu.kanade.tachiyomi.data.track.anilist

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.anilist.dto.ALOAuth
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

class AnilistInterceptor(private val anilist: Anilist, private var token: String?) : Interceptor {

    /**
     * OAuth object used for authenticated requests.
     *
     * Expires a minute early, so a request is never sent on a token that dies in flight. The
     * stored value is already epoch millis — [AnilistApi.createOAuth] builds it from the clock —
     * so the old conversion to milliseconds pushed expiry tens of thousands of years out and the
     * app could never notice a token going stale, only the 401s that follow.
     */
    private var oauth: ALOAuth? = null
        set(value) {
            field = value?.copy(expires = value.expires - 60 * 1000)
        }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        if (token.isNullOrEmpty()) {
            throw IOException("Anilist: not authenticated")
        }
        if (oauth == null) {
            oauth = anilist.loadOAuth()
        }
        // Read back through the field rather than from loadOAuth, so the minute the setter takes
        // off is applied. Null here is a stored token that would not parse: loadOAuth swallows
        // that and returns null, and the check has to happen before the token is used, not after.
        val currAuth = oauth ?: throw IOException("Anilist: no authentication token")

        // No refresh token in the implicit flow, so an expired token is the end of the session.
        if (currAuth.isExpired()) {
            anilist.logout()
            throw IOException("Anilist: login has expired")
        }

        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer ${currAuth.accessToken}")
            .header("User-Agent", "clevim/Karasu/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    /**
     * Called when the user authenticates with Anilist for the first time. Sets the refresh token
     * and the oauth object.
     */
    fun setAuth(oauth: ALOAuth?) {
        token = oauth?.accessToken
        this.oauth = oauth
        anilist.saveOAuth(oauth)
    }
}
