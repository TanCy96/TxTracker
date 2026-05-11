package cy.txtracker.ui.settings.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import cy.txtracker.export.YearMonth
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun CutoffPickerDialog(
    currentValue: YearMonth?,
    onDismiss: () -> Unit,
    onSave: (YearMonth?) -> Unit,
) {
    val today = remember {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    }
    var includeAll by remember { mutableStateOf(currentValue == null) }
    var year by remember { mutableStateOf(currentValue?.year ?: (today.year - 1)) }
    var month by remember { mutableStateOf(currentValue?.month ?: 1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transaction backup cutoff") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = includeAll, onClick = { includeAll = true })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = includeAll, onClick = { includeAll = true })
                    Text("All transactions", modifier = Modifier.padding(start = 8.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = !includeAll, onClick = { includeAll = false })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !includeAll, onClick = { includeAll = false })
                    Text("From ", modifier = Modifier.padding(start = 8.dp))
                    YearPicker(year = year, onChange = { year = it; includeAll = false })
                    Text("-", modifier = Modifier.padding(horizontal = 4.dp))
                    MonthPicker(month = month, onChange = { month = it; includeAll = false })
                }
                Text(
                    text = "Older transactions will be excluded from your cloud backup. " +
                        "They stay on this device, and a local snapshot is saved before any " +
                        "change in case you want to revert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(if (includeAll) null else YearMonth(year, month))
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun YearPicker(year: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val today = remember {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.year
    }
    val years = ((today - 10)..today).toList().reversed()
    OutlinedButton(onClick = { expanded = true }) {
        Text(year.toString())
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        years.forEach { y ->
            DropdownMenuItem(
                text = { Text(y.toString()) },
                onClick = { onChange(y); expanded = false },
            )
        }
    }
}

@Composable
private fun MonthPicker(month: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }) {
        Text("%02d".format(month))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        (1..12).forEach { m ->
            DropdownMenuItem(
                text = { Text("%02d".format(m)) },
                onClick = { onChange(m); expanded = false },
            )
        }
    }
}
