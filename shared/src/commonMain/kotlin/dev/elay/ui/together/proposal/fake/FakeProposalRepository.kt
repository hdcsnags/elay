package dev.elay.ui.together.proposal.fake

import dev.elay.domain.model.CreateProposal
import dev.elay.domain.model.MintRsvpResult
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalResult
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.RespondProposal
import dev.elay.domain.repository.ProposalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [ProposalRepository] for Together-proposal previews/tests (mirrors
 * [dev.elay.ui.together.fake.FakePairRepository]'s role for pairing). Records every mutating call
 * ([createCalls]/[respondCalls]/[cancelCalls]/[completeCalls]) so a ViewModel test can assert
 * "create called with 1-3 candidates and correct instants" etc. directly, plus test hooks
 * ([nextCreateResult] and friends) to script a specific [ProposalResult] — including
 * [ProposalResult.Conflict]/[ProposalResult.Failed] — for the next call of each kind.
 *
 * Stage 3's [mintRsvpToken] (contracts/stage3-web-rsvp.md item 6) is the 11th member forced by
 * the frozen [ProposalRepository] surface it implements, hence the [Suppress] below.
 */
@Suppress("TooManyFunctions")
class FakeProposalRepository(
    initialActive: List<ProposalSummary> = emptyList(),
    initialHistory: List<ProposalSummary> = emptyList(),
) : ProposalRepository {
    private val active = MutableStateFlow(initialActive)
    private val history = MutableStateFlow(initialHistory)

    /** Forces the next [create] call to return this instead of the default applied-echo — `null`
     * (the default) means "no scripted failure/conflict; the fake still requires the caller to
     * have seeded one via [emitActive] for a believable applied response in a real test, since this
     * fake does not synthesize a [ProposalSummary] out of thin air." Consumed (reset to `null`) on
     * use, same pattern as [dev.elay.ui.together.fake.FakePairRepository]. */
    var nextCreateResult: ProposalResult? = null
    var nextRespondResult: ProposalResult? = null
    var nextCancelResult: ProposalResult? = null
    var nextCompleteResult: ProposalResult? = null

    val createCalls: MutableList<CreateProposal> = mutableListOf()
    val respondCalls: MutableList<RespondProposal> = mutableListOf()
    val cancelCalls: MutableList<ProposalId> = mutableListOf()
    val completeCalls: MutableList<ProposalId> = mutableListOf()

    var closeCallCount: Int = 0
        private set

    override fun observeActive(): Flow<List<ProposalSummary>> = active.asStateFlow()

    override fun observeHistory(): Flow<List<ProposalSummary>> = history.asStateFlow()

    /** Test-only direct state injection, mirroring [dev.elay.ui.together.fake.FakePairRepository.emit]. */
    fun emitActive(proposals: List<ProposalSummary>) {
        active.value = proposals
    }

    fun emitHistory(proposals: List<ProposalSummary>) {
        history.value = proposals
    }

    override suspend fun create(command: CreateProposal): ProposalResult {
        createCalls += command
        return nextCreateResult.also { nextCreateResult = null } ?: notScripted()
    }

    override suspend fun respond(command: RespondProposal): ProposalResult {
        respondCalls += command
        return nextRespondResult.also { nextRespondResult = null } ?: notScripted()
    }

    override suspend fun cancel(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalResult {
        cancelCalls += proposalId
        return nextCancelResult.also { nextCancelResult = null } ?: notScripted()
    }

    override suspend fun complete(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalResult {
        completeCalls += proposalId
        return nextCompleteResult.also { nextCompleteResult = null } ?: notScripted()
    }

    // Stage 3 spillover (contracts/stage3-web-rsvp.md item 6): minimal stub forced by
    // ProposalRepository's one permitted frozen-surface addition; C4 (share UI) is not this
    // seat's grant so no scripting hook is added here.
    override suspend fun mintRsvpToken(
        operationId: String,
        proposalId: ProposalId,
    ): MintRsvpResult = MintRsvpResult.Failed("not_scripted", retryable = false)

    override fun close() {
        closeCallCount++
    }

    private fun notScripted(): ProposalResult.Failed = ProposalResult.Failed("not_scripted", retryable = false)
}

/** Single shared instance so a bare Together preview/screen has stable, calm default data (no
 * proposals) — matches [dev.elay.ui.together.fake.sharedFakePairRepository]'s role. */
val sharedFakeProposalRepository: ProposalRepository by lazy { FakeProposalRepository() }
