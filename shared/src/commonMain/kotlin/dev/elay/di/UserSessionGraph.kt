package dev.elay.di

import dev.elay.data.remote.SessionState
import dev.elay.domain.model.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** One signed-in user's built resources, plus how to release them when the session ends. */
class UserResources(
    val graph: UserGraph,
    private val onClose: () -> Unit,
) {
    fun close() = onClose()
}

/**
 * Drives [UserGraph]'s lifecycle off a session [Flow] (brief §1): the moment the session
 * becomes [SessionState.SignedIn] for a *new* user id, tears down whatever was built for the
 * previous one (if any) and lazily builds a fresh [UserResources] via [buildUserGraph]; anything
 * else (`SignedOut`, `Refreshing`) tears down and clears to `null`. Also fires the brief §2
 * "replay once on user-scope creation" call, fire-and-forget, right after a graph is built.
 *
 * Split out of [AppGraph] on purpose: this state machine is the part worth pinning down with a
 * precise test, and keeping it in its own class means a test can drive it with a fake session
 * [Flow] and a fake [buildUserGraph] — never a real `SupabaseClient` or Room database, so
 * nothing here can accidentally make a network call from `commonTest` (see the brief's own
 * "do NOT construct network calls in tests" trap).
 */
class UserSessionGraph(
    sessionFlow: Flow<SessionState>,
    appScope: CoroutineScope,
    private val buildUserGraph: (UserId) -> UserResources,
) {
    private var active: UserResources? = null

    private val _userScope = MutableStateFlow<UserGraph?>(null)
    val userScope: StateFlow<UserGraph?> = _userScope.asStateFlow()

    init {
        sessionFlow
            .map { session -> (session as? SessionState.SignedIn)?.userId }
            .distinctUntilChangedBy { it }
            .onEach { userId -> _userScope.value = transition(userId, appScope) }
            .launchIn(appScope)
    }

    private fun transition(
        userId: UserId?,
        appScope: CoroutineScope,
    ): UserGraph? {
        active?.close()
        active = null
        if (userId == null) return null
        val resources = buildUserGraph(userId)
        active = resources
        appScope.launch { resources.graph.syncCoordinator.replayOnce() }
        return resources.graph
    }
}
