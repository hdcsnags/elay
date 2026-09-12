package dev.elay.di

import android.content.Context
import dev.elay.data.local.databaseBuilder
import kotlinx.coroutines.CoroutineScope

/**
 * `androidApp` composition helper. `androidApp` depends on `shared` as a plain
 * `implementation(projects.shared)` project dependency (no gradle-file changes in this seat's
 * scope), so `androidx.room3:room3-runtime` — an `implementation`, not `api`, dependency of
 * `shared`'s own `androidMain` — never reaches `androidApp`'s compile classpath. Building
 * [AppGraph] straight from `ElayApp` would therefore need to name `RoomDatabase.Builder` in
 * `androidApp` code, which fails to resolve there. Keeping that one Room-typed lambda inside
 * `shared` (this function's signature only exposes [Context]/[String]/[CoroutineScope]/
 * [AppGraph], all already resolvable to `androidApp`) avoids the problem entirely.
 */
fun createAndroidAppGraph(
    context: Context,
    supabaseUrl: String,
    supabaseAnonKey: String,
    appScope: CoroutineScope,
): AppGraph =
    AppGraph(
        supabaseUrl = supabaseUrl,
        supabaseAnonKey = supabaseAnonKey,
        databaseBuilderFactory = { accountKey -> databaseBuilder(context, accountKey) },
        appScope = appScope,
    )
