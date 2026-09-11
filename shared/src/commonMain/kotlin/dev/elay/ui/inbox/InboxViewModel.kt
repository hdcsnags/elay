package dev.elay.ui.inbox

import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.CaptureSource
import dev.elay.domain.model.ParseStatus
import dev.elay.domain.model.Priority
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.UserId
import dev.elay.domain.repository.PlannerRepository
import dev.elay.ui.util.LOCAL_OWNER_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlin.random.Random
import kotlin.time.Clock

/** Inbox surface state (brief §3): "dump now, sort later" — newest capture first. */
data class InboxUiState(
    val captures: List<Capture> = emptyList(),
    val quickAddText: String = "",
) {
    val isEmpty: Boolean get() = captures.isEmpty()
}

private const val CLARIFIED_TASK_TITLE_MAX_LENGTH = 200

/**
 * Plain, testable ViewModel for the Inbox: quick-add capture, clarify a
 * capture into a Task, or dismiss it (spec §2 — capture before organize).
 */
class InboxViewModel(
    private val repository: PlannerRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val ownerId: UserId = LOCAL_OWNER_ID,
    private val newId: () -> String = { defaultNewId() },
) {
    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    init {
        repository
            .observeInbox()
            .onEach { captures -> _state.update { it.copy(captures = captures) } }
            .launchIn(scope)
    }

    fun onQuickAddTextChanged(text: String) {
        _state.update { it.copy(quickAddText = text) }
    }

    /** One tap to capture — no category, project, or priority required up front. */
    fun captureQuickAdd() {
        val body = _state.value.quickAddText.trim()
        if (body.isEmpty()) return
        _state.update { it.copy(quickAddText = "") }
        scope.launch {
            repository.upsertCapture(
                Capture(
                    id = CaptureId(newId()),
                    ownerId = ownerId,
                    body = body,
                    source = CaptureSource.Quick,
                    parseStatus = ParseStatus.Unparsed,
                    capturedAt = clock.now(),
                    clarifiedTaskId = null,
                    version = 0,
                ),
            )
        }
    }

    /** Turns a capture into a real Task, then marks the capture clarified (spec §2 — capture before organize). */
    fun clarifyToTask(capture: Capture) {
        scope.launch {
            val now: Instant = clock.now()
            val task =
                Task(
                    id = TaskId(newId()),
                    ownerId = ownerId,
                    goalId = null,
                    milestoneId = null,
                    title = capture.body.take(CLARIFIED_TASK_TITLE_MAX_LENGTH),
                    notes = null,
                    status = TaskStatus.Todo,
                    priority = Priority.Normal,
                    effort = null,
                    estimateMinutes = null,
                    dueStart = null,
                    dueEnd = null,
                    recurrenceRule = null,
                    tags = emptyList(),
                    version = 0,
                    createdAt = now,
                    updatedAt = now,
                )
            repository.upsertTask(task)
            repository.clarifyCapture(capture.id, task.id)
        }
    }

    fun dismiss(capture: Capture) {
        scope.launch { repository.dismissCapture(capture.id) }
    }
}

private fun defaultNewId(): String = "id-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(RANDOM_ID_BOUND)}"

private const val RANDOM_ID_BOUND = 1_000_000
