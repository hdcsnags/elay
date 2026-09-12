package dev.elay.ui.together

import dev.elay.domain.model.InviteResult
import dev.elay.domain.model.LeaveResult
import dev.elay.domain.model.PairError
import dev.elay.domain.model.PairFailure
import dev.elay.domain.model.PairId
import dev.elay.domain.model.PairMember
import dev.elay.domain.model.PairSnapshot
import dev.elay.domain.model.PairState
import dev.elay.domain.model.PairStatus
import dev.elay.domain.model.RedeemResult
import dev.elay.domain.model.UserId
import dev.elay.ui.together.fake.FakePairRepository
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [TogetherViewModel] state machine transitions (contracts/stage1-pairing.md §4). */
class TogetherViewModelTest {
    @Test
    fun startsLoadingThenReflectsTheRepositorysCurrentPairState() =
        runTest {
            val repository = FakePairRepository(initialState = PairState.Unpaired)
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()

            assertEquals(PairState.Unpaired, viewModel.state.value.pairState)
        }

    @Test
    fun createInviteMovesUnpairedToInvitingWithACode() =
        runTest {
            val repository = FakePairRepository(initialState = PairState.Unpaired)
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()

            viewModel.createInvite()
            runCurrent()

            val pairState = viewModel.state.value.pairState
            assertIs<PairState.Inviting>(pairState)
            assertEquals("ABCDE-FGHJK", pairState.code)
            assertTrue(!viewModel.state.value.isSubmitting)
        }

    @Test
    fun codeNullInvitingRendersWithoutACrashOrAFabricatedCode() =
        runTest {
            val snapshot = soloSnapshot()
            val repository = FakePairRepository()
            val expiresAt = Instant.fromEpochMilliseconds(2_000_000_000_000)
            repository.emit(PairState.Inviting(snapshot, code = null, expiresAt = expiresAt))
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()

            val pairState = viewModel.state.value.pairState
            assertIs<PairState.Inviting>(pairState)
            assertNull(pairState.code)
        }

    @Test
    fun redeemInviteMovesToPairedAndClearsTheCodeField() =
        runTest {
            val repository = FakePairRepository()
            val expiresAt = Instant.fromEpochMilliseconds(2_000_000_000_000)
            repository.emit(PairState.Inviting(soloSnapshot(), "ABCDE-FGHJK", expiresAt))
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()

            viewModel.onRedeemCodeChanged("abcde-fghjk")
            viewModel.redeemInvite()
            runCurrent()

            assertIs<PairState.Paired>(viewModel.state.value.pairState)
            assertEquals("", viewModel.state.value.redeemCodeText)
        }

    @Test
    fun redeemInviteWithAWrongCodeSetsACalmActionErrorNotARawOutcomeString() =
        runTest {
            val repository = FakePairRepository()
            val expiresAt = Instant.fromEpochMilliseconds(2_000_000_000_000)
            repository.emit(PairState.Inviting(soloSnapshot(), "ABCDE-FGHJK", expiresAt))
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()

            viewModel.onRedeemCodeChanged("wrong-codee")
            viewModel.redeemInvite()
            runCurrent()

            assertEquals(
                "That didn't go through — check the code and try again.",
                viewModel.state.value.actionError,
            )
            // still Inviting — a rejected redeem must not silently pair the caller.
            assertIs<PairState.Inviting>(viewModel.state.value.pairState)
        }

    @Test
    fun networkErrorOnCreateInviteRendersACalmMessageWithoutLeakingTheRawReason() =
        runTest {
            val repository = FakePairRepository()
            repository.nextInviteResult = InviteResult.NetworkError("connection_reset_by_peer", retryable = true)
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()

            viewModel.createInvite()
            runCurrent()

            assertEquals(
                "Couldn't reach the server — try again in a moment.",
                viewModel.state.value.actionError,
            )
        }

    @Test
    fun leaveRequiresConfirmationBeforeCallingTheRepository() =
        runTest {
            val repository = FakePairRepository(initialState = PairState.Paired(pairedSnapshot()))
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()

            viewModel.requestLeaveConfirmation()
            assertTrue(viewModel.state.value.leaveConfirmPending)

            viewModel.cancelLeaveConfirmation()
            runCurrent()
            assertTrue(!viewModel.state.value.leaveConfirmPending)
            assertIs<PairState.Paired>(viewModel.state.value.pairState)
        }

    @Test
    fun confirmingLeaveCallsTheRepositoryAndReturnsToUnpaired() =
        runTest {
            val repository = FakePairRepository(initialState = PairState.Paired(pairedSnapshot()))
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()

            viewModel.requestLeaveConfirmation()
            viewModel.confirmLeave()
            runCurrent()

            assertEquals(PairState.Unpaired, viewModel.state.value.pairState)
            assertTrue(!viewModel.state.value.leaveConfirmPending)
        }

    @Test
    fun leaveDomainErrorOnAnAlreadyAbsentPairRendersNotMemberCalmly() =
        runTest {
            val repository = FakePairRepository(initialState = PairState.Paired(pairedSnapshot()))
            repository.nextLeaveResult = LeaveResult.DomainError(PairError.NotMember)
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()

            viewModel.requestLeaveConfirmation()
            viewModel.confirmLeave()
            runCurrent()

            assertEquals("You're not in a pair right now.", viewModel.state.value.actionError)
        }

    @Test
    fun retryClearsAStaleActionErrorWithoutInventingANewRepositoryCall() =
        runTest {
            val repository = FakePairRepository()
            repository.emit(PairState.Failed(PairFailure.Network("timeout", retryable = true)))
            // Forced so this action error appears without the fake's default simulation moving
            // the underlying pair state away from Failed (redeemInvite never touches it here).
            repository.nextRedeemResult = RedeemResult.DomainError(PairError.InvalidOrUnavailable)
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()
            viewModel.onRedeemCodeChanged("anything")
            viewModel.redeemInvite()
            runCurrent()
            assertTrue(viewModel.state.value.actionError != null)

            viewModel.retry()

            assertNull(viewModel.state.value.actionError)
            assertIs<PairState.Failed>(viewModel.state.value.pairState)
        }

    @Test
    fun dismissActionErrorClearsIt() =
        runTest {
            val repository = FakePairRepository()
            repository.nextRedeemResult = RedeemResult.DomainError(PairError.InvalidOrUnavailable)
            val viewModel = TogetherViewModel(repository, backgroundScope)
            runCurrent()
            viewModel.onRedeemCodeChanged("anything")
            viewModel.redeemInvite()
            runCurrent()
            assertTrue(viewModel.state.value.actionError != null)

            viewModel.dismissActionError()

            assertNull(viewModel.state.value.actionError)
        }

    private fun soloSnapshot(): PairSnapshot =
        PairSnapshot(
            id = PairId("pair-1"),
            status = PairStatus.Active,
            version = 1,
            channelTopic = "pair:pair-1:gen-1",
            members =
                listOf(
                    PairMember(
                        userId = UserId("me"),
                        displayName = "You",
                        homeTz = "America/New_York",
                        joinedAt = Instant.fromEpochMilliseconds(0),
                    ),
                ),
            activeInviteExpiresAt = null,
        )

    private fun pairedSnapshot(): PairSnapshot =
        soloSnapshot().copy(
            members =
                soloSnapshot().members +
                    PairMember(
                        userId = UserId("peer"),
                        displayName = "Jordan",
                        homeTz = "America/Chicago",
                        joinedAt = Instant.fromEpochMilliseconds(0),
                    ),
        )
}
