package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri

class MyAnimeListLoginActivity : BaseOAuthLoginActivity() {

    override fun handleResult(data: Uri?) {
        val code = data?.getQueryParameter("code")
        if (code == null) {
            cancelLogin(trackManager.myAnimeList)
        } else {
            finishLogin(trackManager.myAnimeList) { trackManager.myAnimeList.login(code) }
        }
    }
}
