package cy.txtracker.ui.foreign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ForeignRoute(viewModel: ForeignViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        ForeignUiState.Loading -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        is ForeignUiState.Loaded -> ForeignContent(state = s)
    }
}

@Composable
private fun ForeignContent(state: ForeignUiState.Loaded) {
    if (state.byCurrency.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No foreign transactions yet. Open Settings → Foreign currencies " +
                    "to plan a trip, or wait for a foreign notification to arrive.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        state.byCurrency.values.forEach { group ->
            CurrencySection(group)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CurrencySection(group: CurrencyGroup) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${group.code} ${group.displaySymbol}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${group.displaySymbol} ${formatMinor(group.total)}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        // For v1, render minimal transaction rows inline.
        // Re-using the Home screen's TransactionRow would be ideal but it is private
        // to the home package; minimal rendering is acceptable for now.
        group.transactions.forEach { tx ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tx.merchantRaw, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${group.displaySymbol} ${formatMinor(tx.amountMinor)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun formatMinor(minor: Long): String {
    val whole = minor / 100
    val cents = minor % 100
    return "%d.%02d".format(whole, cents)
}
