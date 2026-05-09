package cy.txtracker.ui.settings.merchants

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantMappingsScreen(
    onBack: () -> Unit,
    viewModel: MerchantMappingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Learned merchants") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.rows.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(state.rows, key = { it.mapping.merchantNormalized }) { row ->
                    MerchantRow(row = row, onUnlink = { viewModel.unlink(row.mapping.merchantNormalized) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MerchantRow(
    row: MerchantMappingRow,
    onUnlink: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.mapping.merchantNormalized) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val color = row.category?.color?.let(::Color) ?: MaterialTheme.colorScheme.outline
                Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
                Text(row.category?.name ?: "(category deleted)")
            }
        },
        trailingContent = {
            IconButton(onClick = onUnlink) {
                Icon(Icons.Filled.Close, contentDescription = "Unlink ${row.mapping.merchantNormalized}")
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
                text = "No learned merchants yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "Pick a category in the edit sheet to teach the app a merchant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
