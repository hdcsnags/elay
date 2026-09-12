package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.InviteCodeDto
import dev.elay.data.remote.dto.PairMemberDto
import dev.elay.data.remote.dto.PairRpcEnvelopeDto
import dev.elay.data.remote.dto.PairSnapshotDto
import dev.elay.domain.model.InviteResult
import dev.elay.domain.model.LeaveResult
import dev.elay.domain.model.PairError
import dev.elay.domain.model.PairId
import dev.elay.domain.model.PairState
import dev.elay.domain.model.RedeemResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun member(userId: String) =
    PairMemberDto(
        userId = userId,
        displayName = "User $userId",
        homeTz = "America/Toronto",
        joinedAt = "2026-09-12T00:00:00Z",
    )

private fun snapshot(
    pairId: String = "pair-1",
    topic: String,
    members: List<PairMemberDto>,
    activeInviteExpiresAt: String? = null,
) = PairSnapshotDto(
    pairId = pairId,
    status = "active",
    version = 1,
    channelTopic = topic,
    members = members,
    activeInviteExpiresAt = activeInviteExpiresAt,
)

/**
 * State-machine tests for [SupabasePairRepository] against [FakePairTransport] — no
 * `SupabaseClient`, no network. Uses `backgroundScope`/`runCurrent()` (never `advanceUntilIdle()`
 * — the repository's resync loop is a perpetual coroutine that never goes idle on its own, so
 * `advanceUntilIdle()` would never return).
 */
class SupabasePairRepositoryTest {
    @Test
    fun startsUnpairedWhenThereIsNoActivePair() =
        runTest {
            val transport = FakePairTransport()
            transport.enqueueSnapshot(null)
            val repository = SupabasePairRepository(backgroundScope, transport)

            runCurrent()

            assertEquals(PairState.Unpaired, repository.observePair().value)
            assertEquals(1, transport.fetchCount)
            assertTrue(transport.subscribedTopics.isEmpty())
        }

    @Test
    fun startsPairedAndSubscribesToTheSnapshotTopicWhenTwoMembers() =
        runTest {
            val transport = FakePairTransport()
            transport.enqueueSnapshot(
                snapshot(topic = "pair:pair-1:gen-1", members = listOf(member("u1"), member("u2"))),
            )
            val repository = SupabasePairRepository(backgroundScope, transport)

            runCurrent()

            val state = repository.observePair().value
            assertTrue(state is PairState.Paired)
            assertEquals(listOf("pair:pair-1:gen-1"), transport.subscribedTopics)
        }

    @Test
    fun memberEventTriggersRefetchAndGenerationChangeTriggersResubscribe() =
        runTest {
            val transport = FakePairTransport()
            transport.enqueueSnapshot(
                snapshot(
                    topic = "pair:pair-1:gen-1",
                    members = listOf(member("u1")),
                    activeInviteExpiresAt = "2026-09-13T00:00:00Z",
                ),
            )
            transport.enqueueSnapshot(
                snapshot(topic = "pair:pair-1:gen-2", members = listOf(member("u1"), member("u2"))),
            )
            val repository = SupabasePairRepository(backgroundScope, transport)
            runCurrent()

            assertTrue(repository.observePair().value is PairState.Inviting)
            assertEquals(1, transport.fetchCount)
            val firstHandle = transport.openHandles.single()
            assertTrue(!firstHandle.closed)

            // A membership broadcast on the gen-1 topic is only an invalidation hint.
            firstHandle.emitInvalidation()
            runCurrent()

            assertEquals(2, transport.fetchCount)
            assertTrue(repository.observePair().value is PairState.Paired)
            // Generation rotated -> the old topic's channel is torn down and a fresh one opened
            // on the new topic (never re-used).
            assertEquals(listOf("pair:pair-1:gen-1", "pair:pair-1:gen-2"), transport.subscribedTopics)
            assertTrue(firstHandle.closed)
        }

    @Test
    fun disconnectAloneAlsoTriggersARefetch() =
        runTest {
            val transport = FakePairTransport()
            val topic = "pair:pair-1:gen-1"
            transport.enqueueSnapshot(snapshot(topic = topic, members = listOf(member("u1"), member("u2"))))
            transport.enqueueSnapshot(snapshot(topic = topic, members = listOf(member("u1"), member("u2"))))
            val repository = SupabasePairRepository(backgroundScope, transport)
            runCurrent()
            assertEquals(1, transport.fetchCount)

            // No membership event fired — the channel just drops (contract: subscription error).
            transport.openHandles.single().emitInvalidation()
            runCurrent()

            assertEquals(2, transport.fetchCount)
            assertTrue(repository.observePair().value is PairState.Paired)
        }

    @Test
    fun tokenRefreshOrReconnectPulseAloneTriggersARefetch() =
        runTest {
            val transport = FakePairTransport()
            val topic = "pair:pair-1:gen-1"
            transport.enqueueSnapshot(snapshot(topic = topic, members = listOf(member("u1"), member("u2"))))
            transport.enqueueSnapshot(snapshot(topic = topic, members = listOf(member("u1"), member("u2"))))
            SupabasePairRepository(backgroundScope, transport)
            runCurrent()
            assertEquals(1, transport.fetchCount)

            transport.emitResync()
            runCurrent()

            assertEquals(2, transport.fetchCount)
        }

    @Test
    fun leaveAppliedBecomesUnpairedAndClosesTheChannel() =
        runTest {
            val transport = FakePairTransport()
            val topic = "pair:pair-1:gen-1"
            transport.enqueueSnapshot(snapshot(topic = topic, members = listOf(member("u1"), member("u2"))))
            val repository = SupabasePairRepository(backgroundScope, transport)
            runCurrent()
            val openHandle = transport.openHandles.single()
            assertTrue(!openHandle.closed)

            transport.enqueueSnapshot(null)
            transport.leaveResult =
                PairRpcEnvelopeDto(outcome = "applied", action = "leave_pair", leftPairId = "pair-1")
            val result = repository.leave("op-leave-1")
            runCurrent()

            assertEquals(LeaveResult.Applied(PairId("pair-1")), result)
            assertTrue(openHandle.closed)
            assertEquals(PairState.Unpaired, repository.observePair().value)
        }

    @Test
    fun createInviteAppliedIsVisibleAsInvitingWithCodeAfterTheKickedRefetch() =
        runTest {
            val transport = FakePairTransport()
            transport.enqueueSnapshot(null)
            val repository = SupabasePairRepository(backgroundScope, transport)
            runCurrent()
            assertEquals(PairState.Unpaired, repository.observePair().value)

            val pairSnapshot =
                snapshot(
                    topic = "pair:pair-1:gen-1",
                    members = listOf(member("u1")),
                    activeInviteExpiresAt = "2026-09-13T00:00:00Z",
                )
            transport.enqueueSnapshot(pairSnapshot)
            transport.createInviteResult =
                PairRpcEnvelopeDto(
                    outcome = "applied",
                    action = "create_pair_invite",
                    pair = pairSnapshot,
                    invite =
                        InviteCodeDto(
                            inviteId = "invite-1",
                            code = "ABCDE-FGHJK",
                            expiresAt = "2026-09-13T00:00:00Z",
                        ),
                )

            val result = repository.createInvite("op-create-1")
            runCurrent()

            assertTrue(result is InviteResult.Applied)
            assertEquals("ABCDE-FGHJK", (result as InviteResult.Applied).code)
            val state = repository.observePair().value
            assertTrue(state is PairState.Inviting)
            assertEquals("ABCDE-FGHJK", (state as PairState.Inviting).code)
        }

    @Test
    fun redeemInviteUniformlySurfacesInvalidOrUnavailable() =
        runTest {
            val transport = FakePairTransport()
            transport.enqueueSnapshot(null)
            val repository = SupabasePairRepository(backgroundScope, transport)
            runCurrent()

            transport.redeemInviteResult =
                PairRpcEnvelopeDto(outcome = "invalid_or_unavailable", action = "redeem_pair_invite")
            val result = repository.redeemInvite("op-redeem-1", "WRONG-CODE1")

            assertEquals(RedeemResult.DomainError(PairError.InvalidOrUnavailable), result)
        }

    @Test
    fun closeCancelsTheLoopAndTearsDownTheOpenChannel() =
        runTest {
            val transport = FakePairTransport()
            val topic = "pair:pair-1:gen-1"
            transport.enqueueSnapshot(snapshot(topic = topic, members = listOf(member("u1"), member("u2"))))
            val repository = SupabasePairRepository(backgroundScope, transport)
            runCurrent()
            val handle = transport.openHandles.single()

            repository.close()
            runCurrent()

            assertTrue(handle.closed)

            // The loop is cancelled: further channel activity must not cause another fetch.
            val fetchCountAtClose = transport.fetchCount
            handle.emitInvalidation()
            runCurrent()
            assertEquals(fetchCountAtClose, transport.fetchCount)
        }

    @Test
    fun coldStartWithALiveInviteHasNoCachedCode() =
        runTest {
            val transport = FakePairTransport()
            transport.enqueueSnapshot(
                snapshot(
                    topic = "pair:pair-1:gen-1",
                    members = listOf(member("u1")),
                    activeInviteExpiresAt = "2026-09-13T00:00:00Z",
                ),
            )
            val repository = SupabasePairRepository(backgroundScope, transport)

            runCurrent()

            val state = repository.observePair().value
            assertTrue(state is PairState.Inviting)
            assertNull((state as PairState.Inviting).code)
        }
}
