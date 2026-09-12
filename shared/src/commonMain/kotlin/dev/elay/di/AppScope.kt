package dev.elay.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * One process-lifetime [CoroutineScope] for [AppGraph]'s background work (the replay-trigger
 * decorator's fire-and-forget calls, the initial replay on user-scope creation). A
 * [SupervisorJob] keeps one failed replay from cancelling the whole scope. Constructed once by
 * each platform entry point (`androidApp`'s `ElayApp`, iOS's `MainViewController`).
 */
fun createAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
