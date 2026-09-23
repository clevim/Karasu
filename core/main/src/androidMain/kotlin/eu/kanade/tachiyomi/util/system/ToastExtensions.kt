package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import dev.icerock.moko.resources.StringResource
import karasu.util.lang.getString

/**
 * Display a toast in this context.
 *
 * @param resource the text resource.
 * @param duration the duration of the toast. Defaults to short.
 */
fun Context.toast(@StringRes resource: Int, duration: Int = Toast.LENGTH_SHORT) = onMain {
    Toast.makeText(this, resource, duration).show()
}

/**
 * Display a toast in this context.
 *
 * @param resource the text resource.
 * @param duration the duration of the toast. Defaults to short.
 */
fun Context.toast(resource: StringResource, duration: Int = Toast.LENGTH_SHORT) {
    toast(getString(resource), duration)
}

/**
 * Display a toast in this context.
 *
 * @param text the text to display.
 * @param duration the duration of the toast. Defaults to short.
 */
fun Context.toast(text: String?, duration: Int = Toast.LENGTH_SHORT) = onMain {
    Toast.makeText(this, text.orEmpty(), duration).show()
}

private val mainHandler = Handler(Looper.getMainLooper())

/**
 * A Toast built off the main thread throws, and background jobs call this to report an error they
 * already survived — losing the whole job to the report is never what the caller meant.
 */
private fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}
