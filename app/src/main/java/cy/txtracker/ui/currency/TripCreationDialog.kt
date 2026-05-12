package cy.txtracker.ui.currency

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import kotlinx.datetime.TimeZone
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
 * Read-only date field that opens a Material 3 [DatePicker] dialog on tap.
 * Displays the date as `YYYY-MM-DD` in the Malaysia timezone. Storing the
 * picked value as midnight-MYT preserves the existing trip-window semantics
 * (start-of-day in local time).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: Instant, onChange: (Instant) -> Unit) {
    val dateString = value.toLocalDateTime(MalaysiaTimeZone).date.toString()
    var showPicker by remember { mutableStateOf(false) }

    // OutlinedTextField with readOnly = true doesn't propagate clicks to a
    // surrounding clickable modifier reliably across versions. Wrap in a Box
    // with a transparent matchParentSize overlay that catches the tap.
    Box {
        OutlinedTextField(
            value = dateString,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                Icon(
                    Icons.Outlined.CalendarToday,
                    contentDescription = "Pick date",
                )
            },
            // disabled colors are bland by default; pull the label/text colors
            // from the enabled scheme so the field still reads as interactive.
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable { showPicker = true },
        )
    }

    if (showPicker) {
        val initialMillis = value.toEpochMilliseconds()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        // DatePicker returns UTC midnight for the selected day.
                        // Map back to a local-midnight Instant in MalaysiaTimeZone
                        // so the trip-window range matches user intent regardless
                        // of timezone math elsewhere.
                        val utcDate = Instant.fromEpochMilliseconds(millis)
                            .toLocalDateTime(TimeZone.UTC).date
                        onChange(utcDate.atStartOfDayIn(MalaysiaTimeZone))
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
