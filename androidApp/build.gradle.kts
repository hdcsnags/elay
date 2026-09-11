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
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
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
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
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
