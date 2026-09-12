package dev.elay

import androidx.compose.ui.window.ComposeUIViewController
import dev.elay.data.local.databaseBuilder
import dev.elay.di.AppGraph
import dev.elay.di.PlatformConfig
import dev.elay.di.createAppScope

private val appGraph: AppGraph by lazy {
    AppGraph(
        supabaseUrl = PlatformConfig.supabaseUrl,
        supabaseAnonKey = PlatformConfig.supabaseAnonKey,
        databaseBuilderFactory = { accountKey -> databaseBuilder(accountKey) },
        appScope = createAppScope(),
    )
}

// Conventional iOS entry-point name; referenced from iosApp Swift as MainViewController()
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
fun MainViewController() = ComposeUIViewController { App(appGraph) }
