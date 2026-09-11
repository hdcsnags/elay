package dev.elay.data.remote.impl

import dev.elay.data.remote.AuthGateway
import dev.elay.data.remote.SessionState
import dev.elay.domain.model.UserId
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ADR-002 thin adapter over supabase-kt Auth. Phase 1 is email/password only
 * (local test accounts) — no OAuth/OTP surface exposed here.
 * Constructed with a client supplied by DI — never builds its own.
 */
class SupabaseAuthGateway(
    private val client: SupabaseClient,
) : AuthGateway {
    override val session: Flow<SessionState> =
        client.auth.sessionStatus.map { it.toSessionState() }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ): Result<UserId> =
        runCatching {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            val userId =
                client.auth.currentUserOrNull()?.id
                    ?: error("signInWithEmail succeeded but no current user is available")
            UserId(userId)
        }

    override suspend fun signOut() {
        client.auth.signOut()
    }

    override suspend fun refreshSession(): Result<Unit> = runCatching { client.auth.refreshCurrentSession() }
}

private fun SessionStatus.toSessionState(): SessionState =
    when (this) {
        is SessionStatus.Authenticated ->
            session.user?.id?.let { SessionState.SignedIn(UserId(it)) } ?: SessionState.SignedOut
        is SessionStatus.Initializing -> SessionState.Refreshing
        else -> SessionState.SignedOut
    }
