package dev.elay.ui.together.proposal

import androidx.compose.runtime.compositionLocalOf
import dev.elay.domain.repository.ProposalRepository
import dev.elay.ui.together.proposal.fake.sharedFakeProposalRepository

/**
 * Real-repository injection seam for [dev.elay.ui.together.proposal.TogetherProposalViewModel] —
 * mirrors [dev.elay.ui.together.LocalPairRepository]'s role (house pattern, contract seat grants:
 * this lives in `ui/together/proposal/`, C3's grant, not `di/` — DI/user-scope wiring is the
 * lead's job once [ProposalRepository] joins `UserGraph`).
 *
 * Defaults to the shared fake so a bare `@Preview` or a screen-only test needs no wiring, exactly
 * like [dev.elay.ui.together.LocalPairRepository] and [dev.elay.di.LocalPlannerRepository].
 */
val LocalProposalRepository = compositionLocalOf<ProposalRepository> { sharedFakeProposalRepository }
