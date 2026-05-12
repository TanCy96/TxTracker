package cy.txtracker.ui.currency

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import cy.txtracker.domain.MalaysiaTimeZone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Prompts the user to start a trip for [currency] over an editable date range.
 *
 * Defaults to `[defaultStartAt, defaultEndAt]`. The "Open-ended" checkbox
 * clears endAt so the trip runs until the user manually ends it.
 *
 * Called both proactively (from Currencies settings) and reactively (from the
 * edit-sheet currency change for a parked currency).
 */
@Composable
fun TripCreationDialog(
    currency: String,
    defaultStartAt: Instant,
    defaultEndAt: Instant?,
    onConfirm: (Instant, Instant?) -> Unit,
    onDismiss: () -> Unit,
) {
    var startAt by remember { mutableStateOf(defaultStartAt) }
    var endAt by remember { mutableStateOf(defaultEndAt) }
    var openEnded by remember { mutableStateOf(defaultEndAt == null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(startAt, if (openEnded) null else endAt) }) {
                Text("Start trip")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Start a trip for $currency?") },
        text = {
            Column {
                Text(
                    "Captures in $currency between these dates will land in " +
                        "the Foreign tab. Earlier-captured rows in this currency " +
                        "within the range will be promoted retroactively.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                DateField(label = "Start", value = startAt) { startAt = it }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = openEnded, onCheckedChange = { openEnded = it })
                    Text("Open-ended (no end date)")
                }
                if (!openEnded) {
                    DateField(
                        label = "End",
                        value = endAt
                            ?: defaultStartAt.plus(14, DateTimeUnit.DAY, MalaysiaTimeZone),
                    ) { endAt = it }
                }
            }
        },
    )
}

/**
 * Lightweight YYYY-MM-DD text input. Suitable for v1; a full DatePicker
 * replacement can come later once the basic flow is validated on device.
 */
@Composable
private fun DateField(label: String, value: Instant, onChange: (Instant) -> Unit) {
    val dateString = value.toLocalDateTime(MalaysiaTimeZone).date.toString()
    var text by remember(dateString) { mutableStateOf(dateString) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            runCatching {
                val ld = LocalDate.parse(it)
                onChange(ld.atStartOfDayIn(MalaysiaTimeZone))
            }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
