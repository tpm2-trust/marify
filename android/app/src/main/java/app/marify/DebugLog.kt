package app.marify

import android.util.Log

/**
 * Diagnostic logging that exists in debug builds only. Session steps,
 * fragment sizes and machine names help when a link misbehaves; they have no
 * place in the log of a release build. Warnings and errors still use [Log].
 */
internal inline fun debugLog(tag: String, message: () -> String) {
    if (BuildConfig.DEBUG) Log.d(tag, message())
}
