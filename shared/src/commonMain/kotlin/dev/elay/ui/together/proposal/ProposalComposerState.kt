@file:Suppress("TooManyFunctions") // one small pure transition per composer action (§1) — house pattern, PlanViewModel

package dev.elay.ui.together.proposal

import dev.elay.domain.availability.Certainty
import dev.elay.domain.model.Candidate
import dev.elay.domain.model.CreateProposal
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.RespondProposal
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The Proposal Composer's pure state + transitions (council/stage2-timelock-gemini.md §1): 1-3
 * candidate rows (each its own date/start-time/duration, house "steppers" pattern from
 * [dev.elay.ui.plan.ScheduleSheet]/[dev.elay.ui.plan.PlanViewModel]'s add-block sheet, extended
 * with a per-candidate date since a proposal's candidates are rarely all on the sheet-opening day),
 * an optional title, and a response-deadline choice. No `@Composable` here — directly unit-testable
 * per the seat grant. All instant math goes through kotlinx-datetime; no fixed offsets (ADR-006).
 */

const val MIN_PROPOSAL_CANDIDATES = 1
const val MAX_PROPOSAL_CANDIDATES = 3
const val MIN_CANDIDATE_DURATION_MINUTES = 1
const val MAX_CANDIDATE_DURATION_MINUTES = 24 * 60
const val COMPOSER_TIME_STEP_MINUTES = 15
const val MAX_PROPOSAL_TITLE_LENGTH = 60

private val DEFAULT_START_TIME = LocalTime(hour = 10, minute = 0)
private const val DEFAULT_DURATION_MINUTES = 60
private val TWO_HOURS = 2.hours
private val TWELVE_HOURS = 12.hours
private val TWENTY_FOUR_HOURS = 24.hours

/** One composer candidate row (§1.2) — its own date, start time, and duration, all read/edited in
 * the composer's [ComposerUiState.viewerZone]; converted to an [Instant] only when validating or
 * building the outgoing command. */
data class ComposerCandidate(
    val date: LocalDate,
    val startTime: LocalTime,
    val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
)

/** §1.4's segmented deadline options. [Custom] is modeled for forward-compatibility with the
 * server's plain `Instant` deadline field but has no picker UI this round — see the composer
 * sheet's kdoc for why (no date/time-picker dependency exists in this codebase yet). */
enum class DeadlineOption {
    TwelveHoursBefore,
    TwentyFourHoursBefore,
    TwoHoursBefore,
    Custom,
}

/** The composer sheet's full state (§1). [counterProposalId]/[counterExpectedRevision] are
 * non-null when this composer answers an existing proposal as a counter (§2.1 "Suggest
 * different") rather than creating a fresh one. */
data class ComposerUiState(
    val viewerZone: TimeZone,
    val partnerZone: TimeZone,
    val partnerDisplayName: String,
    val title: String = "",
    val candidates: List<ComposerCandidate>,
    val deadlineOption: DeadlineOption = DeadlineOption.TwentyFourHoursBefore,
    val customDeadline: Instant? = null,
    val counterProposalId: ProposalId? = null,
    val counterExpectedRevision: Int? = null,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    /** Composer certainty (contracts/stage4-honest-availability.md; council/stage4-availability-gemini.md
     * §1.2 "Proposal Composer Candidate Pickers"): the viewer's OWN `rpc_self_conflict_hints`
     * certainty per candidate index, keyed by [Candidate.index]/`BusySnapshot.candidateIdx` — never
     * candidate list position alone (contract: "candidate_idx key lets callers re-associate
     * defensively"). Empty until [TogetherProposalViewModel.refreshComposerHints] resolves; a
     * missing key (rather than [Certainty.Unknown]) means "no label yet" — §B's "inform, never nag"
     * favors silence over a premature guess. */
    val selfHints: Map<Int, Certainty> = emptyMap(),
)

/** §1.2's slot-1 defaults: tomorrow, 10:00 AM, 60 minutes. */
fun defaultComposerCandidate(
    now: Instant,
    zone: TimeZone,
): ComposerCandidate {
    val tomorrow = now.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY)
    return ComposerCandidate(
        date = tomorrow,
        startTime = DEFAULT_START_TIME,
        durationMinutes = DEFAULT_DURATION_MINUTES,
    )
}

/** A fresh "Propose a time lock" composer (§1's entry point from Together) — the deadline option
 * already resolves per §1.4's calm default ([defaultDeadlineOption]), not just the data class's
 * static fallback, so a candidate close to `now` never starts out with an already-past deadline. */
fun newComposerState(
    now: Instant,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
    partnerDisplayName: String,
): ComposerUiState {
    val candidate = defaultComposerCandidate(now, viewerZone)
    val firstStart = candidate.date.atTime(candidate.startTime).toInstant(viewerZone)
    return ComposerUiState(
        viewerZone = viewerZone,
        partnerZone = partnerZone,
        partnerDisplayName = partnerDisplayName,
        candidates = listOf(candidate),
        deadlineOption = defaultDeadlineOption(now, firstStart),
    )
}

/** "Suggest different times" (§2.1/§2.3): prefilled from [existing]'s current revision, answering
 * it as a counter (contract §A.2's `expectedRevision` stale-guard). */
fun counterComposerState(
    existing: ProposalSummary,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
    partnerDisplayName: String,
): ComposerUiState {
    val currentRevision = existing.revisions.maxByOrNull { it.revisionNo }
    val candidates =
        currentRevision
            ?.candidates
            ?.sortedBy { it.index }
            ?.map { candidate -> candidate.toComposerCandidate(viewerZone) }
            .orEmpty()
    return ComposerUiState(
        viewerZone = viewerZone,
        partnerZone = partnerZone,
        partnerDisplayName = partnerDisplayName,
        title = existing.title,
        candidates = candidates.ifEmpty { listOf(defaultComposerCandidate(existing.updatedAt, viewerZone)) },
        counterProposalId = existing.id,
        counterExpectedRevision = existing.currentRevision,
    )
}

/** "Reschedule" (§2.4): a brand-new composer (the accepted negotiation is closed — there is no
 * RPC to amend it) prefilled with the winning candidate's own wall-clock time. */
fun rescheduleComposerState(
    existing: ProposalSummary,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
    partnerDisplayName: String,
    now: Instant,
): ComposerUiState {
    val winning =
        existing.revisions
            .firstOrNull { it.revisionNo == existing.acceptedRevision }
            ?.candidates
            ?.firstOrNull { it.index == existing.acceptedCandidateIdx }
    val candidate = winning?.toComposerCandidate(viewerZone) ?: defaultComposerCandidate(now, viewerZone)
    return ComposerUiState(
        viewerZone = viewerZone,
        partnerZone = partnerZone,
        partnerDisplayName = partnerDisplayName,
        title = existing.title,
        candidates = listOf(candidate),
    )
}

private fun Candidate.toComposerCandidate(zone: TimeZone): ComposerCandidate {
    val local = startsAt.toLocalDateTime(zone)
    return ComposerCandidate(date = local.date, startTime = local.time, durationMinutes = durationMinutes)
}

private fun ComposerCandidate.toInstantRange(zone: TimeZone): Pair<Instant, Instant> {
    val start = date.atTime(startTime).toInstant(zone)
    return start to start + durationMinutes.minutes
}

fun ComposerUiState.withTitle(text: String): ComposerUiState = copy(title = text.take(MAX_PROPOSAL_TITLE_LENGTH))

/** Steps candidate [index]'s start time by [deltaMinutes], rolling its date forward/back across
 * midnight rather than merely wrapping the time-of-day (unlike the fixed-day Plan add-block
 * sheet, a proposal candidate's own date can move). */
fun ComposerUiState.stepCandidateStart(
    index: Int,
    deltaMinutes: Int,
): ComposerUiState {
    val candidate = candidates.getOrNull(index) ?: return this
    val shifted =
        candidate.date
            .atTime(
                candidate.startTime,
            ).toInstant(viewerZone)
            .plus(deltaMinutes.minutes)
            .toLocalDateTime(viewerZone)
    return replaceCandidate(index, candidate.copy(date = shifted.date, startTime = shifted.time))
}

/** Steps candidate [index]'s own date by [deltaDays], leaving its time-of-day untouched. */
fun ComposerUiState.stepCandidateDay(
    index: Int,
    deltaDays: Int,
): ComposerUiState {
    val candidate = candidates.getOrNull(index) ?: return this
    return replaceCandidate(index, candidate.copy(date = candidate.date.plus(deltaDays, DateTimeUnit.DAY)))
}

fun ComposerUiState.stepCandidateDuration(
    index: Int,
    deltaMinutes: Int,
): ComposerUiState {
    val candidate = candidates.getOrNull(index) ?: return this
    val next =
        (candidate.durationMinutes + deltaMinutes).coerceIn(
            MIN_CANDIDATE_DURATION_MINUTES,
            MAX_CANDIDATE_DURATION_MINUTES,
        )
    return replaceCandidate(index, candidate.copy(durationMinutes = next))
}

/** Sets candidate [index]'s duration directly — the composer's `[30m] [45m] [60m] [90m]` presets
 * (§1.2), clamped the same as [stepCandidateDuration]. */
fun ComposerUiState.setCandidateDuration(
    index: Int,
    minutes: Int,
): ComposerUiState {
    val candidate = candidates.getOrNull(index) ?: return this
    val clamped = minutes.coerceIn(MIN_CANDIDATE_DURATION_MINUTES, MAX_CANDIDATE_DURATION_MINUTES)
    return replaceCandidate(index, candidate.copy(durationMinutes = clamped))
}

/** Sets the composer's per-candidate self-conflict certainty (this seat's grant, §1.2) — called by
 * [TogetherProposalViewModel.refreshComposerHints] once `rpc_self_conflict_hints` resolves. A fresh
 * candidate edit clears any stale label for that index rather than showing last known instead
 * of overwriting with new ones for other candidates that were unaffected — see
 * [TogetherProposalViewModel.refreshComposerHints]'s monotonic-request-id staleness guard, which
 * decides whether this is even called for a given response. */
fun ComposerUiState.withSelfHints(hints: Map<Int, Certainty>): ComposerUiState = copy(selfHints = hints)

private fun ComposerUiState.replaceCandidate(
    index: Int,
    candidate: ComposerCandidate,
): ComposerUiState = copy(candidates = candidates.toMutableList().apply { set(index, candidate) })

/** "+ Add alternative time (up to 3)" (§1.2) — a day later than the last slot, same time/duration
 * as a reasonable starting point; a no-op once [MAX_PROPOSAL_CANDIDATES] is reached. */
fun ComposerUiState.withAddedCandidate(): ComposerUiState {
    if (candidates.size >= MAX_PROPOSAL_CANDIDATES) return this
    val last = candidates.last()
    val next = last.copy(date = last.date.plus(1, DateTimeUnit.DAY))
    return copy(candidates = candidates + next)
}

/** "Remove option N" (§1.2) — slot 1 is required and never removable; a no-op below that floor. */
fun ComposerUiState.withRemovedCandidate(index: Int): ComposerUiState {
    if (index == 0 || candidates.size <= MIN_PROPOSAL_CANDIDATES) return this
    return copy(candidates = candidates.filterIndexed { i, _ -> i != index })
}

fun ComposerUiState.withDeadlineOption(option: DeadlineOption): ComposerUiState = copy(deadlineOption = option)

/** The deadline [ComposerUiState.deadlineOption] resolves to, relative to candidate 1's start —
 * always a function of the composer's own state, never `now` (contrast [defaultDeadlineOption],
 * which picks *which* option a fresh composer should start on based on `now`). */
fun resolveDeadline(state: ComposerUiState): Instant {
    val firstStart =
        state.candidates
            .first()
            .toInstantRange(state.viewerZone)
            .first
    return when (state.deadlineOption) {
        DeadlineOption.TwelveHoursBefore -> firstStart - TWELVE_HOURS
        DeadlineOption.TwentyFourHoursBefore -> firstStart - TWENTY_FOUR_HOURS
        DeadlineOption.TwoHoursBefore -> firstStart - TWO_HOURS
        DeadlineOption.Custom -> state.customDeadline ?: (firstStart - TWENTY_FOUR_HOURS)
    }
}

/**
 * §1.4's calm default: 24 hours before candidate 1, unless that would already be at/after now
 * (candidate 1 is today or very soon) — then the same-day-friendly "2 hours before" instead, so
 * the deadline is never pinned to a moment that's already passed.
 */
fun defaultDeadlineOption(
    now: Instant,
    firstCandidateStart: Instant,
): DeadlineOption =
    if (firstCandidateStart - TWENTY_FOUR_HOURS <=
        now
    ) {
        DeadlineOption.TwoHoursBefore
    } else {
        DeadlineOption.TwentyFourHoursBefore
    }

/** Composer validation outcomes (§1.6's exact calm copy matrix). */
sealed interface ComposerValidation {
    data object Valid : ComposerValidation

    data class Invalid(
        val message: String,
    ) : ComposerValidation
}

/**
 * Client-side mirror of §1.6's error copy — fails fast locally rather than round-tripping an
 * obviously invalid command to the RPC. Does not duplicate the server's own authoritative checks
 * (contract §A.1); this is calm, immediate feedback only.
 */
@Suppress("ReturnCount") // one early-exit per §1.6 error case — guard-clause style keeps each check a one-liner
fun validateComposer(
    state: ComposerUiState,
    now: Instant,
): ComposerValidation {
    val ranges = state.candidates.map { it.toInstantRange(state.viewerZone) to it.durationMinutes }
    ranges.forEachIndexed { index, (range, duration) ->
        if (range.first < now) return ComposerValidation.Invalid("This time has already passed")
        if (duration !in MIN_CANDIDATE_DURATION_MINUTES..MAX_CANDIDATE_DURATION_MINUTES) {
            return ComposerValidation.Invalid("Duration must be between 1 minute and 24 hours")
        }
        for (other in index + 1 until ranges.size) {
            if (range.first == ranges[other].first.first) {
                return ComposerValidation.Invalid("Options ${index + 1} and ${other + 1} are at the exact same time")
            }
        }
    }
    val deadline = resolveDeadline(state)
    if (deadline >= ranges.first().first.first) {
        return ComposerValidation.Invalid("Deadline must be before the first proposed time")
    }
    return ComposerValidation.Valid
}

/** Internal (not `private`) so [TogetherProposalViewModel.refreshComposerHints] can build the same
 * `rpc_self_conflict_hints` request candidates this composer would ultimately submit. */
internal fun ComposerUiState.toCandidates(): List<Candidate> =
    candidates.mapIndexed { index, candidate ->
        val (start, end) = candidate.toInstantRange(viewerZone)
        Candidate(index = index, startsAt = start, endsAt = end, durationMinutes = candidate.durationMinutes)
    }

/** Builds `rpc_create_proposal`'s command (contract §A.2) from a valid composer state — callers
 * should check [validateComposer] first. */
fun buildCreateProposal(
    state: ComposerUiState,
    operationId: String,
): CreateProposal =
    CreateProposal(
        operationId = operationId,
        title = state.title.trim(),
        originZoneId = state.viewerZone.id,
        responseDeadline = resolveDeadline(state),
        candidates = state.toCandidates(),
    )

/** Builds the counter shape of `rpc_respond_proposal` (contract §A.2) — requires
 * [ComposerUiState.counterProposalId]/[ComposerUiState.counterExpectedRevision] to be set (i.e.
 * this composer came from [counterComposerState]). */
fun buildCounterResponse(
    state: ComposerUiState,
    operationId: String,
): RespondProposal.Counter =
    RespondProposal.Counter(
        operationId = operationId,
        proposalId = requireNotNull(state.counterProposalId) { "buildCounterResponse requires a counter composer" },
        expectedRevision =
            requireNotNull(
                state.counterExpectedRevision,
            ) { "buildCounterResponse requires a counter composer" },
        originZoneId = state.viewerZone.id,
        responseDeadline = resolveDeadline(state),
        candidates = state.toCandidates(),
    )
