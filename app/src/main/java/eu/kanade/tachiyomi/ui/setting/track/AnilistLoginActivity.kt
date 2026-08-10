package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri

class AnilistLoginActivity : BaseOAuthLoginActivity() {

    override fun handleResult(data: Uri?) {
        // Anilist uses the implicit flow, so the token arrives in the fragment. The trailing "&"
        // the old pattern required is not guaranteed to be there: it is absent when access_token
        // is the last parameter, and then a perfectly good token was read as no token at all.
        val token = data?.fragment
            ?.split("&")
            ?.firstOrNull { it.startsWith("access_token=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotEmpty() }

        if (token == null) {
            cancelLogin(trackManager.aniList)
        } else {
            finishLogin(trackManager.aniList) { trackManager.aniList.login(token) }
        }
    }
}
