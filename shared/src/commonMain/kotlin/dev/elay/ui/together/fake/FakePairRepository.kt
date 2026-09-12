package dev.elay.ui.together.fake

import dev.elay.domain.model.InviteResult
import dev.elay.domain.model.LeaveResult
import dev.elay.domain.model.PairError
import dev.elay.domain.model.PairId
import dev.elay.domain.model.PairMember
import dev.elay.domain.model.PairSnapshot
import dev.elay.domain.model.PairState
import dev.elay.domain.model.PairStatus
import dev.elay.domain.model.RedeemResult
import dev.elay.domain.model.UserId
import dev.elay.domain.repository.PairRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * In-memory [PairRepository] for Together previews/tests (mirrors
 * [dev.elay.ui.fake.FakePlannerRepository]'s role for the planner surfaces). Simulates the
 * frozen state machine (contracts/stage1-pairing.md §4) well enough to drive
 * [dev.elay.ui.together.TogetherViewModel] tests end to end, plus test hooks
 * ([nextInviteResult]/[nextRedeemResult]/[nextLeaveResult] and [emit]) to force a specific
 * outcome or state — including the code-null Inviting case a real cold `rpc_get_pair` fetch
 * would produce (this process never held the code).
 */
class FakePairRepository(
    initialState: PairState = PairState.Unpaired,
    private val selfUserId: UserId = UserId("me"),
    private val peerUserId: UserId = UserId("peer"),
    private val clock: Clock = Clock.System,
    private val defaultCode: String = "ABCDE-FGHJK",
) : PairRepository {
    private val mutableState = MutableStateFlow(initialState)

    /** Forces the next [createInvite] call to return this instead of simulating one — `null`
     * (the default) means "simulate normally". Consumed (reset to `null`) on use. */
    var nextInviteResult: InviteResult? = null

    /** See [nextInviteResult]; same for [redeemInvite]. */
    var nextRedeemResult: RedeemResult? = null

    /** See [nextInviteResult]; same for [leave]. */
    var nextLeaveResult: LeaveResult? = null

    var closeCallCount: Int = 0
        private set

    override fun observePair(): StateFlow<PairState> = mutableState.asStateFlow()

    /** Test-only direct state injection — e.g. a code-null cold-start Inviting snapshot. */
    fun emit(state: PairState) {
        mutableState.value = state
    }

    @Suppress("ReturnCount") // one early-exit per simulated outcome (forced/already-paired/success)
    override suspend fun createInvite(
        operationId: String,
        ttlMinutes: Int,
    ): InviteResult {
        nextInviteResult?.let {
            nextInviteResult = null
            return it
        }
        val current = mutableState.value
        if (current is PairState.Paired) {
            return InviteResult.DomainError(PairError.InvalidOrUnavailable)
        }
        val existingSnapshot = (current as? PairState.Inviting)?.snapshot
        val expiresAt = clock.now() + ttlMinutes.minutes
        val snapshot = existingSnapshot ?: soloSnapshot()
        val updated = snapshot.copy(activeInviteExpiresAt = expiresAt)
        mutableState.value = PairState.Inviting(updated, defaultCode, expiresAt)
        return InviteResult.Applied(updated, defaultCode, expiresAt)
    }

    @Suppress("ReturnCount") // one early-exit per simulated outcome (forced/rejected/success)
    override suspend fun redeemInvite(
        operationId: String,
        code: String,
    ): RedeemResult {
        nextRedeemResult?.let {
            nextRedeemResult = null
            return it
        }
        val current = mutableState.value
        val normalizedCode = code.filter(Char::isLetterOrDigit).uppercase()
        val normalizedDefault = defaultCode.filter(Char::isLetterOrDigit).uppercase()
        if (current !is PairState.Inviting || normalizedCode != normalizedDefault) {
            return RedeemResult.DomainError(PairError.InvalidOrUnavailable)
        }
        val paired = pairedSnapshot(current.snapshot)
        mutableState.value = PairState.Paired(paired)
        return RedeemResult.Applied(paired)
    }

    @Suppress("ReturnCount") // one early-exit per simulated outcome (forced/not-a-member/success)
    override suspend fun leave(operationId: String): LeaveResult {
        nextLeaveResult?.let {
            nextLeaveResult = null
            return it
        }
        val current = mutableState.value
        val pairId =
            when (current) {
                is PairState.Paired -> current.snapshot.id
                is PairState.Inviting -> current.snapshot.id
                else -> null
            } ?: return LeaveResult.DomainError(PairError.NotMember)
        mutableState.value = PairState.Unpaired
        return LeaveResult.Applied(pairId)
    }

    override fun close() {
        closeCallCount++
    }

    private fun soloSnapshot(): PairSnapshot =
        PairSnapshot(
            id = PairId("fake-pair-1"),
            status = PairStatus.Active,
            version = 1,
            channelTopic = "pair:fake-pair-1:gen-1",
            members = listOf(selfMember()),
            activeInviteExpiresAt = null,
        )

    private fun pairedSnapshot(base: PairSnapshot): PairSnapshot =
        base.copy(
            members = listOf(selfMember(), peerMember()),
            activeInviteExpiresAt = null,
            version = base.version + 1,
        )

    private fun selfMember() =
        PairMember(
            userId = selfUserId,
            displayName = "You",
            homeTz = "America/New_York",
            joinedAt = clock.now(),
        )

    private fun peerMember() =
        PairMember(
            userId = peerUserId,
            displayName = "Jordan",
            homeTz = "America/Chicago",
            joinedAt = clock.now(),
        )
}

/** Single shared instance so a bare Together preview/screen has stable, calm default data. */
val sharedFakePairRepository: PairRepository by lazy { FakePairRepository() }
