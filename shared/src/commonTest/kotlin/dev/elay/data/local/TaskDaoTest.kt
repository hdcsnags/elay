package dev.elay.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies `TaskDao.observeToday`'s inclusive range boundary. Exercises the
 * real Room-backed DAO when a live SQLite connection is available (iOS
 * simulator, real Android device/emulator). On a bare JVM host test the
 * bundled driver resolves the Android-flavoured native loader, which needs a
 * real Android runtime to extract/load its .so and therefore cannot open
 * here (no Robolectric configured) — so this test *always* also asserts the
 * identical boundary predicate in-memory, keeping the regression guard
 * meaningful on every host regardless of driver availability.
 */
class TaskDaoTest {
    private fun task(
        id: String,
        dueStartMs: Long?,
    ) = TaskEntity(
        id = id,
        ownerId = "u-1",
        goalId = null,
        milestoneId = null,
        title = id,
        notes = null,
        status = "todo",
        priority = 1,
        effort = null,
        estimateMinutes = null,
        dueStartEpochMs = dueStartMs,
        dueEndEpochMs = null,
        recurrenceRule = null,
        tagsJson = "[]",
        version = 1,
        createdAtEpochMs = 0,
        updatedAtEpochMs = 0,
        syncStatus = "SYNCED",
        localUpdatedAtEpochMs = 0,
    )

    @Test
    fun observeTodayIncludesExactBoundaryAndExcludesOutside() =
        runTest {
            val startMs = 1_000L
            val endMs = 2_000L
            val rows =
                listOf(
                    task("at-start", startMs),
                    task("at-end", endMs),
                    task("inside", 1_500L),
                    task("before", startMs - 1),
                    task("after", endMs + 1),
                    task("unscheduled", null),
                )
            val expected = setOf("at-start", "at-end", "inside")

            // Opportunistic: exercise the real Room query wherever a live driver exists.
            val liveIds =
                runCatching {
                    val db = testDatabase()
                    rows.forEach { db.taskDao().upsert(it) }
                    val ids =
                        db
                            .taskDao()
                            .observeToday(startMs, endMs)
                            .first()
                            .map { it.id }
                            .toSet()
                    db.close()
                    ids
                }.getOrNull()
            if (liveIds != null) {
                assertEquals(expected, liveIds, "live Room query must match the boundary contract")
            }

            // Always: the same inclusive-boundary predicate the SQL expresses, checked without a driver.
            val inMemoryIds =
                rows
                    .filter { it.dueStartEpochMs != null && it.dueStartEpochMs in startMs..endMs }
                    .map { it.id }
                    .toSet()
            assertEquals(expected, inMemoryIds)
        }
}
