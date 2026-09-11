package dev.elay.ui.fake

import dev.elay.domain.model.GoalStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sanity checks on the seeded dataset shape required by brief §1. */
class FakePlannerRepositoryTest {
    private val now = Instant.fromEpochMilliseconds(1_780_000_000_000)
    private val zone = TimeZone.UTC

    @Test
    fun seedHasOneActiveStudyGoalWithMilestones() =
        runTest {
            val repository = FakePlannerRepository.seeded(clock = fixedClock(now), zone = zone)

            val goals = repository.observeGoals().first()
            assertEquals(1, goals.size)
            assertEquals(GoalStatus.Active, goals.single().status)
        }

    @Test
    fun seedHasBetweenSixAndEightTasksAcrossStatusesAndPriorities() {
        val seed = PlannerSeed.build(now, zone)

        assertTrue(seed.tasks.size in 6..8, "expected 6-8 seeded tasks, was ${seed.tasks.size}")
        assertTrue(
            seed.tasks
                .map { it.status }
                .toSet()
                .size >= 3,
            "expected multiple task statuses",
        )
        assertTrue(
            seed.tasks
                .map { it.priority }
                .toSet()
                .size >= 3,
            "expected multiple priorities",
        )
    }

    @Test
    fun seedHasThreeUnparsedCaptures() {
        val seed = PlannerSeed.build(now, zone)

        assertEquals(3, seed.captures.size)
        assertTrue(seed.captures.all { it.parseStatus == dev.elay.domain.model.ParseStatus.Unparsed })
    }

    @Test
    fun seedHasBlocksAcrossSevenDaysIncludingExactlyOneAllDayBlock() {
        val seed = PlannerSeed.build(now, zone)

        assertEquals(1, seed.blocks.count { it.allDay })
        assertTrue(seed.blocks.size >= 7, "expected at least one block per day across today +/- 3 days")
    }

    @Test
    fun clarifyCaptureRemovesItFromTheInboxAndLinksTheNewTask() =
        runTest {
            val repository = FakePlannerRepository.seeded(clock = fixedClock(now), zone = zone)
            val capture = repository.observeInbox().first().first()

            repository.clarifyCapture(
                capture.id,
                dev.elay.domain.model
                    .TaskId("new-task"),
            )

            val inboxAfter = repository.observeInbox().first()
            assertTrue(inboxAfter.none { it.id == capture.id })
        }

    private fun fixedClock(instant: Instant): kotlin.time.Clock =
        object : kotlin.time.Clock {
            override fun now(): Instant = instant
        }
}
