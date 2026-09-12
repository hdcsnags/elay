package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.PairRpcEnvelopeDto
import dev.elay.data.remote.dto.PairSnapshotDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Scripted, in-memory [PairTransport] driving [SupabasePairRepository]'s state machine with no
 * network — see [SupabasePairRepository]'s kdoc for why this seam exists.
 */
internal class FakePairTransport : PairTransport {
    /** `rpc_get_pair` responses, consumed one per call to [fetchPair]; once the queue is drained
     * the last value returned keeps repeating, so a test only enqueues the values that change,
     * not one per expected call. */
    private val snapshots = ArrayDeque<PairSnapshotDto?>()
    private var lastReturned: PairSnapshotDto? = null
    var fetchCount = 0
        private set

    val subscribedTopics = mutableListOf<String>()
    val openHandles = mutableListOf<FakePairChannelHandle>()

    var createInviteResult: PairRpcEnvelopeDto? = null
    var redeemInviteResult: PairRpcEnvelopeDto? = null
    var leaveResult: PairRpcEnvelopeDto? = null

    var fetchError: Throwable? = null
    var subscribeError: Throwable? = null

    private val mutableResyncSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val resyncSignals: Flow<Unit> = mutableResyncSignals

    fun enqueueSnapshot(snapshot: PairSnapshotDto?) {
        snapshots.addLast(snapshot)
    }

    /** Simulates a token-refresh/reconnect pulse (contract §3's other two refetch triggers). */
    fun emitResync() {
        mutableResyncSignals.tryEmit(Unit)
    }

    override suspend fun fetchPair(): PairSnapshotDto? {
        fetchError?.let { throw it }
        fetchCount++
        val next = if (snapshots.isNotEmpty()) snapshots.removeFirst() else lastReturned
        lastReturned = next
        return next
    }

    override suspend fun createInvite(
        operationId: String,
        ttlMinutes: Int,
    ): PairRpcEnvelopeDto = createInviteResult ?: error("createInviteResult not scripted for this test")

    override suspend fun redeemInvite(
        operationId: String,
        code: String,
    ): PairRpcEnvelopeDto = redeemInviteResult ?: error("redeemInviteResult not scripted for this test")

    override suspend fun leavePair(operationId: String): PairRpcEnvelopeDto =
        leaveResult ?: error("leaveResult not scripted for this test")

    override suspend fun subscribe(topic: String): PairChannelHandle {
        subscribeError?.let { throw it }
        subscribedTopics += topic
        val handle = FakePairChannelHandle(topic)
        openHandles += handle
        return handle
    }
}

internal class FakePairChannelHandle(
    val topic: String,
) : PairChannelHandle {
    private val mutableInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val invalidations: Flow<Unit> = mutableInvalidations

    var closed = false
        private set

    /** Simulates a `pair.member_joined.v1`/`pair.member_left.v1` broadcast (or, equally, an
     * unexpected drop — the caller's reaction is identical either way). */
    fun emitInvalidation() {
        mutableInvalidations.tryEmit(Unit)
    }

    override suspend fun close() {
        closed = true
    }
}
