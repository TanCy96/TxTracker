package cy.txtracker.ui.foreign

import cy.txtracker.data.Transaction

sealed interface ForeignUiState {
    data object Loading : ForeignUiState
    data class Loaded(
        /** Transactions grouped by currency code. Currencies with zero rows
         *  in the visible month are omitted. */
        val byCurrency: Map<String, CurrencyGroup>,
        /** Currency codes that have at least one trip active right now. Used
         *  by the empty-state copy to distinguish "no trip yet" from
         *  "trip running, waiting for captures". */
        val activeTripCurrencies: Set<String>,
    ) : ForeignUiState
}

data class CurrencyGroup(
    val code: String,
    val displaySymbol: String,
    val total: Long,                       // minor units
    val transactions: List<Transaction>,
    val categoryTotals: Map<Long?, Long>,  // categoryId -> minor units
)
