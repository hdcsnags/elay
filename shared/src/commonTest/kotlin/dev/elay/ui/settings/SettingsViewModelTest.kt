package dev.elay.ui.settings

import dev.elay.data.remote.SessionState
import dev.elay.domain.model.UserId
import dev.elay.ui.auth.FakeAuthGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SettingsViewModel] owns exactly one behavior (brief §3): sign out. The teardown lifecycle it
 * triggers — [dev.elay.di.UserSessionGraph] tearing the user scope down and App swapping back to
 * sign-in — is already covered by [dev.elay.di.UserSessionGraphTest]'s
 * `signOutTearsDownTheUserScope`; this test only needs to prove the one call reaches the gateway
 * and that it runs on the injected scope (never a screen-scoped one — brief §5's reasoning).
 */
class SettingsViewModelTest {
    @Test
    fun signOutCallsTheAuthGateway() =
        runTest {
            val auth = FakeAuthGateway(initial = SessionState.SignedIn(UserId("user-1")))
            val viewModel = SettingsViewModel(auth, backgroundScope)

            viewModel.signOut()
            runCurrent()

            assertEquals(1, auth.signOutCalls)
        }

    @Test
    fun signOutFlipsTheSessionToSignedOut() =
        runTest {
            val auth = FakeAuthGateway(initial = SessionState.SignedIn(UserId("user-1")))
            val viewModel = SettingsViewModel(auth, backgroundScope)

            viewModel.signOut()
            runCurrent()

            assertEquals(SessionState.SignedOut, auth.session.first())
        }
}
