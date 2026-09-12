package dev.elay.ui.settings

import dev.elay.data.remote.AuthGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings' one real behavior (brief §3): sign out. Launched on [scope] rather than a
 * screen-scoped `rememberCoroutineScope()` (brief §5, the same reasoning as sign-in/sign-up) —
 * [dev.elay.di.UserSessionGraph] reacts to the session flip and tears the whole user scope down,
 * which can unmount Settings before [AuthGateway.signOut] itself has finished; a scope that
 * outlives the screen (App's [dev.elay.di.LocalAppScope], backed by [dev.elay.di.AppGraph]'s
 * process-lifetime scope) means the call always completes.
 */
class SettingsViewModel(
    private val authGateway: AuthGateway,
    private val scope: CoroutineScope,
) {
    fun signOut() {
        scope.launch { authGateway.signOut() }
    }
}
