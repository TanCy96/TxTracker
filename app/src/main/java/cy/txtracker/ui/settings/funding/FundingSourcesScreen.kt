package cy.txtracker.ui.settings.funding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.FundingSource
import cy.txtracker.ui.common.KIND_ORDER
import cy.txtracker.ui.common.fundingBucketLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundingSourcesScreen(
    onBack: () -> Unit,
    viewModel: FundingSourcesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedSource by remember { mutableStateOf<FundingSource?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Funding sources") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.sources.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            val grouped = remember(state.sources) {
                KIND_ORDER.mapNotNull { kind ->
                    val group = state.sources.filter { it.kind == kind }
                    if (group.isEmpty()) null else kind to group
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                grouped.forEach { (kind, sources) ->
                    item(key = "header-${kind.name}") {
                        SectionHeader(fundingBucketLabel(kind))
                    }
                    items(sources, key = { it.id }) { source ->
                        val txCount = state.txCounts[source.id] ?: 0
                        FundingSourceRow(
                            source = source,
                            txCount = txCount,
                            onClick = { selectedSource = source },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    selectedSource?.let { source ->
        FundingSourceEditSheet(
            source = source,
            state = state,
            onRename = { id, name -> viewModel.rename(id, name) },
            onSetKind = { id, kind -> viewModel.setKind(id, kind) },
            onMerge = { sourceId, targetId -> viewModel.merge(sourceId, targetId) },
            onDelete = { id -> viewModel.delete(id) },
            onDismiss = { selectedSource = null },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun FundingSourceRow(
    source: FundingSource,
    txCount: Int,
    onClick: () -> Unit,
) {
    val txLabel = if (txCount == 1) "1 transaction" else "$txCount transactions"
    val bucketLabel = fundingBucketLabel(source.kind)

    ListItem(
        headlineContent = { Text(source.displayName) },
        supportingContent = {
            val autoTag = if (!source.isUserNamed) " · auto" else ""
            Text("$bucketLabel · $txLabel$autoTag")
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No funding sources yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "Sources are created automatically when transactions are captured.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
