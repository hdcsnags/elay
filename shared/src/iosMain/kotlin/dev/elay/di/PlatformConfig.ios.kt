package dev.elay.di

// NOTE: iOS runtime is CI-only for Phase 0 (brief §6) — before shipping a build that talks to
// anything but the local Docker Supabase stack from a simulator, these need to become the real
// hosted endpoint (or a build-time-injected value, mirroring androidApp's BuildConfig fields).
actual object PlatformConfig {
    // iOS simulator reaches the host loopback via 127.0.0.1, not Android's 10.0.2.2 alias.
    actual val supabaseUrl: String = "http://127.0.0.1:54321"
    actual val supabaseAnonKey: String =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9." +
            "CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"
}
