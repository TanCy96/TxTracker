package cy.txtracker.ui.settings.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.PackageStatus
import cy.txtracker.data.TrackedPackageRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackedAppsScreen(
    onBack: () -> Unit,
    onPoolPackageClick: (String) -> Unit,
    viewModel: TrackedAppsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selected by remember { mutableStateOf<TrackedPackageRow?>(null) }
    var renameTarget by remember { mutableStateOf<TrackedPackageRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracked apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            packageSection("Tracked", state.tracked) { selected = it }
            packageSection("Rejected", state.rejected) { selected = it }
            packageSection("Untracked", state.untracked) { selected = it }
        }
    }

    selected?.let { row ->
        PackageActionSheet(
            row = row,
            onTrack = {
                viewModel.track(row.packageName)
                selected = null
            },
            onReject = {
                viewModel.reject(row.packageName)
                selected = null
            },
            onViewPool = {
                selected = null
                onPoolPackageClick(row.packageName)
            },
            onRename = { selected = null; renameTarget = row },
            onDismiss = { selected = null },
        )
    }

    renameTarget?.let { row ->
        RenameAppDialog(
            currentLabel = row.label,
            onConfirm = { newLabel ->
                viewModel.rename(row.packageName, newLabel)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.packageSection(
    title: String,
    rows: List<TrackedPackageRow>,
    onClick: (TrackedPackageRow) -> Unit,
) {
    item(key = "section-$title") {
        Text(
            text = "$title (${rows.size})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    if (rows.isEmpty()) {
        item(key = "empty-$title") {
            Text(
                text = "No packages.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    } else {
        items(rows.size, key = { rows[it].packageName }) { index ->
            val row = rows[index]
            PackageRow(row = row, onClick = { onClick(row) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun PackageRow(row: TrackedPackageRow, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(row.label) },
        supportingContent = { Text(row.packageName) },
        trailingContent = {
            Column {
                if (row.isBuiltIn) {
                    AssistChip(onClick = {}, label = { Text("built-in") })
                }
                Text("${row.poolEntryCountLast30Days} pool")
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackageActionSheet(
    row: TrackedPackageRow,
    onTrack: () -> Unit,
    onReject: () -> Unit,
    onViewPool: () -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                row.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            when (row.status) {
                PackageStatus.TRACKED -> {
                    ListItem(headlineContent = { Text("Move to Rejected") }, modifier = Modifier.clickable { onReject() })
                }
                PackageStatus.REJECTED -> {
                    ListItem(headlineContent = { Text("Move to Tracked") }, modifier = Modifier.clickable { onTrack() })
                }
                PackageStatus.UNTRACKED -> {
                    ListItem(headlineContent = { Text("Move to Tracked") }, modifier = Modifier.clickable { onTrack() })
                    ListItem(headlineContent = { Text("Move to Rejected") }, modifier = Modifier.clickable { onReject() })
                }
            }
            ListItem(headlineContent = { Text("Rename") }, modifier = Modifier.clickable { onRename() })
            ListItem(headlineContent = { Text("View entries in pool") }, modifier = Modifier.clickable { onViewPool() })
        }
    }
}

@Composable
private fun RenameAppDialog(
    currentLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(currentLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename app") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Display name") },
                supportingText = { Text("Leave blank to use the default name.") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
