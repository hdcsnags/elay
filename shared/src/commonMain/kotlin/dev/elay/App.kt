package dev.elay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.elay.ui.InboxRoute
import dev.elay.ui.PlanRoute
import dev.elay.ui.TodayRoute
import dev.elay.ui.inbox.InboxScreen
import dev.elay.ui.plan.PlanScreen
import dev.elay.ui.theme.ElayTheme
import dev.elay.ui.today.TodayScreen
import dev.elay.ui.Surface as ElaySurface

/**
 * App shell: the six top-level surfaces from the spec's §2 core navigation
 * (route contract: contracts/phase0-foundation.md). Surface content is
 * Phase 1 work — these are deliberate placeholders.
 */
@Composable
fun App() {
    ElayTheme {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        Scaffold(
            bottomBar = {
                NavigationBar {
                    ElaySurface.entries.forEach { surface ->
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
                    TodayScreen(onOpenPlan = { navController.navigate(PlanRoute) })
                }
                composable(InboxRoute::class) {
                    InboxScreen()
                }
                composable(PlanRoute::class) {
                    PlanScreen()
                }
                ElaySurface.entries
                    .filter { it != ElaySurface.Today && it != ElaySurface.Inbox && it != ElaySurface.Plan }
                    .forEach { surface ->
                        composable(surface.route::class) { PlaceholderScreen(surface.label) }
                    }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label)
    }
}
