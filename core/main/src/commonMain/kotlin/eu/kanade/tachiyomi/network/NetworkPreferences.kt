package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore

class NetworkPreferences(
    private val preferenceStore: PreferenceStore,
    private val verboseLogging: Boolean,
) {

    fun verboseLogging() = preferenceStore.getBoolean("verbose_logging", verboseLogging)

    fun dohProvider() = preferenceStore.getInt("doh_provider", -1)

    fun defaultUserAgent() = preferenceStore.getString("default_user_agent", DEFAULT_USER_AGENT)

    /** Base URL of a FlareSolverr instance. Blank disables the fallback. */
    fun flareSolverrUrl() = preferenceStore.getString("flaresolverr_url", "")

    /** host -> User-Agent that solved it. Bookkeeping tied to cookies on this device, so it is
     * app state rather than a preference and must stay out of backups. */
    fun flareSolverrUserAgents() =
        preferenceStore.getString(Preference.appStateKey("flaresolverr_user_agents"), "")

    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    }
}
