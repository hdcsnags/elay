package dev.elay.util

/** iOS `actual`: no `Log`-equivalent in Kotlin/Native's stdlib, so this falls back to `println`
 * (Console-visible under the simulator/Instruments) — gated the same way as the Android actual,
 * by [ElayLogConfig.enabled]. iOS runtime is CI-only for Phase 0 (matches `PlatformConfig.ios.kt`'s
 * note) and nothing in `iosMain` sets [ElayLogConfig.enabled] `true`, so this stays silent until a
 * future iOS entry point wires up its own debug/release signal. */
actual object ElayLog {
    actual fun d(
        tag: String,
        message: () -> String,
    ) {
        if (ElayLogConfig.enabled) println("D/Elay$tag: ${message()}")
    }

    actual fun w(
        tag: String,
        message: () -> String,
    ) {
        if (ElayLogConfig.enabled) println("W/Elay$tag: ${message()}")
    }
}
