package dev.elay

import androidx.compose.ui.window.ComposeUIViewController

// Conventional iOS entry-point name; referenced from iosApp Swift as MainViewController()
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
fun MainViewController() = ComposeUIViewController { App() }
