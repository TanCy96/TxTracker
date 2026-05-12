package cy.txtracker.ui.currency

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cy.txtracker.data.TrackedCurrency
import cy.txtracker.parsing.Currencies

/**
 * Modal bottom-sheet picker over MYR + tracked currencies + "Add a currency…".
 * Caller drives the show/hide state; this composable just renders.
 *
 * onPick is called with the chosen 3-letter code. onAddNew is called when the
 * user taps "Add a currency…" — the caller is responsible for showing
 * [AddCurrencyDialog] next. onDismiss is called when the sheet is dismissed
 * by swipe / outside-tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyPickerSheet(
    tracked: List<TrackedCurrency>,
    onPick: (String) -> Unit,
    onAddNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Currency", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                item("MYR") {
                    ListItem(
                        headlineContent = { Text("MYR") },
                        supportingContent = { Text("RM — home currency") },
                        modifier = Modifier.clickable { onPick("MYR") },
                    )
                }
                items(tracked, key = { it.code }) { tc ->
                    ListItem(
                        headlineContent = { Text(tc.code) },
                        supportingContent = { Text(tc.displaySymbol) },
                        modifier = Modifier.clickable { onPick(tc.code) },
                    )
                }
                item("add-new") {
                    ListItem(
                        headlineContent = { Text("Add a currency…") },
                        modifier = Modifier.clickable { onAddNew() },
                    )
                }
            }
        }
    }
}

/**
 * Dialog showing the full [Currencies.KNOWN_CODES] list for picking a code
 * not yet tracked. Excludes anything already in [alreadyTracked]. onPick is
 * called with the chosen code; onDismiss closes without selection.
 */
@Composable
fun AddCurrencyDialog(
    alreadyTracked: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add a currency") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                val available = Currencies.KNOWN_CODES
                    .filter { it != "MYR" && it !in alreadyTracked }
                    .sorted()
                items(available) { code ->
                    ListItem(
                        headlineContent = { Text(code) },
                        supportingContent = {
                            Text(Currencies.CODE_TO_DISPLAY_SYMBOL[code] ?: code)
                        },
                        modifier = Modifier.clickable { onPick(code) },
                    )
                }
            }
        },
    )
}
