package dev.elay.data.local.mapping

import dev.elay.data.local.SyncStatus
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.MilestoneId
import dev.elay.domain.model.Priority
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskMapperTest {
    private fun baseTask(status: TaskStatus) =
        Task(
            id = TaskId("t-1"),
            ownerId = UserId("u-1"),
            goalId = GoalId("g-1"),
            milestoneId = MilestoneId("m-1"),
            title = "Task",
            notes = "notes",
            status = status,
            priority = Priority.Normal,
            effort = 3,
            estimateMinutes = 45,
            dueStart = Instant.fromEpochMilliseconds(1_000),
            dueEnd = Instant.fromEpochMilliseconds(2_000),
            recurrenceRule = "FREQ=DAILY",
            tags = listOf("home", "urgent"),
            version = 2,
            createdAt = Instant.fromEpochMilliseconds(500),
            updatedAt = Instant.fromEpochMilliseconds(1_500),
        )

    @Test
    fun roundTripsEveryStatus() {
        for (status in TaskStatus.entries) {
            val task = baseTask(status)
            val entity = task.toEntity(SyncStatus.Pending, localUpdatedAtEpochMs = 9_000)
            assertEquals(status.wire, entity.status)
            assertEquals(task, entity.toDomain())
        }
    }

    @Test
    fun roundTripsEveryPriority() {
        for (priority in listOf(Priority.Low, Priority.Normal, Priority.High, Priority.Urgent)) {
            val task = baseTask(TaskStatus.Todo).copy(priority = priority)
            val entity = task.toEntity(SyncStatus.Pending, localUpdatedAtEpochMs = 0)
            assertEquals(priority.value, entity.priority)
            assertEquals(task, entity.toDomain())
        }
    }

    @Test
    fun roundTripsNullableAndEmptyTags() {
        val task =
            Task(
                id = TaskId("t-2"),
                ownerId = UserId("u-2"),
                goalId = null,
                milestoneId = null,
                title = "Unscheduled",
                notes = null,
                status = TaskStatus.Todo,
                priority = Priority.Low,
                effort = null,
                estimateMinutes = null,
                dueStart = null,
                dueEnd = null,
                recurrenceRule = null,
                tags = emptyList(),
                version = 1,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            )
        val entity = task.toEntity(SyncStatus.Synced, localUpdatedAtEpochMs = 0)
        assertEquals(task, entity.toDomain())
    }

    @Test
    fun tagsSurviveJsonRoundTrip() {
        val tags = listOf("a", "b", "c with spaces", "")
        assertEquals(tags, decodeTags(encodeTags(tags)))
    }
}
