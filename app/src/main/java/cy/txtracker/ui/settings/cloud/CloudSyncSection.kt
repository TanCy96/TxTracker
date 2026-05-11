package cy.txtracker.ui.settings.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cy.txtracker.export.YearMonth
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Settings "Cloud sync" section. One row when signed out, six when signed in.
 * Stateful UI is owned by the caller (SettingsScreen → ViewModel); this composable
 * is purely presentation + click callbacks.
 */
@Composable
fun CloudSyncSection(
    enabled: Boolean,
    paused: Boolean,
    accountEmail: String?,
    lastSyncAt: Instant?,
    lastSyncError: String?,
    transactionCutoff: YearMonth?,
    onSignInClick: () -> Unit,
    onSignOutClick: (deleteCloudBackup: Boolean) -> Unit,
    onSyncNowClick: () -> Unit,
    onPausedChange: (Boolean) -> Unit,
    onCutoffClick: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    SectionHeader("Cloud sync")
    if (!enabled) {
        ListItem(
            headlineContent = { Text("Sync to Google Drive") },
            supportingContent = { Text("Off · Tap to sign in and back up your data") },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSignInClick),
        )
        return
    }

    var showSignOutDialog by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text("Sync to Google Drive") },
        supportingContent = {
            val statusText = buildStatusText(
                paused = paused,
                accountEmail = accountEmail,
                lastSyncAt = lastSyncAt,
                lastSyncError = lastSyncError,
            )
            Text(
                text = statusText,
                color = if (lastSyncError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.fillMaxWidth().clickable { showSignOutDialog = true },
    )
    HorizontalDivider()

    ListItem(
        headlineContent = { Text("Sync now") },
        supportingContent = { Text("Upload the current backup immediately") },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSyncNowClick),
    )
    HorizontalDivider()

    ListItem(
        headlineContent = { Text("Pause sync") },
        supportingContent = { Text("Halt uploads and downloads. Stays signed in.") },
        trailingContent = {
            Switch(checked = paused, onCheckedChange = onPausedChange)
        },
        modifier = Modifier.fillMaxWidth().clickable { onPausedChange(!paused) },
    )
    HorizontalDivider()

    ListItem(
        headlineContent = { Text("Transaction backup cutoff") },
        supportingContent = {
            Text(
                if (transactionCutoff == null) "Including all transactions"
                else "Including transactions from ${transactionCutoff.format()} onward",
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onCutoffClick),
    )
    HorizontalDivider()

    ListItem(
        headlineContent = { Text("Restore from cloud") },
        supportingContent = { Text("Replace local learning with the cloud backup") },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onRestoreClick),
    )
    HorizontalDivider()

    ListItem(
        headlineContent = { Text("Sign out") },
        supportingContent = { Text("Stop syncing. Your cloud backup remains on Drive.") },
        modifier = Modifier.fillMaxWidth().clickable { showSignOutDialog = true },
    )

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign out from cloud sync?") },
            text = { Text("Your local data stays intact.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    onSignOutClick(false)
                }) { Text("Sign out") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        showSignOutDialog = false
                        onSignOutClick(true)
                    }) { Text("Sign out and delete cloud backup") }
                    TextButton(onClick = { showSignOutDialog = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

private fun buildStatusText(
    paused: Boolean,
    accountEmail: String?,
    lastSyncAt: Instant?,
    lastSyncError: String?,
): String {
    val prefix = if (paused) "Paused" else "Signed in"
    val acct = accountEmail?.let { " as $it" }.orEmpty()
    val lastBit = when {
        lastSyncError != null -> " · Last sync failed: $lastSyncError"
        lastSyncAt != null -> " · Last synced ${relativeTime(lastSyncAt)}"
        else -> " · Not yet synced"
    }
    return "$prefix$acct$lastBit"
}

private fun relativeTime(instant: Instant): String {
    val now = Clock.System.now()
    val diffMs = now.toEpochMilliseconds() - instant.toEpochMilliseconds()
    val minutes = diffMs / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}
