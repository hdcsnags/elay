package dev.elay.ui.today

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.NextTimeSuggestionResult
import dev.elay.domain.model.RecordOutcomeResult
import dev.elay.domain.model.SessionOutcomeKind
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.repository.OutcomeRepository
import dev.elay.domain.repository.PlannerRepository
import dev.elay.ui.today.fake.sharedFakeOutcomeRepository
import dev.elay.ui.util.dayWindow
import dev.elay.ui.util.dueShiftedByOneDay
import dev.elay.ui.util.shiftedByOneDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The Ran-long pill selector's stepper options (council/stage5-retention-gemini.md §B 1.3 point 4:
 * "a small inline pill selector (+15m, +30m, +45m)... with +15m pre-selected").
 */
val RAN_LONG_DELTA_OPTIONS_MINUTES: List<Int> = listOf(15, 30, 45)

/** §B 1.3 point 4's pre-selected pill and this seat's "Finished early" one-tap preset — neither
 * chip has actual_minutes typed by the user (§B 1.3's "No Mandatory Sub-Sheets"), so
 * contracts/stage5-retention-hardening.md's fallback rule ("§B's stepper or presets if specified,
 * else planned±15m quick options") is exactly ±15m here: +15m is §B's own stepper default, and
 * -15m is this seat's honest "no stepper specified for Finished Early" fallback (§B only specifies
 * a pill selector for Ran Long — 1.3 point 4 names no equivalent for Finished Early). */
private const val DEFAULT_ADJUSTMENT_DELTA_MINUTES = 15

/** Floor so a "-15m" preset never records a nonsensical zero/negative duration for a very short
 * block — calm, not a validation error surfaced to the user. */
private const val MIN_ACTUAL_MINUTES = 1

/** §B 1.4 "Time-to-Live (TTL): The wrap-up prompt remains visible for at most 4 hours post-session." */
private val WRAP_UP_TTL = 4.hours

/** §B 1.3 point 4: "confirmable with a second tap or auto-saved after 3 seconds of inactivity." */
private val ADJUSTMENT_AUTO_SAVE_DELAY = 3.seconds

/** §B 1.3 point 2: "The card collapses inline with a gentle 250ms fade into a single calm
 * confirmation label... persists for 2.5 seconds." This ViewModel owns only the timing (the fade
 * itself is the Compose layer's job); it clears [TodayUiState.wrapUpConfirmationBlockId] after
 * this delay. */
private val WRAP_UP_CONFIRMATION_DURATION = 2.5.seconds

/** Today surface state (brief §2): the day's blocks, top focus tasks, and the inbox count chip.
 * Stage 5 (contracts/stage5-retention-hardening.md, this seat's grant) adds the post-session
 * feedback moment and next-time suggestion surfaces per council/stage5-retention-gemini.md §B. */
data class TodayUiState(
    val zone: TimeZone = TimeZone.currentSystemDefault(),
    val now: Instant? = null,
    val blocks: List<TimeBlock> = emptyList(),
    val focusTasks: List<Task> = emptyList(),
    val inboxCount: Int = 0,
    /** Block ids whose outcome has already been recorded THIS session (§B 1.4 "Zero Shame
     * Backlog" — once recorded, the wrap-up row never returns for that block; there is no
     * server-side "list my outcomes" read on the frozen [OutcomeRepository] surface to re-derive
     * this durably across app restarts, so it is deliberately session-only, same as
     * [dismissedWrapUpBlockIds]). */
    val recordedOutcomeBlockIds: Set<String> = emptySet(),
    /** Block ids the user explicitly dismissed via "Dismiss" (§B 1.4 "Explicit Dismissal") —
     * session-only, never a persisted penalty. */
    val dismissedWrapUpBlockIds: Set<String> = emptySet(),
    /** Non-null for [WRAP_UP_CONFIRMATION_DURATION] right after a chip records successfully (§B
     * 1.3 point 2's "Noted for next time." label) — the block id lets the card render its own
     * confirmation in place rather than a second block's. */
    val wrapUpConfirmationBlockId: String? = null,
    /** The Ran-long pill selector's in-progress state (§B 1.3 point 4) — `null` means no selector
     * is expanded. */
    val pendingRanLongAdjustment: PendingRanLongAdjustmentUiState? = null,
    /** The Next-Time card (§B §2), or `null` when nothing to suggest — see
     * [TodayViewModel.refreshNextTimeSuggestion]. */
    val nextTimeCard: NextTimeCardUiState? = null,
    /** Block ids whose Next-Time card was skipped/acted upon this session (§B 2.3 "Suppression":
     * "does not reappear until a subsequent session outcome is logged" — session-only, same
     * rationale as [dismissedWrapUpBlockIds]). */
    val dismissedNextTimeCardBlockIds: Set<String> = emptySet(),
) {
    /** The block in progress right now, if any — excludes [BlockStatus.Completed]/
     * [BlockStatus.Cancelled] blocks so a block already finished (or called off) never offers
     * Complete again (Gate 1 deferred bug: "Up next" offering Complete on a done block). */
    val currentBlock: TimeBlock?
        get() =
            now?.let { moment ->
                blocks
                    .asSequence()
                    .filter { it.status !in FINISHED_BLOCK_STATUSES }
                    .firstOrNull { moment >= it.startsAt && moment < it.endsAt }
            }

    /** The soonest upcoming block, used when nothing is in progress — same completed/cancelled
     * exclusion as [currentBlock]. */
    val nextBlock: TimeBlock?
        get() =
            now?.let { moment ->
                blocks
                    .asSequence()
                    .filter { it.status !in FINISHED_BLOCK_STATUSES && it.startsAt > moment }
                    .minByOrNull { it.startsAt }
            }

    /**
     * The block the post-session wrap-up row applies to right now (council/stage5-retention-gemini.md
     * §B 1.1's two trigger conditions): a [BlockStatus.Completed] block (the user's own "Complete"
     * tap — possibly before [TimeBlock.endsAt], i.e. finished early) or a still-[BlockStatus.Scheduled]
     * block whose window has elapsed, within §B 1.4's 4-hour TTL and never past the day it ended
     * (a stale day-old block silently expires rather than nagging tomorrow). Already-recorded or
     * dismissed blocks are excluded. §B 1.1: "If multiple blocks elapse simultaneously, only the
     * most recently ended block displays wrap-up prompts" — [maxByOrNull] picks that one; the
     * others fall back to the plain schedule row without stacking (§B 1.4 "Zero Shame Backlog").
     */
    val wrapUpBlock: TimeBlock?
        get() =
            now?.let { moment ->
                blocks
                    .asSequence()
                    .filter { it.id.value !in recordedOutcomeBlockIds && it.id.value !in dismissedWrapUpBlockIds }
                    .filter { block ->
                        when (block.status) {
                            BlockStatus.Completed -> true
                            BlockStatus.Scheduled ->
                                moment >= block.endsAt &&
                                    moment - block.endsAt <= WRAP_UP_TTL &&
                                    moment.toLocalDateTime(zone).date == block.endsAt.toLocalDateTime(zone).date
                            BlockStatus.Cancelled -> false
                        }
                    }.maxByOrNull { it.endsAt }
            }

    val isEmpty: Boolean get() = blocks.isEmpty() && focusTasks.isEmpty()
}

/** The Ran-long pill selector's live state (council/stage5-retention-gemini.md §B 1.3 point 4). */
data class PendingRanLongAdjustmentUiState(
    val blockId: String,
    val selectedDeltaMinutes: Int,
)

/**
 * The Next-Time card (council/stage5-retention-gemini.md §B §2) for one upcoming [blockId]/[title]:
 * [standardMinutes] is that block's own current planned duration (client-known, never invented);
 * [suggestedMinutes] is [dev.elay.domain.model.NextTimeSuggestion.suggestedMinutes] as returned by
 * the server, only ever populated when non-null (the server's sample_size>=2 honesty threshold —
 * contracts/stage5-retention-hardening.md, this seat's grant: "never invent one client-side") AND
 * different from [standardMinutes] (§B 2.3 appearance criterion 1: "a completed lock has a
 * recorded session_outcome with variance" — no variance, nothing to suggest). [isSharedLock]
 * mirrors [BlockType.SharedLock] — §B 2.2's overline is `"NEXT TIME TOGETHER"` for the Together
 * partner-session illustration, but this seat's [dev.elay.domain.model.NextTimeSuggestion] carries
 * no partner display name (that lives in `ui/together`'s [dev.elay.domain.model.PairMember], out
 * of this grant's reach), so the copy layer (see [dev.elay.ui.today.nextTimeAccessibilityDescription])
 * only borrows the "Together" framing for an actual [BlockType.SharedLock] block and falls back to
 * a partner-agnostic "Next time" label for a personal block — UNVERIFIED/flagged for the lead
 * (partner-name threading would need `ui/together`'s `PairRepository`, a cross-grant reach this
 * seat avoids per the contract's "grant only" constraint).
 */
data class NextTimeCardUiState(
    val blockId: String,
    val title: String,
    val standardMinutes: Int,
    val suggestedMinutes: Int,
    val isSharedLock: Boolean,
)

/** Max focus tasks shown on Today (spec §2 — "max 3 focus outcomes", priority overload guardrail). */
const val MAX_FOCUS_TASKS = 3

/** Blocks in either of these statuses are never a candidate for "happening now" / "up next". */
private val FINISHED_BLOCK_STATUSES = setOf(BlockStatus.Completed, BlockStatus.Cancelled)

/**
 * Plain, testable ViewModel (no android.lifecycle dependency): owns Today's
 * observation of the repository and the Complete / Not-today actions, plus Stage 5's plan-vs-actual
 * outcome recording and next-time suggestion (contracts/stage5-retention-hardening.md, this seat's
 * grant; council/stage5-retention-gemini.md §B). One small action per wrap-up chip/next-time
 * affordance below widens the function count (house pattern, e.g. TogetherProposalViewModel).
 */
@Suppress("TooManyFunctions")
class TodayViewModel(
    private val repository: PlannerRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val outcomeRepository: OutcomeRepository = sharedFakeOutcomeRepository,
    private val newOperationId: () -> String = { defaultOutcomeOperationId() },
) {
    private val _state = MutableStateFlow(TodayUiState(zone = zone))
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    /** Monotonic guards so a superseded pill-selection/auto-save/confirmation-timer never clobbers
     * a newer one — same "requestId" idiom as [dev.elay.ui.together.proposal.TogetherProposalViewModel]. */
    private var adjustmentRequestId = 0
    private var confirmationRequestId = 0
    private var suggestionRequestId = 0

    init {
        val today = clock.now().toLocalDateTime(zone).date
        val window = dayWindow(today, zone)
        combine(
            repository.observeBlocks(window.start, window.endExclusive),
            repository.observeTodayTasks(window.start, window.endInclusive),
            repository.observeInbox(),
        ) { blocks, tasks, inbox -> Triple(blocks, tasks, inbox) }
            .onEach { (blocks, tasks, inbox) ->
                _state.update {
                    it.copy(
                        now = clock.now(),
                        blocks = blocks.sortedBy { block -> block.startsAt },
                        focusTasks = tasks.activeFocusOrder(),
                        inboxCount = inbox.size,
                    )
                }
            }.launchIn(scope)
    }

    /** Refreshes the "now" marker so countdown text stays current between repository emissions. */
    fun refreshNow() {
        _state.update { it.copy(now = clock.now()) }
    }

    fun completeBlock(block: TimeBlock) {
        scope.launch { repository.upsertBlock(block.copy(status = BlockStatus.Completed)) }
    }

    /** Reschedules rather than fails the plan (spec §2 — no shame mechanics). */
    fun notTodayBlock(block: TimeBlock) {
        scope.launch { repository.upsertBlock(block.shiftedByOneDay(zone)) }
    }

    fun completeTask(task: Task) {
        scope.launch { repository.upsertTask(task.copy(status = TaskStatus.Completed)) }
    }

    fun notTodayTask(task: Task) {
        scope.launch { repository.upsertTask(task.dueShiftedByOneDay(zone)) }
    }

    // --- Post-session wrap-up (§B §1) -------------------------------------------------------

    /** "Finished early" one-tap chip (§B 1.2) — no stepper specified for this chip (§B 1.3 point 4
     * only specifies one for Ran Long), so per the contract's fallback rule this records the
     * planned duration minus the house's ±15m preset immediately, one tap, no selector. */
    fun recordFinishedEarly(block: TimeBlock) {
        val actual = (block.plannedMinutes() - DEFAULT_ADJUSTMENT_DELTA_MINUTES).coerceAtLeast(MIN_ACTUAL_MINUTES)
        recordDurationOutcome(block, SessionOutcomeKind.FinishedEarly, actual)
    }

    /** "Right on time" one-tap chip (§B 1.2) — [SessionOutcomeKind] is a closed four-value set with
     * no "on time" member (contracts/stage5-retention-hardening.md's adopted schema), so this folds
     * into [SessionOutcomeKind.FinishedEarly] with `actualMinutes == plannedMinutes` (a zero delta)
     * — the closest neutral kind, never [SessionOutcomeKind.RanLong] which implies overage.
     * UNVERIFIED against A7's SQL/pgTAP (a schema/copy seam neither lane's frozen surface
     * anticipated); flagged for the lead's merge diff. */
    fun recordOnTime(block: TimeBlock) {
        recordDurationOutcome(block, SessionOutcomeKind.FinishedEarly, block.plannedMinutes())
    }

    /** "Ran long" chip's first tap (§B 1.3 point 4) — expands the pill selector pre-selected at
     * [DEFAULT_ADJUSTMENT_DELTA_MINUTES] and starts the 3-second auto-save countdown. */
    fun beginRanLongAdjustment(block: TimeBlock) {
        val requestId = ++adjustmentRequestId
        _state.update {
            it.copy(
                pendingRanLongAdjustment =
                    PendingRanLongAdjustmentUiState(block.id.value, DEFAULT_ADJUSTMENT_DELTA_MINUTES),
            )
        }
        scheduleAdjustmentAutoSave(block, requestId)
    }

    /** Picking a different pill (+15m/+30m/+45m) — restarts the 3-second inactivity window. */
    fun selectRanLongDelta(
        block: TimeBlock,
        deltaMinutes: Int,
    ) {
        val requestId = ++adjustmentRequestId
        _state.update {
            it.copy(
                pendingRanLongAdjustment =
                    it.pendingRanLongAdjustment
                        ?.takeIf { pending -> pending.blockId == block.id.value }
                        ?.copy(selectedDeltaMinutes = deltaMinutes),
            )
        }
        scheduleAdjustmentAutoSave(block, requestId)
    }

    /** "Confirmable with a second tap" (§B 1.3 point 4) — records immediately with whatever pill is
     * currently selected, pre-empting the auto-save timer. */
    fun confirmRanLongAdjustment(block: TimeBlock) {
        adjustmentRequestId++ // invalidate any in-flight auto-save
        commitRanLongAdjustment(block)
    }

    /** Collapses the pill selector without recording — the row itself is still there to retry
     * (§B 1.3's "no mandatory sub-sheet" cuts both ways: backing out is free, never a forced
     * choice). */
    fun cancelRanLongAdjustment() {
        adjustmentRequestId++
        _state.update { it.copy(pendingRanLongAdjustment = null) }
    }

    /** "Didn't happen" (neutral, non-punitive per §B 1.2) — no `actualMinutes` (the contract's
     * null-iff rule: only ran_long/finished_early carry one). */
    fun recordDidntHappen(block: TimeBlock) {
        recordOutcome(block, SessionOutcomeKind.DidntHappen, actualMinutes = null)
    }

    /** "Reschedule" (§B 1.2) — records the [SessionOutcomeKind.Rescheduled] fact (the closed set's
     * fourth member) but is otherwise a LABEL, never an auto-proposal (contract's binding §A
     * failure mode 2: "no new realtime event or auto-proposal from `rescheduled`"): this ViewModel
     * has no reach into `ui/together`'s proposal composer, so the actual "move this" flow is a
     * navigation nudge the caller supplies (see [dev.elay.ui.today.TodayScreen]'s
     * `onRescheduleBlock`), never something recording an outcome triggers by itself. */
    fun recordReschedule(block: TimeBlock) {
        recordOutcome(block, SessionOutcomeKind.Rescheduled, actualMinutes = null)
    }

    /** "Dismiss"/"×" (§B 1.4 "Explicit Dismissal") — drops the prompt for this block, no penalty,
     * never re-shown this session. */
    fun dismissWrapUp(block: TimeBlock) {
        _state.update { it.copy(dismissedWrapUpBlockIds = it.dismissedWrapUpBlockIds + block.id.value) }
    }

    /** Clears the transient "Noted for next time." label early (e.g. the user scrolled away) —
     * §B 1.3 point 3's other dismissal path; the timer in [onOutcomeApplied] handles the plain
     * 2.5-second expiry. */
    fun dismissWrapUpConfirmation() {
        confirmationRequestId++
        _state.update { it.copy(wrapUpConfirmationBlockId = null) }
    }

    private fun recordDurationOutcome(
        block: TimeBlock,
        kind: SessionOutcomeKind,
        actualMinutes: Int,
    ) = recordOutcome(block, kind, actualMinutes)

    private fun recordOutcome(
        block: TimeBlock,
        kind: SessionOutcomeKind,
        actualMinutes: Int?,
    ) {
        scope.launch {
            when (outcomeRepository.recordOutcome(newOperationId(), block.id.value, kind, actualMinutes)) {
                is RecordOutcomeResult.Applied -> onOutcomeApplied(block)
                // Calm-by-default (contract: "lost outcome = lost nicety, never planner data") —
                // the row simply stays put so the same tap is a free retry; no error banner (§B's
                // "never a red banner" tone rule).
                is RecordOutcomeResult.Failed -> Unit
            }
        }
    }

    private fun onOutcomeApplied(block: TimeBlock) {
        val requestId = ++confirmationRequestId
        _state.update {
            it.copy(
                recordedOutcomeBlockIds = it.recordedOutcomeBlockIds + block.id.value,
                wrapUpConfirmationBlockId = block.id.value,
                pendingRanLongAdjustment = it.pendingRanLongAdjustment?.takeUnless { p -> p.blockId == block.id.value },
            )
        }
        scope.launch {
            delay(WRAP_UP_CONFIRMATION_DURATION)
            if (requestId != confirmationRequestId) return@launch // superseded by a newer outcome/dismissal
            _state.update { it.copy(wrapUpConfirmationBlockId = null) }
        }
    }

    private fun commitRanLongAdjustment(block: TimeBlock) {
        val delta =
            _state.value.pendingRanLongAdjustment
                ?.takeIf { it.blockId == block.id.value }
                ?.selectedDeltaMinutes
                ?: DEFAULT_ADJUSTMENT_DELTA_MINUTES
        _state.update { it.copy(pendingRanLongAdjustment = null) }
        recordDurationOutcome(block, SessionOutcomeKind.RanLong, block.plannedMinutes() + delta)
    }

    private fun scheduleAdjustmentAutoSave(
        block: TimeBlock,
        requestId: Int,
    ) {
        scope.launch {
            delay(ADJUSTMENT_AUTO_SAVE_DELAY)
            if (requestId != adjustmentRequestId) return@launch // superseded by a later pill tap/confirm/cancel
            commitRanLongAdjustment(block)
        }
    }

    // --- Next-time suggestion (§B §2) --------------------------------------------------------

    /**
     * Loads the Next-Time card for [block] (contracts/stage5-retention-hardening.md, this seat's
     * grant: "call `nextTimeSuggestion(titleKey = block.title)` ... when rendering") — callers
     * (the composable) trigger this once per rendered candidate block, same explicit-refresh idiom
     * as [dev.elay.ui.together.proposal.TogetherProposalViewModel.refreshComposerHints] ("§B
     * 'inform, never nag' via a simple explicit refresh"). A no-op if [block]'s card was already
     * skipped/acted upon this session (§B 2.3 "Suppression").
     */
    fun refreshNextTimeSuggestion(block: TimeBlock) {
        if (block.id.value in _state.value.dismissedNextTimeCardBlockIds) return
        val requestId = ++suggestionRequestId
        scope.launch {
            val result = outcomeRepository.nextTimeSuggestion(taskId = block.taskId?.value, titleKey = block.title)
            if (requestId != suggestionRequestId) return@launch // superseded by a later block/refresh
            val suggestedMinutes = (result as? NextTimeSuggestionResult.Loaded)?.suggestion?.suggestedMinutes
            val standardMinutes = block.plannedMinutes()
            _state.update { current ->
                current.copy(
                    nextTimeCard =
                        if (suggestedMinutes == null ||
                            suggestedMinutes == standardMinutes ||
                            block.id.value in current.dismissedNextTimeCardBlockIds
                        ) {
                            null
                        } else {
                            NextTimeCardUiState(
                                blockId = block.id.value,
                                title = block.title ?: "This session",
                                standardMinutes = standardMinutes,
                                suggestedMinutes = suggestedMinutes,
                                isSharedLock = block.type == BlockType.SharedLock,
                            )
                        },
                )
            }
        }
    }

    /** "Propose {suggested}m" / "Keep {standard}m" / "Skip" (§B 2.2) — all three retire the card
     * for this session (§B 2.3 "Suppression": "does not reappear until a subsequent session
     * outcome is logged"); Propose/Keep's actual composer deep-link is a navigation nudge the
     * caller supplies (same "LABEL, not an auto-proposal" shape as [recordReschedule] — this
     * ViewModel has no reach into `ui/together`'s composer). Takes the plain [blockId] rather than
     * a [TimeBlock] — [NextTimeCardUiState] already carries everything the caller needs to know
     * which card is being retired. */
    fun dismissNextTimeCard(blockId: String) {
        suggestionRequestId++
        _state.update {
            it.copy(
                dismissedNextTimeCardBlockIds = it.dismissedNextTimeCardBlockIds + blockId,
                nextTimeCard = it.nextTimeCard?.takeUnless { card -> card.blockId == blockId },
            )
        }
    }
}

private fun List<Task>.activeFocusOrder(): List<Task> =
    filter { it.status == TaskStatus.Todo || it.status == TaskStatus.InProgress }
        .sortedByDescending { it.priority.value }
        .take(MAX_FOCUS_TASKS)

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun defaultOutcomeOperationId(): String =
    kotlin.uuid.Uuid
        .random()
        .toString()
