package cy.txtracker.ui.settings.descriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.MerchantDescriptionMapping
import cy.txtracker.domain.TimeBucket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescriptionMappingsScreen(
    onBack: () -> Unit,
    viewModel: DescriptionMappingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Learned descriptions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.merchantRows.isEmpty() && state.categoryRows.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.merchantRows.isNotEmpty()) {
                    item("merchant-header") { SectionHeader("By merchant + time of day") }
                    items(
                        items = state.merchantRows,
                        key = { it.merchantNormalized + it.timeBucket.name },
                    ) { mapping ->
                        MerchantDescriptionRow(
                            mapping = mapping,
                            onUnlink = { viewModel.unlinkMerchant(mapping.merchantNormalized, mapping.timeBucket) },
                        )
                        HorizontalDivider()
                    }
                }
                if (state.categoryRows.isNotEmpty()) {
                    item("category-header") { SectionHeader("By category + time of day") }
                    items(
                        items = state.categoryRows,
                        key = { "${it.mapping.categoryId}-${it.mapping.timeBucket.name}" },
                    ) { row ->
                        CategoryDescriptionRow(
                            row = row,
                            onUnlink = { viewModel.unlinkCategory(row.mapping.categoryId, row.mapping.timeBucket) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
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
private fun MerchantDescriptionRow(
    mapping: MerchantDescriptionMapping,
    onUnlink: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(mapping.description) },
        supportingContent = {
            Text("${mapping.merchantNormalized} • ${formatBucket(mapping.timeBucket)}")
        },
        trailingContent = {
            IconButton(onClick = onUnlink) {
                Icon(Icons.Filled.Close, contentDescription = "Unlink")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CategoryDescriptionRow(
    row: CategoryDescriptionRow,
    onUnlink: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.mapping.description) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val color = row.category?.color?.let(::Color) ?: MaterialTheme.colorScheme.outline
                Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
                Text("${row.category?.name ?: "(deleted)"} • ${formatBucket(row.mapping.timeBucket)}")
            }
        },
        trailingContent = {
            IconButton(onClick = onUnlink) {
                Icon(Icons.Filled.Close, contentDescription = "Unlink")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No learned descriptions yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "Type a description in the edit sheet to teach the app a pattern.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatBucket(bucket: TimeBucket): String = when (bucket) {
    TimeBucket.MORNING -> "Morning"
    TimeBucket.MIDDAY -> "Midday"
    TimeBucket.AFTERNOON -> "Afternoon"
    TimeBucket.EVENING -> "Evening"
    TimeBucket.LATE_NIGHT -> "Late night"
}
