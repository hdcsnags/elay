package dev.elay.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.elay.domain.model.Capture
import dev.elay.ui.fake.sharedFakePlannerRepository

/**
 * Inbox surface (brief §3): quick-add capture, newest first, clarify-to-task
 * or dismiss. Calm empty state — "caught up" rather than a nagging zero.
 */
@Composable
fun InboxScreen(
    modifier: Modifier = Modifier,
    viewModel: InboxViewModel = rememberInboxViewModel(),
) {
    val state by viewModel.state.collectAsState()
    InboxContent(
        state = state,
        onQuickAddTextChanged = viewModel::onQuickAddTextChanged,
        onCaptureQuickAdd = viewModel::captureQuickAdd,
        onClarify = viewModel::clarifyToTask,
        onDismiss = viewModel::dismiss,
        modifier = modifier,
    )
}

@Composable
private fun InboxContent(
    state: InboxUiState,
    onQuickAddTextChanged: (String) -> Unit,
    onCaptureQuickAdd: () -> Unit,
    onClarify: (Capture) -> Unit,
    onDismiss: (Capture) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(text = "Inbox", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            QuickAddField(
                text = state.quickAddText,
                onTextChanged = onQuickAddTextChanged,
                onCapture = onCaptureQuickAdd,
            )
        }
        if (state.isEmpty) {
            item {
                Text(
                    text = "You're caught up. Anything on your mind? Drop it above.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(state.captures, key = { it.id.value }) { capture ->
                CaptureRow(
                    capture = capture,
                    onClarify = { onClarify(capture) },
                    onDismiss = { onDismiss(capture) },
                )
            }
        }
    }
}

@Composable
private fun QuickAddField(
    text: String,
    onTextChanged: (String) -> Unit,
    onCapture: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Quick capture text" },
            label = { Text("Dump a thought — sort it later") },
        )
        TextButton(
            onClick = onCapture,
            modifier = Modifier.semantics { contentDescription = "Save capture" },
        ) {
            Text("Capture")
        }
    }
}

@Composable
private fun CaptureRow(
    capture: Capture,
    onClarify: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = capture.body, style = MaterialTheme.typography.bodyLarge)
            Row(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = onClarify,
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Turn capture into a task: ${capture.body}"
                        },
                ) {
                    Text("Make it a task")
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.semantics { contentDescription = "Dismiss capture: ${capture.body}" },
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun rememberInboxViewModel(): InboxViewModel {
    val scope = rememberCoroutineScope()
    return remember { InboxViewModel(sharedFakePlannerRepository, scope) }
}
