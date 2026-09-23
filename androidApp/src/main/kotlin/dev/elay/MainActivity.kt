package dev.elay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Stage 6 LEAD-0: must precede super.onCreate. Deliberately NOT wired to
        // setKeepOnScreenCondition — App() renders its own LoadingScreen during session
        // refresh, and holding the system splash across a slow token refresh is the
        // named cold-start regression in contracts/stage6-design-language.md.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appGraph = (application as ElayApp).appGraph
        setContent {
            App(appGraph)
        }
    }
}
