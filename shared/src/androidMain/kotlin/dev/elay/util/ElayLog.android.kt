package dev.elay.util

import android.util.Log

/** Android `actual`: routes through [android.util.Log], gated by [ElayLogConfig.enabled] (which
 * `ElayApp.onCreate` sets from `BuildConfig.DEBUG` — release builds never flip it, so `Log.d`/
 * `Log.w` are never called and the lazy [message] lambda is never invoked). */
actual object ElayLog {
    actual fun d(
        tag: String,
        message: () -> String,
    ) {
        if (ElayLogConfig.enabled) Log.d("Elay$tag", message())
    }

    actual fun w(
        tag: String,
        message: () -> String,
    ) {
        if (ElayLogConfig.enabled) Log.w("Elay$tag", message())
    }
}
