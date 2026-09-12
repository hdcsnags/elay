package dev.elay.di

import dev.elay.data.remote.SessionState
import dev.elay.domain.model.CreateProposal
import dev.elay.domain.model.MintRsvpResult
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalResult
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.RespondProposal
import dev.elay.domain.model.UserId
import dev.elay.domain.repository.ProposalRepository
import dev.elay.ui.auth.FakeAuthGateway
import dev.elay.ui.fake.FakePlannerRepository
import dev.elay.ui.fake.PlannerSeed
import dev.elay.ui.together.fake.FakePairRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
                    UserResources(
                        UserGraph(
                            uid,
                            emptyRepository(),
                            FakeSyncCoordinator(),
                            FakePairRepository(),
                            NoopProposalRepository(),
                            NoopAvailabilityRepository(),
                        ),
                    ) {
                    }
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
                    UserResources(
                        UserGraph(
                            UserId("test-user"),
                            emptyRepository(),
                            coordinator,
                            FakePairRepository(),
                            NoopProposalRepository(),
                            NoopAvailabilityRepository(),
                        ),
                    ) {}
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
                    UserResources(
                        UserGraph(
                            uid,
                            emptyRepository(),
                            FakeSyncCoordinator(),
                            FakePairRepository(),
                            NoopProposalRepository(),
                            NoopAvailabilityRepository(),
                        ),
                    ) {
                        closed =
                            true
                    }
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
                    UserResources(
                        UserGraph(
                            UserId("test-user"),
                            emptyRepository(),
                            FakeSyncCoordinator(),
                            FakePairRepository(),
                            NoopProposalRepository(),
                            NoopAvailabilityRepository(),
                        ),
                    ) {
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
                    UserResources(
                        UserGraph(
                            UserId("test-user"),
                            emptyRepository(),
                            FakeSyncCoordinator(),
                            FakePairRepository(),
                            NoopProposalRepository(),
                            NoopAvailabilityRepository(),
                        ),
                    ) {}
                }
            runCurrent()
            assertEquals(1, buildCount)

            auth.setSession(SessionState.SignedIn(UserId("user-1")))
            runCurrent()

            assertEquals(1, buildCount, "the same signed-in user id re-emitted must not rebuild the graph")
        }
}

/** Minimal stand-in: UserSessionGraph only carries the reference; behavior is tested elsewhere. */
private class NoopAvailabilityRepository : dev.elay.domain.availability.AvailabilityRepository {
    override suspend fun mySources() =
        dev.elay.domain.availability.AvailabilitySourcesResult
            .Failed("noop", retryable = false)

    override suspend fun upsertManualBusy(
        operationId: String,
        busyId: String,
        startsAt: kotlinx.datetime.Instant,
        endsAt: kotlinx.datetime.Instant,
        originZoneId: String,
        label: String?,
    ) = dev.elay.domain.availability.ExternalBusyResult
        .Failed("noop", retryable = false)

    override suspend fun deleteManualBusy(
        operationId: String,
        busyId: String,
    ) = dev.elay.domain.availability.ExternalBusyResult
        .Failed("noop", retryable = false)

    override suspend fun selfConflictHints(candidates: List<dev.elay.domain.model.Candidate>) =
        dev.elay.domain.availability.SelfConflictHintsResult
            .Failed("noop", retryable = false)
}

private class NoopProposalRepository : ProposalRepository {
    private val empty = MutableStateFlow<List<ProposalSummary>>(emptyList())

    override fun observeActive(): Flow<List<ProposalSummary>> = empty

    override fun observeHistory(): Flow<List<ProposalSummary>> = empty

    override suspend fun create(command: CreateProposal): ProposalResult =
        ProposalResult.Failed("noop", retryable = false)

    override suspend fun respond(command: RespondProposal): ProposalResult =
        ProposalResult.Failed("noop", retryable = false)

    override suspend fun cancel(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalResult = ProposalResult.Failed("noop", retryable = false)

    override suspend fun complete(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalResult = ProposalResult.Failed("noop", retryable = false)

    // Stage 3 spillover (contracts/stage3-web-rsvp.md item 6): forced by ProposalRepository's one
    // permitted frozen-surface addition; this DI test double is outside B5's grant so kept to the
    // same one-line "noop" style as its siblings above.
    override suspend fun mintRsvpToken(
        operationId: String,
        proposalId: ProposalId,
    ): MintRsvpResult = MintRsvpResult.Failed("noop", retryable = false)

    override fun close() = Unit
}
