package cy.txtracker.ui.settings.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPriorityScreen(
    onBack: () -> Unit,
    viewModel: NotificationPriorityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var dialogOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification priority") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialogOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add package")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                Text(
                    text = "When two apps notify about the same payment, the app keeps the " +
                        "one from this list and drops the duplicate.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }

            item {
                SectionHeader("Built-in")
            }
            items(state.builtIn, key = { "builtin:" + it.packageName }) { row ->
                ListItem(
                    headlineContent = { Text(row.displayLabel) },
                    supportingContent = { Text(row.packageName, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { AssistChip(onClick = {}, label = { Text("Built-in") }, enabled = false) },
                )
                HorizontalDivider()
            }

            item {
                SectionHeader("Your additions")
            }
            if (state.userAdded.isEmpty()) {
                item {
                    Text(
                        "No packages added yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(state.userAdded, key = { "user:" + it.packageName }) { row ->
                    ListItem(
                        headlineContent = { Text(row.displayLabel) },
                        supportingContent = { Text(row.packageName, style = MaterialTheme.typography.bodySmall) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.remove(row.packageName) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }

        if (dialogOpen) {
            AddSourceDialog(
                candidates = state.candidates,
                onDismiss = { dialogOpen = false },
                onPick = { pkg ->
                    viewModel.add(pkg)
                    dialogOpen = false
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
