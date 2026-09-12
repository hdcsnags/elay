package dev.elay.di

import androidx.room3.RoomDatabase
import dev.elay.data.local.ElayDatabase
import dev.elay.data.remote.AuthGateway
import dev.elay.data.remote.SessionState
import dev.elay.data.remote.impl.SupabaseAuthGateway
import dev.elay.data.remote.impl.SupabaseDataGateway
import dev.elay.data.repository.LocalFirstPlannerRepository
import dev.elay.sync.impl.OutboxSyncCoordinator
import dev.elay.ui.auth.AccountCreator
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Root manual-DI graph (brief §1) — no framework, built once at each platform's entry point
 * (`androidApp`'s `ElayApp`, iOS's `MainViewController`) and handed down to [dev.elay.App].
 *
 * Builds the [SupabaseClient] (Auth + Postgrest + Realtime) and the frozen [AuthGateway]
 * adapter over it, then hands the per-account build recipe to [UserSessionGraph] — see that
 * class for the actual signed-in/signed-out lifecycle, kept separate so it (not this class,
 * which touches a real network client) is what `commonTest` exercises.
 */
class AppGraph(
    supabaseUrl: String,
    supabaseAnonKey: String,
    databaseBuilderFactory: (accountKey: String) -> RoomDatabase.Builder<ElayDatabase>,
    appScope: CoroutineScope,
) {
    private val supabaseClient: SupabaseClient =
        createSupabaseClient(supabaseUrl = supabaseUrl, supabaseKey = supabaseAnonKey) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }

    val authGateway: AuthGateway = SupabaseAuthGateway(supabaseClient)

    /** Raw session stream — [dev.elay.App] gates the sign-in screen vs. the tab shell on this. */
    val sessionFlow: Flow<SessionState> = authGateway.session

    /** local Supabase auto-confirms signups (brief §3) — no email-verification step in Phase 1. */
    val accountCreator =
        AccountCreator { email, password ->
            runCatching {
                supabaseClient.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password
                }
            }
        }

    private val userSessionGraph =
        UserSessionGraph(sessionFlow = sessionFlow, appScope = appScope) { userId ->
            val database = databaseBuilderFactory(userId.value).build()
            val dataGateway = SupabaseDataGateway(supabaseClient)
            val syncCoordinator =
                OutboxSyncCoordinator(
                    outboxDao = database.outboxDao(),
                    writeDao = database.plannerWriteDao(),
                    dataGateway = dataGateway,
                    authGateway = authGateway,
                )
            val baseRepository =
                LocalFirstPlannerRepository(
                    goalDao = database.goalDao(),
                    milestoneDao = database.milestoneDao(),
                    taskDao = database.taskDao(),
                    captureDao = database.captureDao(),
                    timeBlockDao = database.timeBlockDao(),
                    stagingDao = database.plannerStagingDao(),
                    syncCoordinator = syncCoordinator,
                )
            val repository = ReplayTriggeringPlannerRepository(baseRepository, syncCoordinator, appScope)
            UserResources(UserGraph(userId, repository, syncCoordinator)) { database.close() }
        }

    /** Per-account repository + sync coordinator, or `null` while no user is signed in. */
    val userScope: StateFlow<UserGraph?> = userSessionGraph.userScope
}
