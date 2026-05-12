package cy.txtracker.ui.settings.currencies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.ui.currency.AddCurrencyDialog
import cy.txtracker.ui.currency.TripCreationDialog
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrenciesScreen(
    onBack: () -> Unit,
    onTripHistory: (String) -> Unit,
    viewModel: CurrenciesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    // null = closed. "" sentinel = currency-picker step. Non-empty = trip-dialog step.
    var pendingTripCurrency by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Foreign currencies") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                Row(modifier = Modifier.padding(16.dp)) {
                    Button(onClick = { pendingTripCurrency = "" }) { Text("Start a trip") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { showAdd = true }) { Text("Add a currency") }
                }
            }
            items(state.rows, key = { it.currency.code }) { row ->
                ListItem(
                    headlineContent = {
                        Text("${row.currency.code} ${row.currency.displaySymbol}")
                    },
                    supportingContent = {
                        Text(
                            row.activeTrip?.let { trip ->
                                val end = trip.endAt?.toLocalDateTime(MalaysiaTimeZone)?.date?.toString()
                                    ?: "no end"
                                "Active trip until $end"
                            } ?: "No active trip",
                        )
                    },
                    modifier = Modifier.clickable { onTripHistory(row.currency.code) },
                )
            }
        }
    }

    if (showAdd) {
        AddCurrencyDialog(
            alreadyTracked = state.rows.map { it.currency.code }.toSet() + "MYR",
            onPick = { code ->
                showAdd = false
                viewModel.addCurrency(code)
                pendingTripCurrency = code  // immediately offer trip start
            },
            onDismiss = { showAdd = false },
        )
    }

    val pending = pendingTripCurrency
    if (pending != null) {
        if (pending.isBlank()) {
            // First step of proactive "Start a trip" — pick the currency.
            AddCurrencyDialog(
                alreadyTracked = emptySet(),
                onPick = { code -> pendingTripCurrency = code },
                onDismiss = { pendingTripCurrency = null },
            )
        } else {
            val now = Clock.System.now()
            TripCreationDialog(
                currency = pending,
                defaultStartAt = now,
                defaultEndAt = now.plus(14, DateTimeUnit.DAY, MalaysiaTimeZone),
                onConfirm = { startAt, endAt ->
                    pendingTripCurrency = null
                    viewModel.openTrip(pending, startAt, endAt)
                },
                onDismiss = { pendingTripCurrency = null },
            )
        }
    }
}
