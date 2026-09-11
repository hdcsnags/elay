package dev.elay.sync

import kotlinx.coroutines.flow.Flow

/**
 * Frozen sync contracts (contracts/phase1-planner.md §3).
 * One command per typed RPC; replay is strict FIFO per aggregateId,
 * one in flight globally in Phase 1.
 */

enum class Aggregate(
    val wire: String,
) {
    Goal("goal"),
    Milestone("milestone"),
    Task("task"),
    Capture("capture"),
    TimeBlock("time_block"),
}

sealed interface MutationCommand {
    val operationId: String
    val aggregate: Aggregate
    val aggregateId: String
    val expectedVersion: Long

    data class Upsert(
        override val operationId: String,
        override val aggregate: Aggregate,
        override val aggregateId: String,
        override val expectedVersion: Long,
        val payloadJson: String,
    ) : MutationCommand

    data class Delete(
        override val operationId: String,
        override val aggregate: Aggregate,
        override val aggregateId: String,
        override val expectedVersion: Long,
    ) : MutationCommand
}

sealed interface SyncStatus {
    data object Idle : SyncStatus

    data class Replaying(
        val pending: Int,
    ) : SyncStatus

    data class Paused(
        val reason: String,
    ) : SyncStatus

    data class ConflictsHeld(
        val count: Int,
    ) : SyncStatus
}

interface SyncCoordinator {
    val status: Flow<SyncStatus>

    /** Persist to the outbox; replay picks it up. Never performs network work itself. */
    suspend fun enqueue(command: MutationCommand)

    /** Drain the queue once (backoff rules in the contract §3). Safe to call repeatedly. */
    suspend fun replayOnce()
}
