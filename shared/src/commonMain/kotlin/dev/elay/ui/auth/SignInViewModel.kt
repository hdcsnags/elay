package dev.elay.ui.auth

import dev.elay.data.remote.AuthGateway
import dev.elay.util.ElayLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A tiny "create an account" capability, kept separate from the frozen [AuthGateway]
 * (contracts/phase1-planner.md's `data/remote` boundary has no sign-up method, and this seat's
 * brief forbids touching `data/`) rather than adding one there. [dev.elay.di.AppGraph] wires the
 * real implementation straight over the same `SupabaseClient` it already builds for
 * [AuthGateway] — local Supabase auto-confirms signups, so it needs nothing beyond the one call.
 * Tests hand [SignInViewModel] a plain lambda.
 */
fun interface AccountCreator {
    suspend fun signUp(
        email: String,
        password: String,
    ): Result<Unit>
}

/** Which action Sign in submits — both actions live on one screen (brief §3). */
enum class SignInMode { SignIn, CreateAccount }

/** Sign-in surface state — calm copy only, never a red-banner error style (spec §2). */
data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val mode: SignInMode = SignInMode.SignIn,
    val isLoading: Boolean = false,
    val message: String? = null,
)

/**
 * Plain, testable ViewModel (no android.lifecycle dependency, matching seat C1's ViewModels):
 * owns the email/password fields and drives [AuthGateway.signInWithEmail] or
 * [AccountCreator.signUp] on submit. A successful account creation is followed by a sign-in
 * (a brand-new local account has no session yet); a failed one never attempts to sign in.
 */
class SignInViewModel(
    private val authGateway: AuthGateway,
    private val accountCreator: AccountCreator,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun onEmailChanged(email: String) {
        _state.update { it.copy(email = email, message = null) }
    }

    fun onPasswordChanged(password: String) {
        _state.update { it.copy(password = password, message = null) }
    }

    fun toggleMode() {
        _state.update {
            it.copy(
                mode = if (it.mode == SignInMode.SignIn) SignInMode.CreateAccount else SignInMode.SignIn,
                message = null,
            )
        }
    }

    fun submit() {
        val snapshot = _state.value
        if (snapshot.isLoading) return
        val email = snapshot.email.trim()
        val password = snapshot.password
        if (email.isEmpty() || password.isEmpty()) {
            _state.update { it.copy(message = "Add your email and password to continue.") }
            return
        }
        _state.update { it.copy(isLoading = true, message = null) }
        scope.launch {
            val result = submitResult(snapshot.mode, email, password)
            _state.update {
                it.copy(isLoading = false, message = result.exceptionOrNull()?.let(::calmMessageFor))
            }
        }
    }

    private suspend fun submitResult(
        mode: SignInMode,
        email: String,
        password: String,
    ): Result<Unit> =
        when (mode) {
            SignInMode.SignIn -> authGateway.signInWithEmail(email, password).map { }
            SignInMode.CreateAccount ->
                accountCreator.signUp(email, password).fold(
                    onSuccess = { authGateway.signInWithEmail(email, password).map { } },
                    onFailure = { Result.failure(it) },
                )
        }
}

/** Calm, specific-enough-to-act-on copy — never a raw provider error or a red banner (spec §2). */
private fun calmMessageFor(error: Throwable): String {
    // Diagnosability (concierge 2026-09-12): the calm copy must never mean silent failure —
    // the underlying cause goes to the platform log for adb/Console diagnosis (debug builds
    // only — release-stripped via ElayLog, stage5 §A MASVS checklist).
    ElayLog.w("Auth") { authFailureLogMessage(error) }
    return calmCopyFor(error)
}

/** Stage5 §A: class name only, never [Throwable.message] — the message can carry the email the
 * user just typed. Extracted so its no-message-leak behavior is directly testable without going
 * through [ElayLog]. */
internal fun authFailureLogMessage(error: Throwable): String = "ELAY auth failure: ${error::class.simpleName}"

private fun calmCopyFor(error: Throwable): String =
    when {
        error.message?.contains("Invalid login credentials", ignoreCase = true) == true ->
            "That email and password don't match. Try again, or create an account."
        error.message?.contains("already registered", ignoreCase = true) == true ||
            error.message?.contains("already exists", ignoreCase = true) == true ->
            "That email already has an account — try signing in instead."
        else -> "Couldn't reach Elay just now. Please try again in a moment."
    }
