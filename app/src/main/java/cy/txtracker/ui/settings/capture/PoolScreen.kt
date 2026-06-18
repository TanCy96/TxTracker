package cy.txtracker.ui.settings.capture

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.CapturedNotification
import cy.txtracker.data.PoolFilter
import cy.txtracker.ui.format.formatDayHeader
import cy.txtracker.ui.format.formatMyr
import cy.txtracker.ui.format.formatTimeOfDay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoolScreen(
    packageName: String? = null,
    onBack: () -> Unit,
    viewModel: PoolViewModel = hiltViewModel(),
) {
    LaunchedEffect(packageName) { viewModel.setPackageName(packageName) }
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    var actionRow by remember { mutableStateOf<CapturedNotification?>(null) }
    var promoteRow by remember { mutableStateOf<CapturedNotification?>(null) }
    var rejectRow by remember { mutableStateOf<CapturedNotification?>(null) }
    var expandedIds by remember { mutableStateOf(emptySet<Long>()) }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::approveSelected, enabled = selectedIds.isNotEmpty()) {
                            Icon(Icons.Filled.Check, contentDescription = "Approve selected")
                        }
                        IconButton(onClick = viewModel::rejectSelected, enabled = selectedIds.isNotEmpty()) {
                            Icon(Icons.Filled.Delete, contentDescription = "Reject selected")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(if (packageName == null) "Notification pool" else "Pool: $packageName") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            PoolFilterRow(state.filter, viewModel::setFilter)
            HorizontalDivider()
            if (state.rows.isEmpty()) {
                Text(
                    text = "No captured notifications.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    state.rows.forEach { group ->
                        item(key = "header-${group.date}") {
                            Text(
                                text = formatDayHeader(group.date),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                        items(group.rows, key = { it.id }) { row ->
                            PoolRow(
                                row = row,
                                label = state.labelFor(row.packageName),
                                expanded = row.id in expandedIds,
                                onToggleExpanded = {
                                    expandedIds = if (row.id in expandedIds) {
                                        expandedIds - row.id
                                    } else {
                                        expandedIds + row.id
                                    }
                                },
                                selectionMode = selectionMode,
                                selected = row.id in selectedIds,
                                onClick = {
                                    if (selectionMode) viewModel.toggleSelect(row.id) else actionRow = row
                                },
                                onLongClick = { viewModel.enterSelection(row.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    actionRow?.let { row ->
        PoolActionSheet(
            row = row,
            label = state.labelFor(row.packageName),
            onPromote = {
                actionRow = null
                promoteRow = row
            },
            onNoise = {
                actionRow = null
                viewModel.markNoise(row.id)
            },
            onReject = {
                actionRow = null
                rejectRow = row
            },
            onDismiss = { actionRow = null },
        )
    }

    promoteRow?.let { row ->
        PromotePoolEntrySheet(
            row = row,
            categories = categories,
            onSave = { edit ->
                viewModel.promote(row.id, edit) { ok ->
                    if (ok) promoteRow = null
                }
            },
            onDismiss = { promoteRow = null },
        )
    }

    rejectRow?.let { row ->
        AlertDialog(
            onDismissRequest = { rejectRow = null },
            title = { Text("Reject package?") },
            text = { Text("Hide future pool entries from ${state.labelFor(row.packageName)} by default.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rejectPackage(row.packageName)
                    rejectRow = null
                }) { Text("Reject") }
            },
            dismissButton = {
                TextButton(onClick = { rejectRow = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PoolFilterRow(
    selected: PoolFilter,
    onSelect: (PoolFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PoolFilter.entries) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PoolRow(
    row: CapturedNotification,
    label: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        leadingContent = if (selectionMode) {
            { Checkbox(checked = selected, onCheckedChange = { onClick() }) }
        } else null,
        headlineContent = {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(label)
                Text(formatAmount(row.amountMinor, row.currency))
            }
        },
        supportingContent = {
            Column {
                Text(formatTimeOfDay(row.postedAt), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = row.rawText,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                row.rewrittenText?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Parsed as: $it",
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onToggleExpanded) {
                    Text(if (expanded) "Show less" else "View full text")
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                ListItemDefaults.containerColor
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PoolActionSheet(
    row: CapturedNotification,
    label: String,
    onPromote: () -> Unit,
    onNoise: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            ListItem(headlineContent = { Text("Promote to transaction") }, modifier = Modifier.clickable { onPromote() })
            ListItem(headlineContent = { Text("Mark as noise") }, modifier = Modifier.clickable { onNoise() })
            ListItem(headlineContent = { Text("Reject package") }, modifier = Modifier.clickable { onReject() })
        }
    }
}

private fun formatAmount(amountMinor: Long, currency: String): String =
    if (currency == "MYR") {
        formatMyr(amountMinor)
    } else {
        "$currency ${amountMinor / 100}.${(amountMinor % 100).toString().padStart(2, '0')}"
    }
