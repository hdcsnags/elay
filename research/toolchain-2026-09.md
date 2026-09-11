<!-- Research seat: Claude Sonnet via web search, 2026-09-10 (41 tool calls). Versions and dates are as cited by the
     seat; the conductor did NOT independently re-verify them. Every item marked UNVERIFIED must be checked on Maven
     Central / the release page before it is pinned. Re-run this research at Gate 0b. -->

# KMP + Compose Multiplatform toolchain sheet — greenfield start, September 2026

## 1. Core Kotlin / Compose / AGP / wizard
- **Kotlin (stable): 2.4.20** — released 2026-09-07. Plugin `org.jetbrains.kotlin.multiplatform`. https://kotlinlang.org/docs/releases.html · https://blog.jetbrains.com/kotlin/2026/09/kotlin-2-4-20-released/
- **Compose Multiplatform: 1.12.0** — August 2026. Plugin id `org.jetbrains.compose`. https://blog.jetbrains.com/kotlin/2026/08/compose-multiplatform-1-12-0/
- **Compose compiler plugin**: `org.jetbrains.kotlin.plugin.compose`, version = the Kotlin version. https://kotlinlang.org/docs/multiplatform/compose-compiler.html
- **Android Gradle Plugin: 9.4.0** — September 2026; requires Gradle ≥ 9.6.0, JDK ≥ 17, Build Tools ≥ 36.0.0. https://developer.android.com/build/releases/agp-9-4-0-release-notes · KMP+AGP9 migration: https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html
- **Gradle: 9.7.1** (≈ 2026-08-20). https://gradle.org/releases/
- **Layout / wizard**: since May 2026 the wizard (https://kmp.jetbrains.com/) generates `shared` (pure KMP library) + `composeApp` (UI, depends on shared) + `iosApp` (Xcode). https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/

## 2. Navigation, lifecycle, Material 3
- **Navigation 3 in CMP**: shipped alongside CMP 1.10.0 (Jan 2026): `org.jetbrains.androidx.navigation3:navigation3-ui`, `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3`, `org.jetbrains.compose.material3.adaptive:adaptive-navigation3`. Caveats: destination keys need explicit polymorphic kotlinx-serialization (no reflection on iOS/web); the navigation3-ui artifact is not yet published for non-JVM native/web targets — hand-roll `NavDisplay` there. https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html
- **Classic Navigation Compose for CMP (lower risk for iOS today)**: `org.jetbrains.androidx.navigation:navigation-compose`. https://kotlinlang.org/docs/multiplatform/compose-navigation.html
- **Lifecycle/ViewModel multiplatform**: `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` — latest seen 2.11.0-rc01 (≈ 2026-03-18). **UNVERIFIED as stable** — pin the last full stable on Maven Central. https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html
- **Material 3 in CMP**: `org.jetbrains.compose.material3:material3` on the CMP release train. **Material 3 Expressive is experimental and effectively Android-only** — not shared UI across iOS/desktop yet.

## 3. Data layer
- **Room KMP: 3.0.2** — Room 3.0 (package `androidx.room3`) GA 2026-07-01; KSP-only, coroutines-first; Android/iOS/JVM/JS/Wasm. `androidx.room3:room3-runtime`, `androidx.room3:room3-compiler` (KSP), `androidx.sqlite:sqlite-bundled:2.7.0`. https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html · https://developer.android.com/kotlin/multiplatform/room
- **SQLDelight (alternative): 2.2.1** (≈ 2026-03-16), `app.cash.sqldelight` — the safer choice if Room 3.0's newness worries you. https://github.com/sqldelight/sqldelight/releases
- **DataStore multiplatform**: `androidx.datastore:datastore-preferences-core` 1.2.1 (Preferences only; Android/iOS/Desktop, not Web). https://developer.android.com/kotlin/multiplatform/datastore
- **Ktor client: 3.5.2** (≈ August 2026), `io.ktor:ktor-client-core` + engine (`-okhttp` Android, `-darwin` iOS). https://github.com/ktorio/ktor/releases
- **kotlinx-serialization: 1.11.0** — **loosely verified** (tooling reported inconsistent dates). https://github.com/Kotlin/kotlinx.serialization/releases
- **kotlinx-coroutines: 1.11.0** (2026-05-08). **kotlinx-datetime: 0.8.0** (2026-05-07; `parseOrNull`, better Windows DST). **Coil 3: 3.6.2** (≈ June 2026), `io.coil-kt.coil3:coil-compose` + `coil-network-ktor3`.

## 4. Firebase on KMP
- **No official first-party Firebase KMP SDK** as of 2026-09 (long-standing feature request). De facto: **GitLive `dev.gitlive:firebase-*`** (auth, firestore, storage…) wrapping native SDKs; last release ≈ 2026-08-06, **exact version UNVERIFIED** — check mvnrepository `dev.gitlive`. https://github.com/GitLiveApp/firebase-kotlin-sdk
- **Firebase AI Logic on KMP**: no official artifact; community `firebase-ai-kmp` bridge. Google shipped **Google Gen AI SDK for Kotlin 1.0** (Gemini API client, JVM + Android) on 2026-09-03 — first-party Gemini client but not the full AI Logic feature set (no App Check).
- Note for ELAY: the brief argues **Supabase** over Firebase; for KMP that means `supabase-kt` (community-maintained, Ktor-based) — not covered by this sheet; research it if Gate 0a picks B.

## 5. iOS from Windows — the truth
Kotlin/Native Apple targets (`iosArm64`, `iosSimulatorArm64`, `iosX64`) **require Xcode** — Mac only; Windows cannot produce an iOS binary, no cross-toolchain workaround. https://kotlinlang.org/docs/native-target-support.html
Windows CAN: edit/refactor all shared Kotlin including iOS `expect/actual` (typechecks via klib metadata), build/run/test Android and JVM-desktop targets, run `commonTest`/JVM unit tests. NEEDS A MAC: compiling `iosApp`, simulator/device, CocoaPods, `kdoctor`.
**Compose Hot Reload** (stable since CMP 1.10.0) requires a JVM/desktop target — no help for iOS UI on Windows. Expo/EAS cloud builds, by contrast, DO produce iOS builds from Windows (relevant to Gate 0a).

## 6. Testing / tooling
- **KMP IDE plugin**: install from Marketplace; needs Android Studio Otter 2025.2.1+ / IntelliJ 2025.2.2+. Current stable Android Studio: **Quail 4 (2026.1.4)**, ≈ 2026-09-01. https://kotlinlang.org/docs/multiplatform/multiplatform-plugin-releases.html
- **Compose Preview for CMP**: unified common `@Preview` since CMP 1.10.0; renders in Android Studio.
- **Bundled JDK**: Android Studio bundles JDK 21 (JBR) since Ladybug; exact JBR build in Quail 4 **UNVERIFIED** (Help → About). This machine: `C:\Program Files\Android\Android Studio1\jbr`.
- **Detekt**: use stable 1.23.x (2.0 is alpha). **ktlint**: 1.8.0 core.

## 7. Windows-host gotchas (2026)
- MAX_PATH: enable long paths (registry `LongPathsEnabled=1`) and keep the project shallow; `git config core.longpaths true`.
- Gradle: `org.gradle.jvmargs=-Xmx4g`, `org.gradle.parallel=true`, configuration cache; Defender exclusions for the project, `.gradle`, `.konan`, AVDs.
- JDK: AGP 9.4 needs 17+; Studio's JBR (21) satisfies it; point `JAVA_HOME` at it for CLI builds.
- iOS work queues until the Mac arrives — exercise shared + Android fully now; keep `expect/actual` surfaces small.

## Ready-to-paste `gradle/libs.versions.toml` skeleton (re-verify UNVERIFIED lines first)
```toml
[versions]
kotlin = "2.4.20"
agp = "9.4.0"
composeMultiplatform = "1.12.0"
navigationCompose = "2.9.0"          # UNVERIFIED — confirm org.jetbrains.androidx.navigation latest
lifecycleViewmodel = "2.11.0-rc01"   # UNVERIFIED as stable
room = "3.0.2"
ksp = "2.4.20-1.0.30"                # UNVERIFIED — must match Kotlin 2.4.20 per KSP table
sqliteBundled = "2.7.0"
ktor = "3.5.2"
kotlinxSerialization = "1.11.0"      # UNVERIFIED
kotlinxCoroutines = "1.11.0"
kotlinxDatetime = "0.8.0"
coil = "3.6.2"
gitliveFirebase = "2.1.0"            # UNVERIFIED

[libraries]
androidx-room-runtime = { module = "androidx.room3:room3-runtime", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room3:room3-compiler", version.ref = "room" }
androidx-sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version.ref = "sqliteBundled" }
androidx-navigation-compose = { module = "org.jetbrains.androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
androidx-lifecycle-viewmodel-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycleViewmodel" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinxDatetime" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
coil-network-ktor3 = { module = "io.coil-kt.coil3:coil-network-ktor3", version.ref = "coil" }
gitlive-firebase-auth = { module = "dev.gitlive:firebase-auth", version.ref = "gitliveFirebase" }
gitlive-firebase-firestore = { module = "dev.gitlive:firebase-firestore", version.ref = "gitliveFirebase" }

[plugins]
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
androidApplication = { id = "com.android.application", version.ref = "agp" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```
