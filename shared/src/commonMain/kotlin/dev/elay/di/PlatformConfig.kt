package dev.elay.di

/**
 * Per-platform default Supabase endpoint (brief §6). `androidApp` already gets its own
 * BuildConfig-sourced values from Gradle (`androidApp/build.gradle.kts`) and passes them to
 * [AppGraph] directly without going through this — the android `actual` exists only to satisfy
 * the expect/actual contract and to give any shared `androidMain` code (previews, tooling) the
 * same local-stack default. iOS has no BuildConfig-equivalent, so its `actual` is the one
 * production call site that actually reads this (`MainViewController`).
 */
expect object PlatformConfig {
    val supabaseUrl: String
    val supabaseAnonKey: String
}
