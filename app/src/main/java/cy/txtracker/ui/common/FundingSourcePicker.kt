package cy.txtracker.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind

/**
 * Returns the human-readable bucket label for a [FundingSourceKind].
 * Used in the picker section headers, the Settings funding-sources screen,
 * and the CSV export column — kept here so all surfaces share one definition.
 */
fun fundingBucketLabel(kind: FundingSourceKind): String = when (kind) {
    FundingSourceKind.CREDIT_CARD -> "Credit Card"
    FundingSourceKind.E_WALLET -> "E-Wallet"
    FundingSourceKind.DEBIT_BANK -> "Debit/Transfer"
    FundingSourceKind.CASH -> "Cash"
}

/** Canonical display order for funding-source kind sections. */
private val KIND_ORDER = listOf(
    FundingSourceKind.CREDIT_CARD,
    FundingSourceKind.E_WALLET,
    FundingSourceKind.DEBIT_BANK,
    FundingSourceKind.CASH,
)

/**
 * Modal bottom-sheet picker over a list of [FundingSource]s.
 *
 * Sources are grouped by [FundingSourceKind] in canonical order
 * (Credit Card → E-Wallet → Debit/Transfer → Cash) and filtered by a
 * search field so the user can quickly find the right account.
 *
 * A "None" entry at the top lets the user clear the selection.
 * The currently selected source shows a checkmark trailing icon.
 * Auto-named sources (where [FundingSource.isUserNamed] is false) are tagged
 * with a secondary "auto" label so the user can identify them.
 *
 * Caller drives show/hide state; this composable just renders when present.
 * [onPick] receives null when the user selects "None".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundingSourcePickerSheet(
    sources: List<FundingSource>,
    selected: FundingSource?,
    onDismiss: () -> Unit,
    onPick: (FundingSource?) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(sources, query) {
        if (query.isBlank()) sources
        else sources.filter { it.displayName.contains(query, ignoreCase = true) }
    }

    val grouped = remember(filtered) {
        KIND_ORDER
            .mapNotNull { kind ->
                val group = filtered.filter { it.kind == kind }
                if (group.isEmpty()) null else kind to group
            }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "Funding source",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            // "None" entry — always shown at the top regardless of search.
            item(key = "none") {
                ListItem(
                    headlineContent = { Text("None") },
                    trailingContent = if (selected == null) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                    } else null,
                    modifier = Modifier.clickable {
                        onPick(null)
                        onDismiss()
                    },
                )
                HorizontalDivider()
            }

            grouped.forEach { (kind, group) ->
                item(key = "header-${kind.name}") {
                    Text(
                        text = fundingBucketLabel(kind),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                items(group, key = { it.id }) { source ->
                    ListItem(
                        headlineContent = { Text(source.displayName) },
                        supportingContent = if (!source.isUserNamed) {
                            { Text("auto") }
                        } else null,
                        trailingContent = if (selected?.id == source.id) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else null,
                        modifier = Modifier.clickable {
                            onPick(source)
                            onDismiss()
                        },
                    )
                }
            }

            item(key = "bottom-spacer") {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
