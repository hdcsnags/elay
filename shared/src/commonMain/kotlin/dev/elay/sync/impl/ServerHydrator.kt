package dev.elay.sync.impl

import dev.elay.data.local.CaptureDao
import dev.elay.data.local.GoalDao
import dev.elay.data.local.MilestoneDao
import dev.elay.data.local.SyncStatus
import dev.elay.data.local.TaskDao
import dev.elay.data.local.TimeBlockDao
import dev.elay.data.local.mapping.toEntity
import dev.elay.data.remote.DataGateway
import dev.elay.data.remote.dto.CaptureDto
import dev.elay.data.remote.dto.GoalDto
import dev.elay.data.remote.dto.MilestoneDto
import dev.elay.data.remote.dto.TaskDto
import dev.elay.data.remote.dto.TimeBlockDto
import dev.elay.data.remote.dto.toDomain
import dev.elay.sync.Aggregate
import dev.elay.util.ElayLog
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Clock

/** A local row in either of these states holds an unsynced edit — hydration must never touch it. */
private val LOCAL_ONLY_STATUSES = setOf(SyncStatus.Pending.wire, SyncStatus.Conflict.wire)

/** [dev.elay.data.local.TimeBlockDao] has no single-row `get` — this range covers the whole table. */
private const val FULL_RANGE_START_MS = Long.MIN_VALUE
private const val FULL_RANGE_END_MS = Long.MAX_VALUE

/**
 * Fresh-install / new-device server-to-local hydration (STATE.md's Honest Gate 1 remainder:
 * "fetchSince exists, nothing calls it — a fresh install can't see server data").
 *
 * For each of the five Phase 1 aggregates, pulls the full authoritative snapshot via
 * [DataGateway.fetchSince] (`sinceVersion = 0` — Phase 1 has no persisted per-account watermark
 * yet, so every hydration is a full resync; cheap enough at this scale, and idempotent since it
 * only ever upserts by primary key), maps DTO -> domain -> the same `data/local/mapping` entity
 * shape every other write path uses, and upserts with `syncStatus = SYNCED`.
 *
 * The one rule that matters (brief §2): a local row already `PENDING` or `CONFLICT` carries an
 * edit the outbox hasn't reconciled yet, and hydration must never clobber it with a stale server
 * snapshot — [isLocalOnly] skips exactly those rows, upserting everything else.
 *
 * Errors are swallowed per aggregate: a network hiccup on one aggregate must not stop the other
 * four, and hydration must never block app startup (the caller fires [hydrateAll] on the app
 * scope, fire-and-forget — see [dev.elay.di.AppGraph]). Diagnosability (concierge 2026-09-12
 * precedent, matching [OutboxSyncCoordinator] and `SignInViewModel`): the real cause still goes
 * to the platform log via `println` for adb/Console diagnosis, it just never surfaces to the UI
 * or throws — a failed hydration quietly retries whole on the next app start.
 */
@Suppress("LongParameterList") // one DAO per aggregate, same shape as LocalFirstPlannerRepository
class ServerHydrator(
    private val dataGateway: DataGateway,
    private val goalDao: GoalDao,
    private val milestoneDao: MilestoneDao,
    private val taskDao: TaskDao,
    private val captureDao: CaptureDao,
    private val timeBlockDao: TimeBlockDao,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Runs all five aggregate hydrations. Never throws (see class doc). */
    suspend fun hydrateAll() {
        hydrateGoals()
        hydrateMilestones()
        hydrateTasks()
        hydrateCaptures()
        hydrateTimeBlocks()
    }

    private suspend fun hydrateGoals() =
        fetchAndApply(Aggregate.Goal) { rows ->
            rows.forEach { row ->
                val dto = json.decodeFromJsonElement<GoalDto>(row)
                val existing = goalDao.get(dto.id)
                if (isLocalOnly(existing?.syncStatus)) return@forEach
                goalDao.upsert(dto.toDomain().toEntity(SyncStatus.Synced, now()))
            }
        }

    private suspend fun hydrateMilestones() =
        fetchAndApply(Aggregate.Milestone) { rows ->
            rows.forEach { row ->
                val dto = json.decodeFromJsonElement<MilestoneDto>(row)
                val existing = milestoneDao.get(dto.id)
                if (isLocalOnly(existing?.syncStatus)) return@forEach
                milestoneDao.upsert(dto.toDomain().toEntity(SyncStatus.Synced, now()))
            }
        }

    private suspend fun hydrateTasks() =
        fetchAndApply(Aggregate.Task) { rows ->
            rows.forEach { row ->
                val dto = json.decodeFromJsonElement<TaskDto>(row)
                val existing = taskDao.get(dto.id)
                if (isLocalOnly(existing?.syncStatus)) return@forEach
                taskDao.upsert(dto.toDomain().toEntity(SyncStatus.Synced, now()))
            }
        }

    private suspend fun hydrateCaptures() =
        fetchAndApply(Aggregate.Capture) { rows ->
            rows.forEach { row ->
                val dto = json.decodeFromJsonElement<CaptureDto>(row)
                val existing = captureDao.get(dto.id)
                if (isLocalOnly(existing?.syncStatus)) return@forEach
                captureDao.upsert(dto.toDomain().toEntity(SyncStatus.Synced, now()))
            }
        }

    /** No `TimeBlockDao.get(id)` exists (read-only `data/local` grant) — index the whole table once. */
    private suspend fun hydrateTimeBlocks() =
        fetchAndApply(Aggregate.TimeBlock) { rows ->
            if (rows.isEmpty()) return@fetchAndApply
            val localById =
                timeBlockDao.observeRange(FULL_RANGE_START_MS, FULL_RANGE_END_MS).first().associateBy { it.id }
            rows.forEach { row ->
                val dto = json.decodeFromJsonElement<TimeBlockDto>(row)
                val existing = localById[dto.id]
                if (isLocalOnly(existing?.syncStatus)) return@forEach
                timeBlockDao.upsert(dto.toDomain().toEntity(SyncStatus.Synced, now()))
            }
        }

    private suspend fun fetchAndApply(
        aggregate: Aggregate,
        applyRows: suspend (List<JsonObject>) -> Unit,
    ) {
        runCatching {
            val rows = dataGateway.fetchSince(aggregate.wire, sinceVersion = 0).getOrThrow()
            applyRows(rows)
        }.onFailure { error ->
            // Diagnosability (concierge 2026-09-12 precedent): never throws, never blocks
            // startup, but the cause still reaches the platform log (debug builds only —
            // release-stripped via ElayLog, stage5 §A MASVS checklist).
            ElayLog.w("Hydration") {
                "ELAY hydration failure: ${aggregate.wire} ${error::class.simpleName}"
            }
        }
    }
}

private fun isLocalOnly(syncStatus: String?): Boolean = syncStatus in LOCAL_ONLY_STATUSES
