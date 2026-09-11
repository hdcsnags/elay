package dev.elay.data.local

/**
 * Local sync bookkeeping for cached planner rows (contracts/phase1-planner.md §3).
 * Not a wire enum — purely local Room state describing outbox reconciliation.
 */
enum class SyncStatus(
    val wire: String,
) {
    Pending("PENDING"),
    Synced("SYNCED"),
    Conflict("CONFLICT"),
}
