package dev.elay.ui.together

import androidx.compose.runtime.compositionLocalOf
import dev.elay.domain.repository.PairRepository
import dev.elay.ui.together.fake.sharedFakePairRepository

/**
 * Real-repository injection seam for [rememberTogetherViewModel] — mirrors
 * [dev.elay.di.LocalPlannerRepository]'s role for Today/Inbox/Plan (house pattern, brief §5).
 * Deliberately lives in `ui/together/`, not `di/` (contracts/stage1-pairing.md seat grants: C2's
 * grant is `ui/together/`, not `di/`; DI/user-scope [PairRepository] ownership is the lead's —
 * council/stage1-pairing-contract-sol.md §5). The lead is free to relocate this alongside
 * [dev.elay.di.LocalPlannerRepository] once [PairRepository] joins `UserGraph`.
 *
 * Defaults to the shared fake so a bare `@Preview` or a screen-only test needs no wiring, exactly
 * like [dev.elay.di.LocalPlannerRepository].
 */
val LocalPairRepository = compositionLocalOf<PairRepository> { sharedFakePairRepository }
