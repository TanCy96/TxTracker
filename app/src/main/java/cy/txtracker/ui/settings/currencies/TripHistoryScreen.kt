package cy.txtracker.ui.settings.currencies

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.ui.currency.TripCreationDialog
import kotlinx.datetime.Clock
import kotlinx.datetime.toLocalDateTime

private enum class TripStatus(val label: String) {
    Upcoming("Upcoming"),
    Active("Active"),
    Ended("Ended"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistoryScreen(
    currency: String,
    onBack: () -> Unit,
    viewModel: TripHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val now = Clock.System.now()
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    var pendingEdit by remember { mutableStateOf<TripWithCount?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${state.currency} trips") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(state.trips, key = { it.trip.id }) { row ->
                val trip = row.trip
                val status = when {
                    trip.startAt > now -> TripStatus.Upcoming
                    trip.endAt != null && trip.endAt <= now -> TripStatus.Ended
                    else -> TripStatus.Active
                }
                ListItem(
                    headlineContent = {
                        val startStr = trip.startAt.toLocalDateTime(MalaysiaTimeZone).date.toString()
                        val endStr = trip.endAt
                            ?.toLocalDateTime(MalaysiaTimeZone)?.date?.toString() ?: "open"
                        Text("$startStr → $endStr")
                    },
                    supportingContent = {
                        Text("${status.label} • ${pluralizeTx(row.transactionCount)}")
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // "End now" only makes sense for currently-running
                            // trips — setting endAt on an Upcoming trip would
                            // produce endAt < startAt (a degenerate window).
                            if (status == TripStatus.Active) {
                                TextButton(onClick = { viewModel.endTrip(trip.id) }) {
                                    Text("End now")
                                }
                            }
                            IconButton(onClick = { pendingEdit = row }) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = "Edit trip dates",
                                )
                            }
                            IconButton(onClick = { pendingDelete = trip.id }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Delete trip",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    val tripIdToDelete = pendingDelete
    if (tripIdToDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTrip(tripIdToDelete)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
            title = { Text("Delete this trip?") },
            text = {
                Text(
                    "The trip record is removed. Transactions already " +
                        "promoted by this trip stay in the Foreign tab — " +
                        "deleting the trip is just an audit-trail cleanup.",
                )
            },
        )
    }

    val rowToEdit = pendingEdit
    if (rowToEdit != null) {
        TripCreationDialog(
            currency = state.currency,
            defaultStartAt = rowToEdit.trip.startAt,
            defaultEndAt = rowToEdit.trip.endAt,
            isEditing = true,
            onConfirm = { startAt, endAt ->
                viewModel.editTrip(rowToEdit.trip.id, startAt, endAt)
                pendingEdit = null
            },
            onDismiss = { pendingEdit = null },
        )
    }
}

private fun pluralizeTx(count: Int): String =
    if (count == 1) "1 transaction" else "$count transactions"
