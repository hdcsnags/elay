package dev.elay.di

import androidx.compose.runtime.compositionLocalOf
import dev.elay.domain.repository.PlannerRepository
import dev.elay.ui.fake.sharedFakePlannerRepository

/**
 * Real-repository injection seam for the `ui/today|inbox|plan` ViewModel factories (brief §5):
 * a `CompositionLocal` beats threading a [PlannerRepository] parameter through every `Screen`
 * composable's public signature, which the brief keeps frozen ("screen bodies stay" — only the
 * private `remember*ViewModel` factories change). Defaults to the shared fake so a screen
 * rendered with no [dev.elay.App]-level provider (a bare `@Preview`, or a test that mounts a
 * screen directly) still works without wiring — only the real `App(AppGraph)` entry point
 * overrides it once, with the signed-in user's real [PlannerRepository] from [AppGraph.userScope].
 */
val LocalPlannerRepository = compositionLocalOf<PlannerRepository> { sharedFakePlannerRepository }

/** The signed-in account (previews/tests fall back to the fake-data owner). */
val LocalCurrentUserId = compositionLocalOf { dev.elay.ui.util.LOCAL_OWNER_ID }
