package cy.txtracker.ui.settings.currencies

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.domain.MalaysiaTimeZone
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
            items(state.trips, key = { it.id }) { trip ->
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
                    supportingContent = { Text(status.label) },
                    trailingContent = {
                        // "End now" only makes sense for currently-running trips —
                        // setting endAt on an Upcoming trip would produce
                        // endAt < startAt (a degenerate window). Cancelling an
                        // Upcoming trip would need a separate delete path; not
                        // wired up yet.
                        if (status == TripStatus.Active) {
                            TextButton(onClick = { viewModel.endTrip(trip.id) }) {
                                Text("End now")
                            }
                        }
                    },
                )
            }
        }
    }
}
