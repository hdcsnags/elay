package dev.elay.ui.together.proposal

import androidx.compose.runtime.compositionLocalOf
import dev.elay.domain.availability.AvailabilityRepository
import dev.elay.ui.together.proposal.fake.sharedFakeAvailabilityRepository

/**
 * Real-repository injection seam for [dev.elay.domain.availability.AvailabilityRepository]'s three
 * consuming ViewModels — [TogetherProposalViewModel] (composer/responder certainty, this seat's
 * grant §2-§3) and [dev.elay.ui.plan.PlanViewModel] (manual busy sheet + capacity gauge, §4-§5) —
 * mirroring [LocalProposalRepository]'s role exactly (contract: "A `LocalAvailabilityRepository`
 * CompositionLocal … mirroring `LocalProposalRepository` exactly — the lead wires the real one in
 * DI at merge"). Lives in `ui/together/proposal/` rather than a new `ui/plan/` copy or a `di/`
 * file: `dev.elay.ui.plan.PlanScreen` already reaches into this package for
 * [buildDualTimeLines]/[dualTimeAccessibilityDescription] (the `SharedLockMarker` dual-time
 * rendering), so a single shared Local here — not a second one — is the house pattern for a
 * cross-surface Stage-4 dependency both grants need.
 *
 * Defaults to the shared fake so a bare `@Preview`/screen-only test needs no wiring, exactly like
 * [LocalProposalRepository] and [dev.elay.di.LocalPlannerRepository].
 */
val LocalAvailabilityRepository = compositionLocalOf<AvailabilityRepository> { sharedFakeAvailabilityRepository }
