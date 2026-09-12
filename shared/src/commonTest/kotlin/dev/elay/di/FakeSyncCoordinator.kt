package dev.elay.di

import dev.elay.sync.MutationCommand
import dev.elay.sync.SyncCoordinator
import dev.elay.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow

/** Records calls instead of touching a real outbox/network — the `di` tests' only fake. */
class FakeSyncCoordinator : SyncCoordinator {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val status = _status

    val enqueued = mutableListOf<MutationCommand>()
    var replayOnceCalls: Int = 0
        private set

    override suspend fun enqueue(command: MutationCommand) {
        enqueued += command
    }

    override suspend fun replayOnce() {
        replayOnceCalls++
    }
}
