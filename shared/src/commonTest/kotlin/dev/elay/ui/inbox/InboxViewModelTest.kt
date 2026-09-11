package dev.elay.ui.inbox

import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.CaptureSource
import dev.elay.domain.model.ParseStatus
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.UserId
import dev.elay.ui.fake.FakePlannerRepository
import dev.elay.ui.fake.PlannerSeed
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InboxViewModelTest {
    private val ownerId = UserId("owner-1")

    @Test
    fun clarifyingACaptureCreatesATaskAndMovesTheCaptureOutOfTheInbox() =
        runTest {
            val capture = capture("c-1", "Call the dentist")
            val repository = fakeRepository(captures = listOf(capture))
            val viewModel = InboxViewModel(repository, backgroundScope, ownerId = ownerId, newId = { "task-1" })
            runCurrent()

            viewModel.clarifyToTask(capture)
            runCurrent()

            assertTrue(
                viewModel.state.value.captures
                    .none { it.id == capture.id },
                "clarified capture leaves the inbox",
            )
            val createdTask = repository.task(TaskId("task-1"))
            assertEquals("Call the dentist", createdTask?.title)
        }

    @Test
    fun quickAddCapturesTextAsAnUnparsedCaptureAndClearsTheField() =
        runTest {
            val repository = fakeRepository()
            val viewModel = InboxViewModel(repository, backgroundScope, ownerId = ownerId, newId = { "capture-1" })
            runCurrent()

            viewModel.onQuickAddTextChanged("  Buy milk  ")
            viewModel.captureQuickAdd()
            runCurrent()

            assertEquals("", viewModel.state.value.quickAddText)
            val captured =
                viewModel.state.value.captures
                    .single()
            assertEquals("Buy milk", captured.body)
            assertEquals(ParseStatus.Unparsed, captured.parseStatus)
        }

    @Test
    fun blankQuickAddTextIsIgnored() =
        runTest {
            val repository = fakeRepository()
            val viewModel = InboxViewModel(repository, backgroundScope, ownerId = ownerId)
            runCurrent()

            viewModel.onQuickAddTextChanged("   ")
            viewModel.captureQuickAdd()
            runCurrent()

            assertTrue(
                viewModel.state.value.captures
                    .isEmpty(),
            )
        }

    @Test
    fun dismissRemovesACaptureFromTheInbox() =
        runTest {
            val capture = capture("c-1", "Look up flight prices")
            val repository = fakeRepository(captures = listOf(capture))
            val viewModel = InboxViewModel(repository, backgroundScope, ownerId = ownerId)
            runCurrent()

            viewModel.dismiss(capture)
            runCurrent()

            assertTrue(
                viewModel.state.value.captures
                    .isEmpty(),
            )
        }

    @Test
    fun inboxListsNewestCapturesFirst() =
        runTest {
            val older = capture("c-old", "Older", capturedAt = Instant.fromEpochMilliseconds(1_000))
            val newer = capture("c-new", "Newer", capturedAt = Instant.fromEpochMilliseconds(2_000))
            val repository = fakeRepository(captures = listOf(older, newer))
            val viewModel = InboxViewModel(repository, backgroundScope, ownerId = ownerId)
            runCurrent()

            assertEquals(
                listOf(newer.id, older.id),
                viewModel.state.value.captures
                    .map { it.id },
            )
        }

    private fun capture(
        id: String,
        body: String,
        capturedAt: Instant = Instant.fromEpochMilliseconds(0),
    ) = Capture(
        id = CaptureId(id),
        ownerId = ownerId,
        body = body,
        source = CaptureSource.Quick,
        parseStatus = ParseStatus.Unparsed,
        capturedAt = capturedAt,
        clarifiedTaskId = null,
        version = 1,
    )

    private fun fakeRepository(captures: List<Capture> = emptyList()) =
        FakePlannerRepository(
            PlannerSeed(
                goals = emptyList(),
                milestones = emptyList(),
                tasks = emptyList(),
                captures = captures,
                blocks = emptyList(),
            ),
        )
}
