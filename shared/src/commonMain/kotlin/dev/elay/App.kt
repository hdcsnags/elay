package dev.elay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.elay.data.remote.SessionState
import dev.elay.di.AppGraph
import dev.elay.di.LocalAppScope
import dev.elay.di.LocalCurrentUserId
import dev.elay.di.LocalPlannerRepository
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.TaskId
import dev.elay.ui.GoalDetailRoute
import dev.elay.ui.InboxRoute
import dev.elay.ui.PlanRoute
import dev.elay.ui.SettingsRoute
import dev.elay.ui.TaskDetailRoute
import dev.elay.ui.TodayRoute
import dev.elay.ui.TogetherRoute
import dev.elay.ui.auth.SignInScreen
import dev.elay.ui.auth.SignInViewModel
import dev.elay.ui.goal.GoalDetailScreen
import dev.elay.ui.inbox.InboxScreen
import dev.elay.ui.plan.PlanScreen
import dev.elay.ui.settings.SettingsScreen
import dev.elay.ui.settings.SettingsViewModel
import dev.elay.ui.task.TaskDetailScreen
import dev.elay.ui.theme.ElayTheme
import dev.elay.ui.today.TodayScreen
import dev.elay.ui.together.TogetherScreen
import dev.elay.ui.Surface as ElaySurface

/**
 * Auth-gated app root (brief §4): a centered spinner while the session restores
 * ([SessionState.Refreshing] — Gate 1 remainder: "session-restore loading state (sign-in flashes
 * before Today)"; [dev.elay.data.remote.impl.SupabaseAuthGateway] already maps the SDK's
 * `SessionStatus.Initializing` to this state rather than collapsing it into `SignedOut`, so no
 * new flow is needed here, just an explicit branch instead of folding it into the sign-in case),
 * [SignInScreen] once actually signed out, the four-tab shell (master plan v2's pivot —
 * Today · Plan · Together · Inbox) once [AppGraph.userScope] has finished building the signed-in
 * user's real repository. Goals/Review stay registered (contracts/phase0-foundation.md's route
 * contract) but out of the bar — the pivot cut them from the nav, not from the routes.
 *
 * [LocalAppScope] is provided here from [AppGraph.appScope] (brief §5: auth ops must survive a
 * screen swap mid-request — a `rememberCoroutineScope()` doesn't) so every descendant, sign-in
 * included, can launch a long-lived op instead of one tied to its own composition.
 */
@Composable
fun App(appGraph: AppGraph) {
    CompositionLocalProvider(LocalAppScope provides appGraph.appScope) {
        ElayTheme {
            val session by appGraph.sessionFlow.collectAsState(initial = SessionState.Refreshing)
            when (session) {
                is SessionState.SignedIn -> {
                    val userGraph by appGraph.userScope.collectAsState()
                    val graph = userGraph
                    if (graph == null) {
                        LoadingScreen()
                    } else {
                        CompositionLocalProvider(
                            LocalPlannerRepository provides graph.repository,
                            dev.elay.ui.together.LocalPairRepository provides graph.pairRepository,
                            dev.elay.ui.together.proposal.LocalProposalRepository provides graph.proposalRepository,
                            dev.elay.ui.together.proposal.LocalAvailabilityRepository provides
                                graph.availabilityRepository,
                            LocalCurrentUserId provides graph.userId,
                        ) {
                            AppShell(appGraph = appGraph)
                        }
                    }
                }
                SessionState.Refreshing -> LoadingScreen()
                SessionState.SignedOut -> {
                    val appScope = LocalAppScope.current
                    val signInViewModel =
                        remember { SignInViewModel(appGraph.authGateway, appGraph.accountCreator, appScope) }
                    SignInScreen(viewModel = signInViewModel)
                }
            }
        }
    }
}

/** The four top-level surfaces the pivot keeps in the bar, Today first (spec's start surface). */
private val BAR_SURFACES = listOf(ElaySurface.Today, ElaySurface.Plan, ElaySurface.Together, ElaySurface.Inbox)

@Composable
private fun AppShell(appGraph: AppGraph) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                BAR_SURFACES.forEach { surface ->
                    val selected = backStack?.destination?.hasRoute(surface.route::class) == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(surface.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(surface.icon, contentDescription = surface.label) },
                        label = { Text(surface.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ElaySurface.Today.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TodayRoute::class) {
                TodayScreen(
                    onOpenPlan = { navController.navigate(PlanRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable(InboxRoute::class) {
                InboxScreen()
            }
            composable(PlanRoute::class) {
                PlanScreen()
            }
            togetherAndDetailRoutes(navController)
            composable(SettingsRoute::class) {
                val appScope = LocalAppScope.current
                val settingsViewModel = remember { SettingsViewModel(appGraph.authGateway, appScope) }
                SettingsScreen(
                    viewModel = settingsViewModel,
                    email = appGraph.currentUserEmail(),
                    userId = LocalCurrentUserId.current.value,
                )
            }
            placeholderRoutes()
        }
    }
}

/** The still-registered-but-out-of-the-bar surfaces (Goals/Review — contracts/phase0-foundation.md's
 * route contract survives the pivot even though the nav bar doesn't render them). Split out of
 * [AppShell]'s `NavHost` body to keep that function's length under detekt's `LongMethod` threshold. */
private fun NavGraphBuilder.placeholderRoutes() {
    ElaySurface.entries
        .filter {
            it != ElaySurface.Today &&
                it != ElaySurface.Inbox &&
                it != ElaySurface.Plan &&
                it != ElaySurface.Together
        }.forEach { surface ->
            composable(surface.route::class) { PlaceholderScreen(surface.label) }
        }
}

/** Together (contracts/stage1-pairing.md) plus the Goal/Task detail routes it links to
 * (Gate-1 deferred item) — split out of [AppShell]'s `NavHost` body to keep that function's
 * length under detekt's `LongMethod` threshold. */
private fun NavGraphBuilder.togetherAndDetailRoutes(navController: NavHostController) {
    composable(TogetherRoute::class) {
        TogetherScreen()
    }
    composable(GoalDetailRoute::class) { backStackEntry ->
        val route = backStackEntry.toRoute<GoalDetailRoute>()
        GoalDetailScreen(
            goalId = GoalId(route.id),
            onBack = { navController.popBackStack() },
        )
    }
    composable(TaskDetailRoute::class) { backStackEntry ->
        val route = backStackEntry.toRoute<TaskDetailRoute>()
        TaskDetailScreen(
            taskId = TaskId(route.id),
            onBack = { navController.popBackStack() },
            onOpenGoal = { goalId -> navController.navigate(GoalDetailRoute(goalId.value)) },
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label)
    }
}
