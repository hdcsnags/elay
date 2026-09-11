<!--
  researcher: Claude Sonnet 5
  date: 2026-09-11
  purpose: Gate 0b scaffold pins for ELAY (KMP + CMP + Room 3 + supabase-kt)
  method: raw maven-metadata.xml / official APIs fetched directly (not search-index
          summaries, which were found stale/unreliable during this pass)
-->

| # | Artifact | Coordinates | Latest stable | Source | Notes |
|---|---|---|---|---|---|
| 1 | Kotlin | org.jetbrains.kotlin.multiplatform | 2.4.20 (2026-09-07) | repo1.maven.org/.../org.jetbrains.kotlin.jvm.gradle.plugin/maven-metadata.xml | matches expectation |
| 2 | AGP | com.android.application | 9.4.0 (2026-09-01) | dl.google.com/.../com/android/tools/build/gradle + developer.android.com/build/releases/gradle-plugin | 9.5.0-alpha05 exists but not stable; **min Gradle 9.6.0** |
| 3 | Gradle | gradle.org distribution | 9.7.1 (built 2026-08-19) | services.gradle.org/versions/current | 9.8.0-rc-1 is an active RC, not stable |
| 4 | Compose Multiplatform plugin | org.jetbrains.compose | 1.12.0 (2026-08-24) | repo1.maven.org/.../org/jetbrains/compose/compose-gradle-plugin/maven-metadata.xml | 1.13.0-alpha01 is newest overall but alpha |
| 5 | Compose Multiplatform material3 | org.jetbrains.compose.material3:material3 | 1.9.0 (2025-10-13) | repo1.maven.org/.../material3/maven-metadata.xml | **Stalled**: 1.10.0/1.11.0/1.12.0/1.13.0 lines are alpha-only so far — material3 trails the CMP plugin significantly |
| 6 | Navigation Compose (JB) | org.jetbrains.androidx.navigation:navigation-compose | 2.9.2 (2026-02-10) | repo1.maven.org/.../navigation-compose/maven-metadata.xml | 2.10.0-beta01 is newest, not stable |
| 7 | Lifecycle ViewModel Compose (JB) | org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose | 2.11.0 (2026-07-13) | repo1.maven.org/.../lifecycle-viewmodel-compose/maven-metadata.xml | Now STABLE — resolves prior sheet's "2.11.0-rc01 UNVERIFIED" |
| 8 | KSP | com.google.devtools.ksp | 2.3.12 (2026-09-09) | repo1.maven.org/.../com.google.devtools.ksp.gradle.plugin/maven-metadata.xml | **Caution**: KSP decoupled its version scheme from Kotlin's at 2.3.0. 2.3.12's own POM pins `kotlin-stdlib 2.3.20`, i.e. no KSP build yet targets Kotlin 2.4.20. Verify compatibility before pinning both together. |
| 9 | Room 3 | androidx.room3:room3-runtime / room3-compiler | 3.0.3 | dl.google.com/.../androidx/room3/room3-runtime(-compiler)/maven-metadata.xml | 3.1.0-alpha01 is newest, not stable; satisfies ≥3.0.3 |
| 10 | SQLite bundled | androidx.sqlite:sqlite-bundled | 2.7.1 | dl.google.com/.../androidx/sqlite/sqlite-bundled/maven-metadata.xml | 2.8.0-alpha01 is newest, not stable |
| 11 | kotlinx-serialization-json | org.jetbrains.kotlinx:kotlinx-serialization-json | 1.11.0 (2026-04-09) | repo1.maven.org/.../kotlinx-serialization-json/maven-metadata.xml | 1.12.0-RC is newest, not stable |
| 12 | kotlinx-coroutines-core | org.jetbrains.kotlinx:kotlinx-coroutines-core | 1.11.0 (2026-05-07) | repo1.maven.org/.../kotlinx-coroutines-core/maven-metadata.xml | |
| 13 | kotlinx-datetime | org.jetbrains.kotlinx:kotlinx-datetime | 0.8.0 (2026-05-07) | repo1.maven.org/.../kotlinx-datetime/maven-metadata.xml | ignore the `-0.6.x-compat` variant tag |
| 14 | Ktor client core | io.ktor:ktor-client-core | 3.5.2 (2026-07-31) | repo1.maven.org/.../ktor-client-core/maven-metadata.xml | |
| 15 | supabase-kt | io.github.jan-tennert.supabase (bom, auth-kt, postgrest-kt, realtime-kt) | 3.8.0 (2026-08-26) | repo1.maven.org/.../io/github/jan-tennert/supabase/{bom,auth-kt,postgrest-kt}/maven-metadata.xml | auth-kt 3.8.0's POM depends on **Ktor 3.5.1** (one patch behind our 3.5.2 pin — compatible) |
| 16 | Detekt gradle plugin | io.gitlab.arturbosch.detekt | 1.23.8 (2025-02-21) | repo1.maven.org/.../detekt-gradle-plugin/maven-metadata.xml | No 1.x release since Feb 2025. Detekt 2.0 is in development under new groupId `dev.detekt` (2.0.0-alpha.6, 2026-08-04) — not stable |
| 17 | ktlint gradle integration | org.jlleitschuh.gradle:ktlint-gradle | 14.2.0 (2026-03-12) | plugins.gradle.org/m2/org/jlleitschuh/gradle/ktlint-gradle/maven-metadata.xml | Not on Maven Central — published only to the Gradle Plugin Portal's backing repo |
| 18 | Supabase CLI | supabase/cli | 2.117.0 | github.com/supabase/cli (releases/latest) + npm registry `supabase@latest` | matches your npx-installed 2.117.0; 2.118.0-beta.x are pre-releases |

```toml
[versions]
kotlin = "2.4.20"
agp = "9.4.0"
compose-multiplatform = "1.12.0"
compose-material3 = "1.9.0"
navigation-compose = "2.9.2"
lifecycle-viewmodel-compose = "2.11.0"
ksp = "2.3.12"
room3 = "3.0.3"
sqlite-bundled = "2.7.1"
kotlinx-serialization-json = "1.11.0"
kotlinx-coroutines = "1.11.0"
kotlinx-datetime = "0.8.0"
ktor = "3.5.2"
supabase-bom = "3.8.0"
detekt = "1.23.8"
ktlint-gradle = "14.2.0"

[libraries]
androidx-room3-runtime = { group = "androidx.room3", name = "room3-runtime", version.ref = "room3" }
androidx-room3-compiler = { group = "androidx.room3", name = "room3-compiler", version.ref = "room3" }
androidx-sqlite-bundled = { group = "androidx.sqlite", name = "sqlite-bundled", version.ref = "sqlite-bundled" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-serialization-json" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-datetime = { group = "org.jetbrains.kotlinx", name = "kotlinx-datetime", version.ref = "kotlinx-datetime" }
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
supabase-bom = { group = "io.github.jan-tennert.supabase", name = "bom", version.ref = "supabase-bom" }
jb-navigation-compose = { group = "org.jetbrains.androidx.navigation", name = "navigation-compose", version.ref = "navigation-compose" }
jb-lifecycle-viewmodel-compose = { group = "org.jetbrains.androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle-viewmodel-compose" }

[plugins]
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
androidApplication = { id = "com.android.application", version.ref = "agp" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint-gradle" }
```
