package dev.elay.ui.plan

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.minutes

/*
 * The manual "I'm busy then" sheet's own pure state (contracts/stage4-honest-availability.md;
 * council/stage4-availability-gemini.md §3.2 "The Lightest-Possible UI Surface"). No `@Composable`
 * here — same split as ScheduleSheetState/buildScheduledBlock (pure logic in this file, the
 * sheet composable lives in `PlanScreen.kt`).
 *
 * Honest-minimal scope (UNVERIFIED, flagged for the lead): `rpc_upsert_external_busy` and
 * `rpc_delete_external_busy` exist (this seat's grant), but there is no list RPC for `external_busy`
 * rows at all — the contract's own text calls this out ("there is no list RPC for busy rows"). This
 * sheet is therefore add-only with an optimistic local echo: ManualBusyEntry rows it shows are
 * only ever the ones this process has successfully upserted since launch, in
 * PlanUiState.manualBusyEntries — never a durable read-back of what the server actually holds. A
 * manual busy row added in a previous session, or on another device, is invisible here until a
 * list RPC exists. Delete is offered only for entries visible this way (their client-generated
 * ManualBusyEntry.busyId is already known locally), which the contract also sanctions
 * ("render the sheet as add/edit-last-created only").
 */

/** §3.2's own time-of-day step and duration presets — separate constants from
 * [SCHEDULE_TIME_STEP_MINUTES]/[MIN_BLOCK_DURATION_MINUTES]/[MAX_BLOCK_DURATION_MINUTES] would be
 * redundant; this sheet reuses the Plan add-block sheet's own bounds/step rather than inventing a
 * parallel set, since both build a `[start, start+duration)` instant window the same way. */
const val MANUAL_BUSY_TIME_STEP_MINUTES = SCHEDULE_TIME_STEP_MINUTES
val MANUAL_BUSY_DURATION_PRESETS = listOf(30, 60, 90)

private val DEFAULT_MANUAL_BUSY_START_TIME = LocalTime(hour = 14, minute = 0)
private const val DEFAULT_MANUAL_BUSY_DURATION_MINUTES = 60

/** One manual-busy sheet's own editable draft: a fixed date — always the Plan day the sheet was
 * opened from, never separately editable, mirroring [ScheduleSheetState.date] — a start time +
 * duration (stepper or the 30/60/90m presets, §3.2), and an optional label (placeholder only;
 * §3.2's default label is never silently substituted — a blank [label] upserts with no label at
 * all, matching [dev.elay.domain.availability.UpsertManualBusy] carrying no default-label field). */
data class ManualBusyDraftState(
    val date: LocalDate,
    val zone: TimeZone,
    val startTime: LocalTime = DEFAULT_MANUAL_BUSY_START_TIME,
    val durationMinutes: Int = DEFAULT_MANUAL_BUSY_DURATION_MINUTES,
    val label: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

/** One manual busy row this session has minted and heard `Applied` back for — see this file's
 * kdoc for why this is the sheet's only source of rows to display. [busyId] is the client-generated
 * id reused for [dev.elay.domain.availability.AvailabilityRepository.deleteManualBusy]. */
data class ManualBusyEntry(
    val busyId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val label: String?,
)

fun manualBusyDraftFor(
    date: LocalDate,
    zone: TimeZone,
): ManualBusyDraftState = ManualBusyDraftState(date = date, zone = zone)

fun ManualBusyDraftState.stepStartTime(deltaMinutes: Int): ManualBusyDraftState =
    copy(startTime = startTime.stepBy(deltaMinutes))

fun ManualBusyDraftState.withDuration(minutes: Int): ManualBusyDraftState =
    copy(durationMinutes = minutes.coerceIn(MIN_BLOCK_DURATION_MINUTES, MAX_BLOCK_DURATION_MINUTES))

fun ManualBusyDraftState.withLabel(text: String): ManualBusyDraftState = copy(label = text)

/** Builds the `[startsAt, endsAt)` instant range this draft would upsert, in
 * [ManualBusyDraftState.zone] — the wall-clock zone the sheet's steppers edit in, kept separate
 * from `originTz` (§3.2/deliverable 4: "origin_tz from `TimeZone.currentSystemDefault().id`"),
 * which the caller supplies independently when building the RPC command. */
fun ManualBusyDraftState.toInstantRange(): Pair<Instant, Instant> {
    val start = date.atTime(startTime).toInstant(zone)
    return start to start + durationMinutes.minutes
}
