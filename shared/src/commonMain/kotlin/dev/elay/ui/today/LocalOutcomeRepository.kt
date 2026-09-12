package dev.elay.ui.today

import androidx.compose.runtime.compositionLocalOf
import dev.elay.domain.repository.OutcomeRepository
import dev.elay.ui.today.fake.sharedFakeOutcomeRepository

/**
 * Real-repository injection seam for [TodayViewModel]'s Stage 5 plan-vs-actual surfaces
 * (contracts/stage5-retention-hardening.md, this seat's grant: "put yours in `ui/today/` with a
 * fake/ package") — mirrors [dev.elay.ui.together.proposal.LocalProposalRepository] and
 * [dev.elay.ui.together.proposal.LocalAvailabilityRepository]'s role exactly: the lead wires the
 * real [OutcomeRepository] (B8's `SupabaseOutcomeRepository`) into this Local at merge, once it
 * joins `UserGraph`.
 *
 * Defaults to the shared fake so a bare `@Preview`/screen-only test needs no wiring, exactly like
 * every other Local in the house.
 */
val LocalOutcomeRepository = compositionLocalOf<OutcomeRepository> { sharedFakeOutcomeRepository }
