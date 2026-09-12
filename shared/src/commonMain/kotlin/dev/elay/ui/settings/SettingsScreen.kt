package dev.elay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Minimal Settings surface (brief §3, previously an unregistered route): the signed-in
 * account's [email] when the provider has one, the raw [userId] otherwise, and a Sign out
 * button. Reached from a gear icon in Today's header row; [dev.elay.di.UserSessionGraph]
 * already tears the whole per-account graph down once [SettingsViewModel.signOut] flips the
 * session, so this screen owns no navigation of its own on sign-out — App reacts to the session
 * change and swaps back to sign-in.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    email: String?,
    userId: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = email ?: userId,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = "Signed in as ${email ?: userId}" },
        )
        Button(
            onClick = viewModel::signOut,
            modifier = Modifier.semantics { contentDescription = "Sign out" },
        ) {
            Text("Sign out")
        }
    }
}
