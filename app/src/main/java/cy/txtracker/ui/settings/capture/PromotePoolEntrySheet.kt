package cy.txtracker.ui.settings.capture

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cy.txtracker.data.CapturedNotification
import cy.txtracker.data.Category
import cy.txtracker.data.PromoteEdit
import cy.txtracker.parsing.Currencies
import cy.txtracker.ui.format.formatAmount
import cy.txtracker.ui.format.formatTimeOfDay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromotePoolEntrySheet(
    row: CapturedNotification,
    categories: List<Category>,
    onSave: (PromoteEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    var merchant by remember(row.id) { mutableStateOf("") }
    var categoryId by remember(row.id) { mutableStateOf<Long?>(null) }
    var description by remember(row.id) { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Promote transaction", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            val symbol = Currencies.CODE_TO_DISPLAY_SYMBOL[row.currency] ?: row.currency
            Text("${formatAmount(row.amountMinor, symbol)} at ${formatTimeOfDay(row.postedAt)}")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("Merchant") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("Category", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = categoryId == null,
                        onClick = { categoryId = null },
                        label = { Text("Unverified") },
                    )
                }
                items(categories, key = { it.id }) { category ->
                    FilterChip(
                        selected = categoryId == category.id,
                        onClick = { categoryId = category.id },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(Color(category.color), CircleShape))
                                Spacer(Modifier.size(6.dp))
                                Text(category.name)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = merchant.trim().isNotEmpty(),
                    onClick = {
                        onSave(
                            PromoteEdit(
                                merchantRaw = merchant,
                                amountMinor = row.amountMinor,
                                currency = row.currency,
                                occurredAt = row.postedAt,
                                categoryId = categoryId,
                                description = description.takeIf { it.isNotBlank() },
                            ),
                        )
                    },
                ) { Text("Save") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
