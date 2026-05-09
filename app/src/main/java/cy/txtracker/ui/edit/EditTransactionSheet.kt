package cy.txtracker.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.Category
import cy.txtracker.data.Transaction
import cy.txtracker.ui.format.formatDayHeader
import cy.txtracker.ui.format.formatMyr
import cy.txtracker.ui.format.formatTimeOfDay
import cy.txtracker.domain.MalaysiaTimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionSheet(
    transactionId: Long,
    onDismiss: () -> Unit,
    viewModel: EditTransactionViewModel = hiltViewModel(),
) {
    LaunchedEffect(transactionId) { viewModel.load(transactionId) }
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        when (val s = state) {
            EditUiState.Loading -> LoadingContent()
            EditUiState.Missing -> MissingContent(onClose = onDismiss)
            is EditUiState.Editing -> EditingContent(
                state = s,
                onCategoryChange = { categoryId ->
                    viewModel.setCategory(transactionId, categoryId, learn = true)
                },
                onDelete = {
                    viewModel.delete(transactionId, onDone = onDismiss)
                },
                onClose = onDismiss,
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MissingContent(onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Transaction not found.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onClose) { Text("Close") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditingContent(
    state: EditUiState.Editing,
    onCategoryChange: (Long?) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val tx = state.transaction
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // Header: merchant + amount.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tx.merchantRaw,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                val date = tx.occurredAt.toLocalDateTime(MalaysiaTimeZone).date
                Text(
                    text = "${formatDayHeader(date)} • ${formatTimeOfDay(tx.occurredAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatMyr(tx.amountMinor),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(text = "Category", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        CategoryPicker(
            categories = state.categories,
            selectedCategoryId = tx.categoryId,
            onCategoryChange = onCategoryChange,
        )

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onClose) { Text("Done") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    categories: List<Category>,
    selectedCategoryId: Long?,
    onCategoryChange: (Long?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "unverified") {
            FilterChip(
                selected = selectedCategoryId == null,
                onClick = { onCategoryChange(null) },
                label = { Text("Unverified") },
            )
        }
        items(categories, key = { it.id }) { c ->
            FilterChip(
                selected = selectedCategoryId == c.id,
                onClick = { onCategoryChange(c.id) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(Color(c.color), CircleShape))
                        Spacer(Modifier.size(6.dp))
                        Text(c.name)
                    }
                },
            )
        }
    }
}
