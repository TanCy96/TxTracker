package cy.txtracker.ui.settings.funding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.ui.common.FundingSourcePickerSheet
import cy.txtracker.ui.common.fundingBucketLabel

private val KIND_ORDER = listOf(
    FundingSourceKind.CREDIT_CARD,
    FundingSourceKind.E_WALLET,
    FundingSourceKind.DEBIT_BANK,
    FundingSourceKind.CASH,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundingSourceEditSheet(
    source: FundingSource,
    state: FundingSourcesUiState,
    onRename: (id: Long, name: String) -> Unit,
    onSetKind: (id: Long, kind: FundingSourceKind) -> Unit,
    onMerge: (sourceId: Long, targetId: Long) -> Unit,
    onDelete: (id: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(source.id) { mutableStateOf(source.displayName) }
    var showMergePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val isDefaultCash = source.id == state.defaultCashId
    val txCount = state.txCounts[source.id] ?: 0
    val canDelete = txCount == 0 && !isDefaultCash

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Edit funding source",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            onRename(source.id, trimmed)
                            onDismiss()
                        }
                    },
                    enabled = name.trim().isNotEmpty() && name.trim() != source.displayName,
                ) {
                    Text("Save name")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Bucket",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            KIND_ORDER.forEach { kind ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = source.kind == kind,
                            enabled = !isDefaultCash,
                            onClick = {
                                if (!isDefaultCash) {
                                    onSetKind(source.id, kind)
                                    onDismiss()
                                }
                            },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = source.kind == kind,
                        enabled = !isDefaultCash,
                        onClick = {
                            if (!isDefaultCash) {
                                onSetKind(source.id, kind)
                                onDismiss()
                            }
                        },
                    )
                    Text(
                        text = fundingBucketLabel(kind),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDefaultCash) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            if (isDefaultCash) {
                Text(
                    text = "The default Cash source kind is locked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showMergePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Merge into…")
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showDeleteConfirm = true },
                enabled = canDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete")
            }

            if (!canDelete) {
                Text(
                    text = when {
                        isDefaultCash -> "The default Cash source cannot be deleted."
                        txCount > 0 -> "Cannot delete: $txCount transaction(s) linked to this source."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showMergePicker) {
        val otherSources = state.sources.filter { it.id != source.id }
        FundingSourcePickerSheet(
            sources = otherSources,
            selected = null,
            onDismiss = { showMergePicker = false },
            onPick = { target ->
                if (target != null) {
                    onMerge(source.id, target.id)
                    showMergePicker = false
                    onDismiss()
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete \"${source.displayName}\"?") },
            text = {
                Text(
                    "This funding source will be permanently removed. " +
                        "Linked transactions will lose their funding source.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(source.id)
                        showDeleteConfirm = false
                        onDismiss()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
