package eu.kanade.tachiyomi.data.track.kitsu

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuOAuth
import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class KitsuInterceptor(val kitsu: Kitsu) : Interceptor {

    private val json: Json by injectLazy()

    /**
     * OAuth object used for authenticated requests.
     */
    private var oauth: KitsuOAuth? = kitsu.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val currAuth = refreshIfExpired(chain)

        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer ${currAuth.accessToken}")
            .header("User-Agent", "clevim/Karasu/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .header("Accept", "application/vnd.api+json")
            .header("Content-Type", "application/vnd.api+json")
            .build()

        return chain.proceed(authRequest)
    }

    /**
     * The auth to sign with, refreshed first if it has expired.
     *
     * Synchronized because a library update fires tracking requests in parallel: without the lock
     * every expired one starts its own refresh, and a refresh token is spent when it is used, so
     * the request that wins invalidates the tokens the others are still holding.
     */
    private fun refreshIfExpired(chain: Interceptor.Chain): KitsuOAuth = synchronized(this) {
        val currAuth = oauth ?: throw IOException("Kitsu: not authenticated")
        if (!currAuth.isExpired()) return@synchronized currAuth

        // Nullable because Kitsu does not promise one. Read here rather than at the top of
        // intercept, so a token that never needs refreshing is not held hostage by its absence.
        val refreshToken = currAuth.refreshToken
            ?: throw IOException("Kitsu: login has expired and there is no refresh token")

        val response = chain.proceed(KitsuApi.refreshTokenRequest(refreshToken))
        if (!response.isSuccessful) {
            response.close()
            // Signing with the token we already know is expired only turns this into a 401 that
            // blames the request, and hides that it was the refresh that failed.
            throw IOException("Kitsu: failed to refresh the account token")
        }
        newAuth(json.decodeFromString<KitsuOAuth>(response.body.string()))
        return@synchronized oauth!!
    }

    fun newAuth(oauth: KitsuOAuth?) {
        this.oauth = oauth
        kitsu.saveToken(oauth)
    }
}
