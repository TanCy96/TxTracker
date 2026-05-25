package cy.txtracker.ui.settings.rewrites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewritesScreen(
    onBack: () -> Unit,
    viewModel: RewritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification rewrites") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.byPackage.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), Alignment.Center) {
                Text(
                    "No rewrites yet. Open any captured transaction's Edit sheet and " +
                        "tap \"Improve parsing for this app\" to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.byPackage.forEach { (pkg, rules) ->
                item(key = "header-$pkg") {
                    PackageHeader(pkg)
                }
                rules.forEachIndexed { idx, rule ->
                    item(key = "$pkg-${rule.pattern}") {
                        RewriteRow(
                            pattern = rule.pattern,
                            replacement = rule.replacement,
                            onDelete = { viewModel.delete(pkg, rule.pattern) },
                        )
                        if (idx < rules.lastIndex) HorizontalDivider()
                    }
                }
                item(key = "spacer-$pkg") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun PackageHeader(packageName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            packageName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RewriteRow(
    pattern: String,
    replacement: String,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(pattern, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = {
            val text = if (replacement.isEmpty()) "→ (stripped)" else "→ \"$replacement\""
            Text(text, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete rewrite")
            }
        },
    )
}
