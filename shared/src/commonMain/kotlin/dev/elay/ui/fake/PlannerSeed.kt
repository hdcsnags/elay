package dev.elay.ui.fake

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.CaptureSource
import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.GoalStatus
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.MilestoneId
import dev.elay.domain.model.MilestoneStatus
import dev.elay.domain.model.ParseStatus
import dev.elay.domain.model.Priority
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.ui.util.LOCAL_OWNER_ID
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** In-memory dataset backing [FakePlannerRepository] — realistic, not exhaustive (brief §1). */
internal data class PlannerSeed(
    val goals: List<Goal>,
    val milestones: List<Milestone>,
    val tasks: List<Task>,
    val captures: List<Capture>,
    val blocks: List<TimeBlock>,
) {
    companion object {
        @Suppress("LongMethod")
        fun build(
            now: Instant,
            zone: TimeZone,
        ): PlannerSeed {
            val today = now.toLocalDateTime(zone).date
            fun at(
                dayOffset: Int,
                hour: Int,
                minute: Int,
            ): Instant = today.plus(dayOffset, DateTimeUnit.DAY).atTime(LocalTime(hour, minute)).toInstant(zone)

            val goal =
                Goal(
                    id = GoalId("goal-study-1"),
                    ownerId = LOCAL_OWNER_ID,
                    title = "Finish the algorithms course",
                    notes = "One module a week, practice problems daily.",
                    targetDate = today.plus(30, DateTimeUnit.DAY),
                    status = GoalStatus.Active,
                    version = 1,
                    createdAt = now - 20.days,
                    updatedAt = now - 1.days,
                )

            val milestones =
                listOf(
                    Milestone(
                        id = MilestoneId("milestone-module-1"),
                        goalId = goal.id,
                        title = "Complete Module 1: Sorting",
                        targetDate = today.plus(-10, DateTimeUnit.DAY),
                        sortOrder = 0,
                        status = MilestoneStatus.Completed,
                        version = 1,
                    ),
                    Milestone(
                        id = MilestoneId("milestone-module-2"),
                        goalId = goal.id,
                        title = "Complete Module 2: Graphs",
                        targetDate = today.plus(7, DateTimeUnit.DAY),
                        sortOrder = 1,
                        status = MilestoneStatus.Active,
                        version = 1,
                    ),
                    Milestone(
                        id = MilestoneId("milestone-final-project"),
                        goalId = goal.id,
                        title = "Ship the final project",
                        targetDate = today.plus(28, DateTimeUnit.DAY),
                        sortOrder = 2,
                        status = MilestoneStatus.Pending,
                        version = 1,
                    ),
                )

            val tasks =
                listOf(
                    Task(
                        id = TaskId("task-review-notes"),
                        ownerId = LOCAL_OWNER_ID,
                        goalId = goal.id,
                        milestoneId = milestones[1].id,
                        title = "Review lecture notes on graph traversal",
                        notes = null,
                        status = TaskStatus.InProgress,
                        priority = Priority.High,
                        effort = 2,
                        estimateMinutes = 30,
                        dueStart = at(0, 8, 30),
                        dueEnd = at(0, 9, 0),
                        recurrenceRule = null,
                        tags = listOf("study"),
                        version = 1,
                        createdAt = now - 2.days,
                        updatedAt = now - 1.days,
                    ),
                    Task(
                        id = TaskId("task-practice-problems"),
                        ownerId = LOCAL_OWNER_ID,
                        goalId = goal.id,
                        milestoneId = milestones[1].id,
                        title = "Solve five practice problems",
                        notes = null,
                        status = TaskStatus.Todo,
                        priority = Priority.Normal,
                        effort = 3,
                        estimateMinutes = 60,
                        dueStart = at(0, 15, 0),
                        dueEnd = at(0, 16, 0),
                        recurrenceRule = null,
                        tags = listOf("study"),
                        version = 1,
                        createdAt = now - 2.days,
                        updatedAt = now - 2.days,
                    ),
                    Task(
                        id = TaskId("task-email-study-group"),
                        ownerId = LOCAL_OWNER_ID,
                        goalId = goal.id,
                        milestoneId = null,
                        title = "Email the study group about Saturday",
                        notes = null,
                        status = TaskStatus.Todo,
                        priority = Priority.Low,
                        effort = 1,
                        estimateMinutes = 10,
                        dueStart = at(0, 18, 0),
                        dueEnd = null,
                        recurrenceRule = null,
                        tags = emptyList(),
                        version = 1,
                        createdAt = now - 1.days,
                        updatedAt = now - 1.days,
                    ),
                    Task(
                        id = TaskId("task-submit-assignment"),
                        ownerId = LOCAL_OWNER_ID,
                        goalId = goal.id,
                        milestoneId = milestones[1].id,
                        title = "Submit module 2 assignment",
                        notes = "Due before the midnight cutoff.",
                        status = TaskStatus.Todo,
                        priority = Priority.Urgent,
                        effort = 4,
                        estimateMinutes = 90,
                        dueStart = at(1, 20, 0),
                        dueEnd = at(1, 21, 30),
                        recurrenceRule = null,
                        tags = listOf("study", "deadline"),
                        version = 1,
                        createdAt = now - 5.days,
                        updatedAt = now - 5.days,
                    ),
                    Task(
                        id = TaskId("task-buy-groceries"),
                        ownerId = LOCAL_OWNER_ID,
                        goalId = null,
                        milestoneId = null,
                        title = "Buy groceries",
                        notes = null,
                        status = TaskStatus.Todo,
                        priority = Priority.Normal,
                        effort = null,
                        estimateMinutes = null,
                        dueStart = null,
                        dueEnd = null,
                        recurrenceRule = null,
                        tags = listOf("errand"),
                        version = 1,
                        createdAt = now - 3.days,
                        updatedAt = now - 3.days,
                    ),
                    Task(
                        id = TaskId("task-plan-weekend-trip"),
                        ownerId = LOCAL_OWNER_ID,
                        goalId = null,
                        milestoneId = null,
                        title = "Plan the weekend hike route",
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
                        createdAt = now - 1.days,
                        updatedAt = now - 1.days,
                    ),
                    Task(
                        id = TaskId("task-fix-bike"),
                        ownerId = LOCAL_OWNER_ID,
                        goalId = null,
                        milestoneId = null,
                        title = "Fix the bike tire",
                        notes = null,
                        status = TaskStatus.Completed,
                        priority = Priority.Normal,
                        effort = 2,
                        estimateMinutes = 20,
                        dueStart = at(-1, 10, 0),
                        dueEnd = at(-1, 10, 30),
                        recurrenceRule = null,
                        tags = listOf("errand"),
                        version = 2,
                        createdAt = now - 4.days,
                        updatedAt = now - 1.days,
                    ),
                    Task(
                        id = TaskId("task-call-dentist"),
                        ownerId = LOCAL_OWNER_ID,
                        goalId = null,
                        milestoneId = null,
                        title = "Call the dentist to reschedule",
                        notes = null,
                        status = TaskStatus.Cancelled,
                        priority = Priority.High,
                        effort = null,
                        estimateMinutes = 5,
                        dueStart = at(0, 12, 0),
                        dueEnd = null,
                        recurrenceRule = null,
                        tags = emptyList(),
                        version = 2,
                        createdAt = now - 6.days,
                        updatedAt = now - 1.days,
                    ),
                )

            val captures =
                listOf(
                    Capture(
                        id = CaptureId("capture-call-mom"),
                        ownerId = LOCAL_OWNER_ID,
                        body = "Call mom back about the weekend",
                        source = CaptureSource.Quick,
                        parseStatus = ParseStatus.Unparsed,
                        capturedAt = now - 3.days,
                        clarifiedTaskId = null,
                        version = 1,
                    ),
                    Capture(
                        id = CaptureId("capture-flight-prices"),
                        ownerId = LOCAL_OWNER_ID,
                        body = "Look up flight prices for the conference",
                        source = CaptureSource.Quick,
                        parseStatus = ParseStatus.Unparsed,
                        capturedAt = now - 1.days,
                        clarifiedTaskId = null,
                        version = 1,
                    ),
                    Capture(
                        id = CaptureId("capture-hike-idea"),
                        ownerId = LOCAL_OWNER_ID,
                        body = "Idea: scout a new weekend hike route",
                        source = CaptureSource.Voice,
                        parseStatus = ParseStatus.Unparsed,
                        capturedAt = now - 12.hours,
                        clarifiedTaskId = null,
                        version = 1,
                    ),
                )

            val allDayStart = today.atStartOfDayIn(zone)
            val allDayEnd = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
            val blocks =
                listOf(
                    TimeBlock(
                        id = TimeBlockId("block-past-3-review"),
                        ownerId = LOCAL_OWNER_ID,
                        taskId = null,
                        title = "Focused review",
                        startsAt = at(-3, 9, 0),
                        endsAt = at(-3, 10, 0),
                        originTz = zone,
                        type = BlockType.Focus,
                        status = BlockStatus.Completed,
                        recurrenceRule = null,
                        allDay = false,
                        version = 1,
                    ),
                    TimeBlock(
                        id = TimeBlockId("block-past-2-groceries"),
                        ownerId = LOCAL_OWNER_ID,
                        taskId = null,
                        title = "Grocery run",
                        startsAt = at(-2, 17, 0),
                        endsAt = at(-2, 17, 30),
                        originTz = zone,
                        type = BlockType.Personal,
                        status = BlockStatus.Completed,
                        recurrenceRule = null,
                        allDay = false,
                        version = 1,
                    ),
                    TimeBlock(
                        id = TimeBlockId("block-past-1-study-call"),
                        ownerId = LOCAL_OWNER_ID,
                        taskId = null,
                        title = "Study group call",
                        startsAt = at(-1, 19, 0),
                        endsAt = at(-1, 20, 0),
                        originTz = zone,
                        type = BlockType.Personal,
                        status = BlockStatus.Completed,
                        recurrenceRule = null,
                        allDay = false,
                        version = 1,
                    ),
                    TimeBlock(
                        id = TimeBlockId("block-today-deep-work"),
                        ownerId = LOCAL_OWNER_ID,
                        taskId = TaskId("task-review-notes"),
                        title = "Deep work: algorithms",
                        startsAt = at(0, 9, 0),
                        endsAt = at(0, 10, 30),
                        originTz = zone,
                        type = BlockType.Focus,
                        status = BlockStatus.Scheduled,
                        recurrenceRule = null,
                        allDay = false,
                        version = 1,
                    ),
                    TimeBlock(
                        id = TimeBlockId("block-today-standup"),
                        ownerId = LOCAL_OWNER_ID,
                        taskId = null,
                        title = "Team standup",
                        startsAt = at(0, 13, 0),
                        endsAt = at(0, 13, 15),
                        originTz = zone,
                        type = BlockType.Routine,
                        status = BlockStatus.Scheduled,
                        recurrenceRule = "FREQ=DAILY",
                        allDay = false,
                        version = 1,
                    ),
                    TimeBlock(
                        id = TimeBlockId("block-today-all-day"),
                        ownerId = LOCAL_OWNER_ID,
                        taskId = null,
                        title = "No-meetings focus day",
                        startsAt = allDayStart,
                        endsAt = allDayEnd,
                        originTz = zone,
                        type = BlockType.Personal,
                        status = BlockStatus.Scheduled,
                        recurrenceRule = null,
                        allDay = true,
                        version = 1,
                    ),
                    TimeBlock(
                        id = TimeBlockId("block-plus-1-assignment"),
                        ownerId = LOCAL_OWNER_ID,
                        taskId = TaskId("task-submit-assignment"),
                        title = "Assignment submission window",
                        startsAt = at(1, 20, 0),
                        endsAt = at(1, 21, 0),
                        originTz = zone,
                        type = BlockType.Focus,
                        status = BlockStatus.Scheduled,
                        recurrenceRule = null,
                        allDay = false,
                        version = 1,
                    ),
                    TimeBlock(
                        id = TimeBlockId("block-plus-2-hike"),
                        ownerId = LOCAL_OWNER_ID,
                        taskId = null,
                        title = "Weekend hike",
                        startsAt = at(2, 8, 0),
                        endsAt = at(2, 11, 0),
                        originTz = zone,
                        type = BlockType.Personal,
                        status = BlockStatus.Scheduled,
                        recurrenceRule = null,
                        allDay = false,
                        version = 1,
                    ),
                    TimeBlock(
                        id = TimeBlockId("block-plus-3-study-call"),
                        ownerId = LOCAL_OWNER_ID,
                        taskId = null,
                        title = "Study group call",
                        startsAt = at(3, 19, 0),
                        endsAt = at(3, 20, 0),
                        originTz = zone,
                        type = BlockType.Personal,
                        status = BlockStatus.Scheduled,
                        recurrenceRule = null,
                        allDay = false,
                        version = 1,
                    ),
                )

            return PlannerSeed(
                goals = listOf(goal),
                milestones = milestones,
                tasks = tasks,
                captures = captures,
                blocks = blocks,
            )
        }
    }
}
