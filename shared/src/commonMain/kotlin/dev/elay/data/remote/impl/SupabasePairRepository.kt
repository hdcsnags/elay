package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.PairRpcEnvelopeDto
import dev.elay.data.remote.dto.PairSnapshotDto
import dev.elay.data.remote.dto.toDomain
import dev.elay.domain.model.InviteResult
import dev.elay.domain.model.LeaveResult
import dev.elay.domain.model.PairError
import dev.elay.domain.model.PairFailure
import dev.elay.domain.model.PairId
import dev.elay.domain.model.PairState
import dev.elay.domain.model.RedeemResult
import dev.elay.domain.repository.PairRepository
import dev.elay.util.ElayLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Stage 1 realtime pairing events (council/stage1-pairing-contract-sol.md §3). Reserved
 * proposal/commitment names are Stage 2's and never appear here.
 */
private const val EVENT_MEMBER_JOINED = "pair.member_joined.v1"
private const val EVENT_PROPOSAL_CREATED = "pair.proposal_created.v1"
private const val EVENT_PROPOSAL_UPDATED = "pair.proposal_updated.v1"
private const val EVENT_COMMITMENT_CHANGED = "pair.commitment_changed.v1"
private const val EVENT_MEMBER_LEFT = "pair.member_left.v1"

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

/**
 * Everything [SupabasePairRepository]'s state machine needs from the network, behind one seam.
 * Exists purely for testability: the state machine (join/leave -> refetch, generation change ->
 * resubscribe, error -> refetch-before-resubscribe, `leave()` -> closed channel) is exercised in
 * commonTest against a scripted fake implementation — no `SupabaseClient`, no network, per the
 * brief's own "no network" test constraint. [SupabaseRealtimePairTransport] is the only production
 * implementation, wiring this to the real SDK.
 */
internal interface PairTransport {
    /** `rpc_get_pair`: the caller's active pair, or `null` if unpaired. */
    suspend fun fetchPair(): PairSnapshotDto?

    suspend fun createInvite(
        operationId: String,
        ttlMinutes: Int,
    ): PairRpcEnvelopeDto

    suspend fun redeemInvite(
        operationId: String,
        code: String,
    ): PairRpcEnvelopeDto

    suspend fun leavePair(operationId: String): PairRpcEnvelopeDto

    /** Opens a private Broadcast subscription on [topic]. Never returns a channel already
     * subscribed elsewhere — one call, one fresh channel. */
    suspend fun subscribe(topic: String): PairChannelHandle

    /** Pulses (never completes) on session token refresh or realtime reconnect — contract §3:
     * "On reconnect, token refresh, generation change, or subscription error, refetch before
     * resubscribing." Generation-change and subscription-error are handled by
     * [PairChannelHandle.invalidations] instead; this covers the other two triggers. */
    val resyncSignals: Flow<Unit>
}

/** One live (or dying) subscription. [invalidations] pulses once per membership broadcast event
 * *and* once more if the channel drops unexpectedly — in both cases the caller's response is the
 * same: refetch, then resubscribe against whatever topic the fresh snapshot names. */
internal interface PairChannelHandle {
    val invalidations: Flow<Unit>

    /** Stage 2 lead wiring: pulses on proposal/commitment broadcast events. Pair state does not
     * change on these; they exist so the proposal repository can share this channel (contract §3,
     * B4's no-second-channel seam) instead of opening its own. */
    val proposalEvents: Flow<Unit>

    suspend fun close()
}

/**
 * ADR-002 thin adapter: the only class where realtime/postgrest types are visible for pairing.
 * Constructed with a client supplied by DI — never builds its own (concierge wiring owns the
 * [SupabaseClient] lifecycle, matching [SupabaseDataGateway]/[SupabaseAuthGateway]).
 */
private class SupabaseRealtimePairTransport(
    private val client: SupabaseClient,
) : PairTransport {
    override suspend fun fetchPair(): PairSnapshotDto? = client.postgrest.rpc("rpc_get_pair").decodeAs()

    override suspend fun createInvite(
        operationId: String,
        ttlMinutes: Int,
    ): PairRpcEnvelopeDto =
        callPairRpc(
            "rpc_create_pair_invite",
            buildJsonObject {
                put("p_operation_id", operationId)
                put("p_ttl_minutes", ttlMinutes)
            },
        )

    override suspend fun redeemInvite(
        operationId: String,
        code: String,
    ): PairRpcEnvelopeDto =
        callPairRpc(
            "rpc_redeem_pair_invite",
            buildJsonObject {
                put("p_operation_id", operationId)
                put("p_code", code)
            },
        )

    override suspend fun leavePair(operationId: String): PairRpcEnvelopeDto =
        callPairRpc(
            "rpc_leave_pair",
            buildJsonObject { put("p_operation_id", operationId) },
        )

    private suspend fun callPairRpc(
        function: String,
        params: JsonObject,
    ): PairRpcEnvelopeDto = client.postgrest.rpc(function, params).decodeAs()

    override suspend fun subscribe(topic: String): PairChannelHandle {
        val channel = client.realtime.channel(topic) { isPrivate = true }
        channel.subscribe()
        // Stage5 §A: log a hash, never the raw topic — the topic string embeds the pair id.
        ElayLog.d("Realtime") {
            "ELAY realtime subscribe: topic=#${topic.hashCode()} " +
                "status=${channel.status.value} socket=${client.realtime.status.value}"
        }
        return SupabaseRealtimeChannelHandle(client, channel)
    }

    override val resyncSignals: Flow<Unit> =
        merge(
            // Skip(1): the first Authenticated/CONNECTED emission is what our own startup fetch
            // and subscribe already react to — only *later* transitions are refresh/reconnect.
            client.auth.sessionStatus
                .filterIsInstance<SessionStatus.Authenticated>()
                .drop(1)
                .map { Unit },
            client.realtime.status
                .filter { it == Realtime.Status.CONNECTED }
                .drop(1)
                .map { Unit },
        )
}

private class SupabaseRealtimeChannelHandle(
    private val client: SupabaseClient,
    private val channel: RealtimeChannel,
) : PairChannelHandle {
    override val invalidations: Flow<Unit> =
        merge(
            channel.broadcastFlow(EVENT_MEMBER_JOINED).map { Unit },
            channel.broadcastFlow(EVENT_MEMBER_LEFT).map { Unit },
            channel.status.filter { it == RealtimeChannel.Status.UNSUBSCRIBED }.map { Unit },
        )

    override val proposalEvents: Flow<Unit> =
        merge(
            channel.broadcastFlow(EVENT_PROPOSAL_CREATED).map { Unit },
            channel.broadcastFlow(EVENT_PROPOSAL_UPDATED).map { Unit },
            channel.broadcastFlow(EVENT_COMMITMENT_CHANGED).map { Unit },
            // NOTE (D2, flagged for lead review): channel.topic embeds the pair id, same as the
            // subscribe-site topic above; §A named only that site for hashing, so this one is
            // left as a like-for-like println->ElayLog swap. Consider hashing here too for
            // consistency.
        ).onEach { ElayLog.d("Realtime") { "ELAY realtime proposal event on topic#${channel.topic.hashCode()}" } }

    override suspend fun close() {
        client.realtime.removeChannel(channel)
    }
}

@Suppress("TooGenericExceptionCaught") // this adapter must never let a network failure escape uncaught.
private suspend fun <T> runCatchingSuspend(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

private fun Throwable.toNetworkFailure(): PairFailure.Network =
    when (this) {
        is RestException -> {
            val retryable =
                statusCode == HTTP_UNAUTHORIZED ||
                    statusCode == HTTP_FORBIDDEN ||
                    statusCode == HTTP_TOO_MANY_REQUESTS ||
                    statusCode >= HTTP_SERVER_ERROR_FLOOR
            PairFailure.Network("http_$statusCode", retryable)
        }
        is HttpRequestException -> PairFailure.Network("network: $message", retryable = true)
        else -> PairFailure.Network(message ?: "unknown_error", retryable = true)
    }

private fun Throwable.networkErrorReason(): Pair<String, Boolean> {
    val failure = toNetworkFailure()
    return failure.reason to failure.retryable
}

private fun String.toPairError(): PairError =
    when (this) {
        "invalid_or_unavailable" -> PairError.InvalidOrUnavailable
        "not_member" -> PairError.NotMember
        else -> PairError.Unrecognized(this)
    }

/**
 * [PairRepository] over [PairTransport] (contracts/stage1-pairing.md; council/stage1-pairing-
 * contract-sol.md §3-§4). No Room caching (lead amendment 4) — this is remote+memory: the only
 * state kept across a refetch is the in-flight invite code cache (see [PairState.Inviting]'s
 * kdoc) and the currently-open channel.
 *
 * A single background loop drives everything: fetch `rpc_get_pair`, derive [PairState], open a
 * private Broadcast subscription on the snapshot's `channel_topic`, then wait for the next
 * invalidation (a membership broadcast, an unexpected channel drop, a token refresh/reconnect
 * pulse, or an explicit "please recheck" kick from [createInvite]/[redeemInvite]/[leave]) before
 * looping back to refetch. Every loop iteration refetches *before* it resubscribes, satisfying
 * the contract's reconnect/token-refresh/generation-change/error rule uniformly.
 */
class SupabasePairRepository internal constructor(
    private val scope: CoroutineScope,
    private val transport: PairTransport,
) : PairRepository {
    constructor(client: SupabaseClient, scope: CoroutineScope) : this(scope, SupabaseRealtimePairTransport(client))

    private val mutableState = MutableStateFlow<PairState>(PairState.Loading)

    /** CONFLATED channel, not a SharedFlow: a `MutableSharedFlow(replay = 0)` DISCARDS emissions
     * while nobody is suspended in `first()` (extraBufferCapacity only buffers for an
     * already-subscribed slow collector), so a kick landing mid-fetch/mid-subscribe was silently
     * lost — pre-gate finding F10, 2026-09-12. A conflated channel retains the latest pulse until
     * [resyncLoop] receives it. */
    private val kick = Channel<Unit>(Channel.CONFLATED)

    /** Keyed by parsed [Instant] rather than the raw wire string: `rpc_get_pair` and
     * `rpc_create_pair_invite` are two separate responses, so comparing their expiry strings
     * byte-for-byte would be fragile if the server ever formats them differently. */
    private var cachedInvite: CachedInvite? = null
    private var currentChannel: PairChannelHandle? = null

    /** Stage 2 lead wiring: proposal-event pulses forwarded across channel swaps. */
    private val proposalHints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var proposalForwardJob: Job? = null

    val proposalInvalidations: Flow<Unit> get() = proposalHints

    private val resyncJob: Job = scope.launch { resyncLoop() }

    override fun observePair(): StateFlow<PairState> = mutableState.asStateFlow()

    @Suppress("ReturnCount") // one early-exit per RPC outcome (network/non-applied/malformed/success)
    override suspend fun createInvite(
        operationId: String,
        ttlMinutes: Int,
    ): InviteResult {
        val envelope =
            runCatchingSuspend { transport.createInvite(operationId, ttlMinutes) }.getOrElse { e ->
                val (reason, retryable) = e.networkErrorReason()
                return InviteResult.NetworkError(reason, retryable)
            }
        if (envelope.outcome != "applied") return InviteResult.DomainError(envelope.outcome.toPairError())
        val pairDto = envelope.pair
        val invite = envelope.invite
        if (pairDto == null || invite == null) {
            return InviteResult.NetworkError("malformed_applied_response", retryable = false)
        }
        val expiresAt = Instant.parse(invite.expiresAt)
        cachedInvite = CachedInvite(pairDto.pairId, expiresAt, invite.code)
        kick.trySend(Unit)
        return InviteResult.Applied(pairDto.toDomain(), invite.code, expiresAt)
    }

    @Suppress("ReturnCount") // one early-exit per RPC outcome (network/non-applied/malformed/success)
    override suspend fun redeemInvite(
        operationId: String,
        code: String,
    ): RedeemResult {
        val envelope =
            runCatchingSuspend { transport.redeemInvite(operationId, code) }.getOrElse { e ->
                val (reason, retryable) = e.networkErrorReason()
                return RedeemResult.NetworkError(reason, retryable)
            }
        if (envelope.outcome != "applied") return RedeemResult.DomainError(envelope.outcome.toPairError())
        val pairDto =
            envelope.pair ?: return RedeemResult.NetworkError("malformed_applied_response", retryable = false)
        kick.trySend(Unit)
        return RedeemResult.Applied(pairDto.toDomain())
    }

    @Suppress("ReturnCount") // one early-exit per RPC outcome (network/non-applied/malformed/success)
    override suspend fun leave(operationId: String): LeaveResult {
        val envelope =
            runCatchingSuspend { transport.leavePair(operationId) }.getOrElse { e ->
                val (reason, retryable) = e.networkErrorReason()
                return LeaveResult.NetworkError(reason, retryable)
            }
        if (envelope.outcome != "applied") return LeaveResult.DomainError(envelope.outcome.toPairError())
        val leftId =
            envelope.leftPairId ?: return LeaveResult.NetworkError("malformed_applied_response", retryable = false)
        cachedInvite = null
        kick.trySend(Unit)
        return LeaveResult.Applied(PairId(leftId))
    }

    /** Cancels the background loop and tears down whatever channel is open — never leaves a
     * private subscription authorized past this repository's lifetime (ADR-009). Does not force
     * [observePair]'s last value to anything in particular: nothing should still be reading it
     * once the owning session is gone. */
    override fun close() {
        resyncJob.cancel()
        // Pre-gate finding F11 (2026-09-12): the forward job runs on [scope] (the app scope),
        // not as a child of [resyncJob] — without this it outlived close() holding the dead
        // channel.
        proposalForwardJob?.cancel()
        proposalForwardJob = null
        val handle = currentChannel
        currentChannel = null
        cachedInvite = null
        if (handle != null) {
            scope.launch { handle.close() }
        }
    }

    /** No `break`/`continue`: a fetch or subscribe failure publishes [PairState.Failed]/
     * [PairState.Unpaired] and waits out a kick *inside* the helper, then returns `null` so this
     * loop simply falls through to its next iteration without an explicit jump.
     *
     * Wake-up plumbing (pre-gate finding F10): [transport.resyncSignals] is forwarded into the
     * conflated [kick] channel by a collector armed BEFORE the first fetch, and each live
     * channel's [PairChannelHandle.invalidations] is forwarded the same way while it is open —
     * so a signal arriving mid-fetch/mid-subscribe is retained, not dropped. Proposal events
     * lost during a channel swap are compensated by one [proposalHints] pulse right after each
     * resubscribe (the proposal repository just refetches; a spurious pulse is harmless). */
    private suspend fun resyncLoop(): Unit =
        coroutineScope {
            val signalForward = launch { transport.resyncSignals.collect { kick.trySend(Unit) } }
            try {
                while (currentCoroutineContext().isActive) {
                    val snapshot = awaitSnapshotOrWaitForKick()
                    if (snapshot != null) {
                        mutableState.value = deriveState(snapshot)
                        val handle = subscribeOrWaitForKick(snapshot.channelTopic)
                        if (handle != null) {
                            currentChannel = handle
                            val invalidationForward =
                                launch { handle.invalidations.collect { kick.trySend(Unit) } }
                            proposalForwardJob?.cancel()
                            proposalForwardJob =
                                scope.launch { handle.proposalEvents.collect { proposalHints.tryEmit(Unit) } }
                            proposalHints.tryEmit(Unit)
                            kick.receive()
                            invalidationForward.cancel()
                            proposalForwardJob?.cancel()
                            proposalForwardJob = null
                            currentChannel = null
                            runCatchingSuspend { handle.close() }
                        }
                    }
                }
            } finally {
                signalForward.cancel()
            }
        }

    /** `rpc_get_pair`. On success with no active pair, publishes [PairState.Unpaired]; on
     * failure, publishes [PairState.Failed]. Either way waits for the next kick before returning
     * `null` so the caller retries from the top instead of busy-looping. */
    private suspend fun awaitSnapshotOrWaitForKick(): PairSnapshotDto? {
        val snapshot =
            runCatchingSuspend { transport.fetchPair() }.getOrElse { e ->
                mutableState.value = PairState.Failed(e.toNetworkFailure())
                waitForKick()
                return null
            }
        if (snapshot == null) {
            cachedInvite = null
            mutableState.value = PairState.Unpaired
            waitForKick()
        }
        return snapshot
    }

    private suspend fun subscribeOrWaitForKick(topic: String): PairChannelHandle? =
        runCatchingSuspend { transport.subscribe(topic) }.getOrElse { e ->
            mutableState.value = PairState.Failed(e.toNetworkFailure())
            waitForKick()
            null
        }

    /** [transport.resyncSignals] is already forwarded into [kick] by [resyncLoop]'s persistent
     * collector, so a bare receive covers both sources — and a pulse that landed while this
     * coroutine was busy is retained by the conflated channel (F10). */
    private suspend fun waitForKick() {
        kick.receive()
    }

    private fun deriveState(dto: PairSnapshotDto): PairState {
        val domain = dto.toDomain()
        return when {
            domain.members.size >= 2 -> {
                cachedInvite = null
                PairState.Paired(domain)
            }
            domain.activeInviteExpiresAt != null -> {
                val expiresAt = domain.activeInviteExpiresAt
                val code =
                    cachedInvite
                        ?.takeIf { it.pairId == dto.pairId && it.expiresAt == expiresAt }
                        ?.code
                PairState.Inviting(domain, code, expiresAt)
            }
            // No second member and no live invite: not the shape the RPCs produce in practice
            // (create-invite always creates the pair row and the invite atomically), but a
            // defensive fallback still needs a state — Unpaired is the safe rendering (it lets
            // the user create a fresh invite, which is exactly what reusing the singleton pair
            // row would do server-side anyway).
            else -> {
                cachedInvite = null
                PairState.Unpaired
            }
        }
    }
}

private data class CachedInvite(
    val pairId: String,
    val expiresAt: Instant,
    val code: String,
)
