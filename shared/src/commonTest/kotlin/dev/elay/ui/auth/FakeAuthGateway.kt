package dev.elay.ui.auth

import dev.elay.data.remote.AuthGateway
import dev.elay.data.remote.SessionState
import dev.elay.domain.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Scripted fake [AuthGateway] — no network, session state and sign-in result under test
 * control. Shared by [SignInViewModelTest] and the `di` package's user-scope-lifecycle tests
 * (both need the same seam: a session [Flow] the test can push values into).
 */
class FakeAuthGateway(
    initial: SessionState = SessionState.SignedOut,
) : AuthGateway {
    private val _session = MutableStateFlow(initial)
    override val session: Flow<SessionState> = _session

    var signInResult: Result<UserId> = Result.success(UserId("user-1"))
    val signInCalls = mutableListOf<Pair<String, String>>()

    var refreshCalls: Int = 0
        private set
    var signOutCalls: Int = 0
        private set

    fun setSession(state: SessionState) {
        _session.value = state
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ): Result<UserId> {
        signInCalls += email to password
        return signInResult
    }

    override suspend fun signOut() {
        signOutCalls++
        _session.value = SessionState.SignedOut
    }

    override suspend fun refreshSession(): Result<Unit> {
        refreshCalls++
        return Result.success(Unit)
    }
}
