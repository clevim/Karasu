package eu.kanade.tachiyomi.ui.setting.track

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.ui.base.activity.BaseThemedActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import uy.kohesive.injekt.injectLazy
import karasu.i18n.MR
import karasu.util.lang.getString

abstract class BaseOAuthLoginActivity : BaseThemedActivity() {

    internal val trackManager: TrackManager by injectLazy()

    abstract fun handleResult(data: Uri?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = ProgressBar(this)
        setContentView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        handleResult(intent.data)
    }

    /**
     * Finishes an OAuth login and says out loud what happened.
     *
     * These flows used to drop the result on the floor: a token the tracker rejected, a response
     * that would not parse and a request that never landed all ended the same way — back on a
     * settings screen that only says "not logged in", with the reason left in logcat. Whoever just
     * typed their password is standing right here, so the failure is theirs to read.
     *
     * Reaching this at all is itself the answer to half the question: it means the callback came
     * back to *this* app, rather than to another Tachiyomi fork claiming the same URL.
     */
    internal fun finishLogin(service: TrackService, login: suspend () -> Boolean) {
        lifecycleScope.launchIO {
            val error = try {
                if (login()) null else MR.strings.unknown_error.getString(this@BaseOAuthLoginActivity)
            } catch (e: Exception) {
                e.message ?: MR.strings.unknown_error.getString(this@BaseOAuthLoginActivity)
            }
            withUIContext {
                if (error == null) {
                    toast(MR.strings.successfully_logged_in)
                } else {
                    service.logout()
                    val name = service.nameRes().getString(this@BaseOAuthLoginActivity)
                    toast("${getString(MR.strings.not_logged_into_, name)}: $error", Toast.LENGTH_LONG)
                }
                returnToSettings()
            }
        }
    }

    /** The tracker sent us back without a token at all — a denied prompt, or a mangled callback. */
    internal fun cancelLogin(service: TrackService) {
        service.logout()
        toast(getString(MR.strings.not_logged_into_, service.nameRes().getString(this)))
        returnToSettings()
    }

    internal fun returnToSettings() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finishAfterTransition()
    }
}
