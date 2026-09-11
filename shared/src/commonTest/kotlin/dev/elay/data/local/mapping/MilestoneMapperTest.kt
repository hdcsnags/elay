package dev.elay.data.local.mapping

import dev.elay.data.local.SyncStatus
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.MilestoneId
import dev.elay.domain.model.MilestoneStatus
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MilestoneMapperTest {
    @Test
    fun roundTripsEveryStatus() {
        for (status in MilestoneStatus.entries) {
            val milestone =
                Milestone(
                    id = MilestoneId("m-1"),
                    goalId = GoalId("g-1"),
                    title = "Milestone",
                    targetDate = LocalDate(2026, 6, 15),
                    sortOrder = 2,
                    status = status,
                    version = 4,
                )
            val entity = milestone.toEntity(SyncStatus.Pending, localUpdatedAtEpochMs = 10)
            assertEquals(status.wire, entity.status)
            assertEquals(milestone, entity.toDomain())
        }
    }

    @Test
    fun roundTripsNullTargetDate() {
        val milestone =
            Milestone(
                id = MilestoneId("m-2"),
                goalId = GoalId("g-2"),
                title = "No date",
                targetDate = null,
                sortOrder = 0,
                status = MilestoneStatus.Pending,
                version = 1,
            )
        val entity = milestone.toEntity(SyncStatus.Synced, localUpdatedAtEpochMs = 0)
        assertEquals(milestone, entity.toDomain())
    }
}
