package dev.elay.di

import dev.elay.data.remote.SessionState
import dev.elay.domain.model.UserId
import dev.elay.ui.auth.FakeAuthGateway
import dev.elay.ui.fake.FakePlannerRepository
import dev.elay.ui.fake.PlannerSeed
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UserSessionGraph] is [AppGraph]'s user-scope lifecycle engine, extracted precisely so it can
 * be driven here with a fake session flow and a fake `buildUserGraph` — no `SupabaseClient`, no
 * Room database, no network (the brief's own trap: "do NOT construct network calls in tests").
 */
class UserSessionGraphTest {
    private fun emptyRepository() =
        FakePlannerRepository(
            PlannerSeed(
                goals = emptyList(),
                milestones = emptyList(),
                tasks = emptyList(),
                captures = emptyList(),
                blocks = emptyList(),
            ),
        )

    @Test
    fun startsWithNoUserScopeWhileSignedOut() =
        runTest {
            val auth = FakeAuthGateway(initial = SessionState.SignedOut)
            val graph =
                UserSessionGraph(sessionFlow = auth.session, appScope = backgroundScope) { uid ->
                    UserResources(UserGraph(uid, emptyRepository(), FakeSyncCoordinator())) {}
                }
            runCurrent()

            assertNull(graph.userScope.value)
        }

    @Test
    fun signedInBuildsAUserGraphAndReplaysOnce() =
        runTest {
            val auth = FakeAuthGateway(initial = SessionState.SignedOut)
            val builtFor = mutableListOf<UserId>()
            val coordinator = FakeSyncCoordinator()
            val graph =
                UserSessionGraph(sessionFlow = auth.session, appScope = backgroundScope) { userId ->
                    builtFor += userId
                    UserResources(UserGraph(UserId("test-user"), emptyRepository(), coordinator)) {}
                }
            runCurrent()

            auth.setSession(SessionState.SignedIn(UserId("user-1")))
            runCurrent()

            assertEquals(listOf(UserId("user-1")), builtFor)
            assertNotNull(graph.userScope.value)
            assertEquals(1, coordinator.replayOnceCalls)
        }

    @Test
    fun signOutTearsDownTheUserScope() =
        runTest {
            val auth = FakeAuthGateway(initial = SessionState.SignedIn(UserId("user-1")))
            var closed = false
            val graph =
                UserSessionGraph(sessionFlow = auth.session, appScope = backgroundScope) { uid ->
                    UserResources(UserGraph(uid, emptyRepository(), FakeSyncCoordinator())) { closed = true }
                }
            runCurrent()
            assertNotNull(graph.userScope.value)

            auth.setSession(SessionState.SignedOut)
            runCurrent()

            assertNull(graph.userScope.value)
            assertTrue(closed, "signing out must close the previous UserResources")
        }

    @Test
    fun switchingUserClosesThePreviousScopeBeforeBuildingTheNewOne() =
        runTest {
            val auth = FakeAuthGateway(initial = SessionState.SignedIn(UserId("user-1")))
            val closedOrder = mutableListOf<String>()
            val graph =
                UserSessionGraph(sessionFlow = auth.session, appScope = backgroundScope) { userId ->
                    UserResources(UserGraph(UserId("test-user"), emptyRepository(), FakeSyncCoordinator())) {
                        closedOrder += userId.value
                    }
                }
            runCurrent()

            auth.setSession(SessionState.SignedIn(UserId("user-2")))
            runCurrent()

            assertEquals(listOf("user-1"), closedOrder)
            assertNotNull(graph.userScope.value)
        }

    @Test
    fun reemittingTheSameSignedInUserDoesNotRebuild() =
        runTest {
            val auth = FakeAuthGateway(initial = SessionState.SignedIn(UserId("user-1")))
            var buildCount = 0
            val graph =
                UserSessionGraph(sessionFlow = auth.session, appScope = backgroundScope) { _ ->
                    buildCount++
                    UserResources(UserGraph(UserId("test-user"), emptyRepository(), FakeSyncCoordinator())) {}
                }
            runCurrent()
            assertEquals(1, buildCount)

            auth.setSession(SessionState.SignedIn(UserId("user-1")))
            runCurrent()

            assertEquals(1, buildCount, "the same signed-in user id re-emitted must not rebuild the graph")
        }
}
