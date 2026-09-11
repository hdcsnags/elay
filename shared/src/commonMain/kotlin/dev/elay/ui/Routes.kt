package dev.elay.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/** Typed route contracts — names fixed in contracts/phase0-foundation.md. */
@Serializable object TodayRoute
@Serializable object PlanRoute
@Serializable object GoalsRoute
@Serializable object InboxRoute
@Serializable object TogetherRoute
@Serializable object ReviewRoute

/** The six §2 top-level surfaces, in bar order. */
enum class Surface(
    val label: String,
    val route: Any,
    val icon: ImageVector,
) {
    Today("Today", TodayRoute, Icons.Filled.Home),
    Plan("Plan", PlanRoute, Icons.Filled.DateRange),
    Goals("Goals", GoalsRoute, Icons.Filled.Star),
    Inbox("Inbox", InboxRoute, Icons.Filled.Email),
    Together("Together", TogetherRoute, Icons.Filled.Person),
    Review("Review", ReviewRoute, Icons.Filled.CheckCircle),
}
