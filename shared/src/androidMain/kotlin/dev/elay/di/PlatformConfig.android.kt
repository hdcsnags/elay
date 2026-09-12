package dev.elay.di

/**
 * Same local-Docker-stack defaults as `androidApp/build.gradle.kts`'s BuildConfig fallback.
 * `androidApp`'s own `ElayApp` builds [AppGraph] straight from its generated `BuildConfig`
 * fields, never reading this — this `actual` exists only to satisfy the expect/actual contract
 * declared in commonMain.
 */
actual object PlatformConfig {
    actual val supabaseUrl: String = "http://10.0.2.2:54321"
    actual val supabaseAnonKey: String =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9." +
            "CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"
}
