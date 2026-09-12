package dev.elay.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Sign-in / create-account surface (brief §3): email + password, one submit action that reads
 * as "Sign in" or "Create account" depending on [SignInMode], a loading spinner in place of the
 * button label while a request is in flight. Calm copy throughout — even a failed attempt
 * renders as plain body text, never a red error banner (spec §2).
 */
@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    SignInContent(
        state = state,
        onEmailChanged = viewModel::onEmailChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onToggleMode = viewModel::toggleMode,
        onSubmit = viewModel::submit,
        modifier = modifier,
    )
}

@Composable
private fun SignInContent(
    state: SignInUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onToggleMode: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Welcome to Elay", style = MaterialTheme.typography.headlineSmall)
        Text(text = introCopy(state.mode), style = MaterialTheme.typography.bodyMedium)
        CredentialFields(state = state, onEmailChanged = onEmailChanged, onPasswordChanged = onPasswordChanged)
        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SubmitButton(state = state, onSubmit = onSubmit)
        TextButton(
            onClick = onToggleMode,
            modifier = Modifier.semantics { contentDescription = "Switch between sign in and create account" },
        ) {
            Text(toggleLabel(state.mode))
        }
    }
}

@Composable
private fun CredentialFields(
    state: SignInUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = state.email,
        onValueChange = onEmailChanged,
        label = { Text("Email") },
        singleLine = true,
        modifier = Modifier.semantics { contentDescription = "Email" },
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = onPasswordChanged,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.semantics { contentDescription = "Password" },
    )
}

@Composable
private fun SubmitButton(
    state: SignInUiState,
    onSubmit: () -> Unit,
) {
    Button(
        onClick = onSubmit,
        enabled = !state.isLoading,
        modifier = Modifier.semantics { contentDescription = submitLabel(state.mode) },
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            Text(submitLabel(state.mode))
        }
    }
}

private fun introCopy(mode: SignInMode): String =
    if (mode == SignInMode.SignIn) {
        "Sign in to pick up your plan where you left it."
    } else {
        "A minute to set up, then you're in."
    }

private fun submitLabel(mode: SignInMode): String = if (mode == SignInMode.SignIn) "Sign in" else "Create account"

private fun toggleLabel(mode: SignInMode): String =
    if (mode == SignInMode.SignIn) "New here? Create an account" else "Already have an account? Sign in"
