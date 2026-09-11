package dev.elay.data.local.mapping

import dev.elay.data.local.SyncStatus
import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.GoalStatus
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalMapperTest {
    @Test
    fun roundTripsEveryStatus() {
        for (status in GoalStatus.entries) {
            val goal =
                Goal(
                    id = GoalId("g-1"),
                    ownerId = UserId("u-1"),
                    title = "Title",
                    notes = "notes",
                    targetDate = LocalDate(2026, 12, 31),
                    status = status,
                    version = 3,
                    createdAt = Instant.fromEpochMilliseconds(1_000),
                    updatedAt = Instant.fromEpochMilliseconds(2_000),
                )
            val entity = goal.toEntity(SyncStatus.Pending, localUpdatedAtEpochMs = 5_000)
            assertEquals(status.wire, entity.status)
            assertEquals(goal, entity.toDomain())
        }
    }

    @Test
    fun roundTripsNullableFields() {
        val goal =
            Goal(
                id = GoalId("g-2"),
                ownerId = UserId("u-2"),
                title = "No notes",
                notes = null,
                targetDate = null,
                status = GoalStatus.Active,
                version = 1,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            )
        val entity = goal.toEntity(SyncStatus.Synced, localUpdatedAtEpochMs = 0)
        assertEquals(goal, entity.toDomain())
    }
}
