package dev.elay.di

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.CoroutineScope

/**
 * Process-lifetime [CoroutineScope] injection seam (brief §5, Gate 1 remainder: "auth work on
 * ViewModel scope (benign ForgottenCoroutineScopeException after successful sign-up)"). Sign-in
 * and sign-up today launch their network call on `rememberCoroutineScope()`, which App's own
 * screen-swap composable cancels the instant [dev.elay.data.remote.SessionState] flips and the
 * sign-in branch leaves composition mid-request. [AppGraph.appScope] already outlives every
 * screen (built once at the platform entry point, torn down never), so handing it down via this
 * `CompositionLocal` instead lets an in-flight auth call finish even if its screen is gone by the
 * time the network responds.
 *
 * Deliberately no silent fallback — unlike [LocalPlannerRepository] (a bare `@Preview` reasonably
 * wants fake data), a screen that reads this without [dev.elay.App] having provided it is a
 * wiring bug worth failing loudly on rather than quietly running auth ops on nothing.
 */
val LocalAppScope =
    compositionLocalOf<CoroutineScope> {
        error("LocalAppScope has no default — App() must provide it from AppGraph.appScope")
    }
