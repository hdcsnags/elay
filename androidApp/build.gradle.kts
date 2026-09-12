import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Backend endpoints for DI (ADR-002). Defaults target the LOCAL Docker Supabase
// stack from the Android emulator (10.0.2.2 = host loopback); the anon key is
// the public supabase-local demo constant, identical for every local install —
// not a secret. Real/hosted values, if ever, come from local.properties
// (gitignored) — never committed (AGENTS.md rule).
val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
val supabaseUrl = localProps.getProperty("SUPABASE_URL") ?: "http://10.0.2.2:54321"
val rsvpLinkBase = localProps.getProperty("RSVP_LINK_BASE") ?: "http://10.0.2.2:54321/functions/v1/rsvp"
val supabaseAnonKey =
    localProps.getProperty("SUPABASE_ANON_KEY")
        ?: (
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9." +
                "CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"
        )

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.compose.foundation)
    implementation(libs.ktor.client.okhttp)
}

android {
    namespace = "dev.elay"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    compileSdkMinor =
        libs.versions.android.compileSdkMinor
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.elay.app"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        // Stage 5 release lane: one source of truth in libs.versions.toml.
        val elayVersion =
            libs.versions.elay.version
                .get()
        val (vMajor, vMinor, vPatch) = elayVersion.split(".").map { it.toInt() }
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = elayVersion
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "RSVP_LINK_BASE", "\"$rsvpLinkBase\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        // Stage 5 release lane: signing material lives OUTSIDE the repo
        // (%USERPROFILE%\.elay\keystore.properties -> elay-release.jks). When absent
        // (CI, clean clones), the signingConfig is omitted entirely and bundleRelease
        // still builds (unsigned) -- CI asserts shape, signing is a local gate.
        val ksProps = Properties()
        val ksFile = file(System.getProperty("user.home")).resolve(".elay/keystore.properties")
        if (ksFile.exists()) {
            ksFile.inputStream().use { ksProps.load(it) }
            val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            val missing = required.filter { ksProps.getProperty(it).isNullOrBlank() }
            // Pre-gate F13: a PRESENT-but-incomplete keystore.properties must fail with a
            // named message, not crash every task via file(null).
            require(missing.isEmpty()) {
                "keystore.properties exists but is missing: " + missing.joinToString() +
                    " (see docs/release.md)"
            }
            create("release") {
                storeFile = file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            // MASVS RESILIENCE/CODE (Stage 5 items 3-4): shrink + obfuscate, and refuse to
            // build a release whose backend endpoint or RSVP link base is cleartext/loopback.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// Stage 5 hardening item 3: a release artifact must never carry a cleartext or loopback
// backend. Fails the build BEFORE packaging when the configured values are unfit.
val assertReleaseEndpoints by tasks.registering {
    // Configuration-cache-safe: plain values captured at configuration time; the action
    // references no script objects (Gradle cc requirement, found on the first release build).
    val url = supabaseUrl
    val rsvpBase = rsvpLinkBase
    val allowInsecure = providers.gradleProperty("elay.allowInsecureRelease").orNull == "true"
    doFirst {
        val loopback = listOf("127.0.0.1", "localhost", "10.0.2.2")
        fun assertShippable(
            name: String,
            value: String,
        ) {
            require(value.startsWith("https://") && loopback.none { value.contains(it) }) {
                "Release build requires an https, non-loopback " + name + " from local.properties (got '" +
                    value + "'). For the local minified smoke gate ONLY, pass -Pelay.allowInsecureRelease=true."
            }
        }
        if (allowInsecure) {
            logger.lifecycle(
                "WARNING: elay.allowInsecureRelease=true — building a RELEASE against '" + url +
                    "'. LOCAL MINIFIED SMOKE ONLY; never distribute this artifact.",
            )
        } else {
            assertShippable("SUPABASE_URL", url)
            assertShippable("RSVP_LINK_BASE", rsvpBase)
        }
    }
}
tasks.matching { it.name in setOf("bundleRelease", "assembleRelease") }.configureEach {
    dependsOn(assertReleaseEndpoints)
}
