package cy.txtracker.ui.settings.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cy.txtracker.service.SummaryCadence

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOsState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Push notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            if (state.osNotificationsDisabled) {
                OsDisabledBanner(onOpen = { context.startActivity(viewModel.openSystemSettings()) })
                Spacer(Modifier.height(16.dp))
            }

            Text("Pending verification", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Get a notification when transactions sit unverified for more than a day.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.pendingEnabled,
                    onCheckedChange = viewModel::setPendingEnabled,
                )
            }

            Spacer(Modifier.height(24.dp))

            Text("Foreign currency", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Get a notification when foreign-currency activity is captured " +
                        "outside an active trip.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.foreignEnabled,
                    onCheckedChange = viewModel::setForeignEnabled,
                )
            }

            Spacer(Modifier.height(24.dp))

            Text("Spending summary", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            CadenceRow(
                cadence = state.summaryCadence,
                onChange = viewModel::setSummaryCadence,
            )
            Spacer(Modifier.height(12.dp))
            TimeRow(
                hour = state.summaryHour,
                enabled = state.summaryCadence != SummaryCadence.OFF,
                onChange = viewModel::setSummaryHour,
            )
        }
    }
}

@Composable
private fun OsDisabledBanner(onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "OS notifications are off for TxTracker.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text("Open settings") }
        }
    }
}

@Composable
private fun CadenceRow(cadence: SummaryCadence, onChange: (SummaryCadence) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Frequency", style = MaterialTheme.typography.bodyMedium)
        // Wrap the button + dropdown in a Box so the DropdownMenu anchors to
        // the button without taking a layout slot in the surrounding
        // SpaceBetween Row — otherwise the button visibly shifts toward the
        // centre while the menu is open and back to the end on dismiss.
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(cadenceLabel(cadence)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SummaryCadence.entries.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(cadenceLabel(c)) },
                        onClick = {
                            onChange(c)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeRow(hour: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable { showPicker = true } else it },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Time",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatHour(hour),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showPicker) {
        TimePickerDialog(
            initialHour = hour,
            onConfirm = { picked ->
                onChange(picked)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialHour: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = 0,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(pickerState.hour) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Summary time") },
        text = { TimePicker(state = pickerState) },
    )
}

private fun cadenceLabel(c: SummaryCadence): String = when (c) {
    SummaryCadence.OFF -> "Off"
    SummaryCadence.DAILY -> "Daily"
    SummaryCadence.WEEKLY -> "Weekly"
    SummaryCadence.MONTHLY -> "Monthly"
}

private fun formatHour(hour: Int): String {
    val h12 = ((hour % 12).takeIf { it != 0 } ?: 12)
    val suffix = if (hour < 12) "AM" else "PM"
    return "$h12:00 $suffix"
}
