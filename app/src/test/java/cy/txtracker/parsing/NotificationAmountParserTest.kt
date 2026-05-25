package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationAmountParserTest {

    @Test
    fun finds_prefix_amount() {
        val match = NotificationAmountParser.findFirst("Paid RM 1,163.27 today")

        assertThat(match?.amountMinor).isEqualTo(116327L)
        assertThat(match?.currency).isEqualTo("MYR")
    }

    @Test
    fun finds_suffix_amount() {
        val match = NotificationAmountParser.findFirst("Spent 25.50 USD")

        assertThat(match?.amountMinor).isEqualTo(2550L)
        assertThat(match?.currency).isEqualTo("USD")
    }

    @Test
    fun rejects_date_like_text() {
        assertThat(NotificationAmountParser.findFirst("Transaction Date: 18MAY2026")).isNull()
    }

    @Test
    fun resolves_ambiguous_symbol_from_defaults() {
        val match = NotificationAmountParser.findFirst("Paid $12.00", mapOf("$" to "SGD"))

        assertThat(match?.currency).isEqualTo("SGD")
    }
}
