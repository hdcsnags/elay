package dev.elay.util

/**
 * Release-build-safe logging facade for the seven `ELAY *` diagnostic printlns (contracts/
 * stage5-retention-hardening.md §A / MASVS checklist item — "the seven ELAY printlns route
 * through release-stripped ElayLog"). The requirement is honest and simple: release builds must
 * emit NOTHING.
 *
 * The shared module has no `BuildConfig` of its own (that's an Android/Gradle-only generated
 * artifact), so [ElayLogConfig.enabled] is the portable stand-in — a plain commonMain flag that
 * defaults to `false` (silent) so any platform that forgets to wire it up fails safe rather than
 * leaking diagnostics into a release build. `androidApp`'s `ElayApp.onCreate` is the one
 * production call site that flips it, from its own generated `BuildConfig.DEBUG` (release
 * manifests never set it true).
 *
 * `message` is a lazy lambda specifically so a disabled logger never pays for the string
 * interpolation/exception formatting at the call site — that work is skipped entirely, not just
 * its output.
 */
expect object ElayLog {
    fun d(
        tag: String,
        message: () -> String,
    )

    fun w(
        tag: String,
        message: () -> String,
    )
}

/**
 * Global on/off switch for [ElayLog]. `commonMain`-owned (see [ElayLog]'s kdoc for why) and
 * `false` by default. Only `androidApp`'s `ElayApp.onCreate` (debug-build BuildConfig read) ever
 * sets this `true` in production wiring; nothing in `commonMain` or `iosMain` does.
 */
object ElayLogConfig {
    var enabled: Boolean = false
}
