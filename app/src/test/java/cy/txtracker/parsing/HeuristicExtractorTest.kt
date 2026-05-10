package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import kotlinx.datetime.Instant
import org.junit.Test

class HeuristicExtractorTest {

    private val extractor = HeuristicExtractor()
    private val now = Instant.parse("2026-05-09T12:30:00Z")

    @Test
    fun handles_tng_p2p_transferred_format_user_reported() {
        // The exact case that broke the strict TnG parser:
        val text = "RM 1.00 has been successfully transferred to LIM SHER LYNN."

        val r = extractor.extract(text, "my.com.tngdigital.ewallet", now)!!
        assertThat(r.amountMinor).isEqualTo(100L)
        assertThat(r.merchantRaw).isEqualTo("LIM SHER LYNN")
        assertThat(r.direction).isEqualTo(Direction.OUT)
    }

    @Test
    fun handles_paid_to_with_trailing_purpose_clause() {
        // "to ABC" followed by a "for" clause should stop at the boundary, not slurp the
        // suffix into the merchant.
        val text = "Paid RM 5.00 to ABC for movie tickets"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("ABC")
        assertThat(r.amountMinor).isEqualTo(500L)
    }

    @Test
    fun handles_charged_at_merchant_format() {
        val text = "MYR12.50 was charged at COFFEE BEAN on 09/05."
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("COFFEE BEAN")
        assertThat(r.amountMinor).isEqualTo(1250L)
    }

    @Test
    fun handles_at_merchant_bank_format() {
        val text = "RM530.00 charged on card 1234 @CHONG TYRE AUTO on 09/05."
        val r = extractor.extract(text, "bank", now)!!
        assertThat(r.merchantRaw).isEqualTo("CHONG TYRE AUTO")
        assertThat(r.amountMinor).isEqualTo(53000L)
    }

    @Test
    fun thousands_separator_works() {
        val r = extractor.extract("Paid RM 1,234.56 to BIG STORE", "anything", now)!!
        assertThat(r.amountMinor).isEqualTo(123456L)
    }

    @Test
    fun handles_purchased_verb() {
        // Common bank push form for card swipes.
        val text = "Purchased RM 5.00 at COFFEE BEAN"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("COFFEE BEAN")
        assertThat(r.amountMinor).isEqualTo(500L)
    }

    @Test
    fun handles_bare_transfer_verb() {
        // Wise / digital-bank notifications often use "transfer" rather than "transferred".
        val text = "RM 50.00 transfer to JANE"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("JANE")
        assertThat(r.amountMinor).isEqualTo(5000L)
    }

    @Test
    fun handles_withdrew_verb() {
        val text = "Withdrew RM 100.00 at ATM SS15"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("ATM SS15")
        assertThat(r.amountMinor).isEqualTo(10000L)
    }

    @Test
    fun handles_billed_verb() {
        val text = "Your card was billed RM 30.00 at MERCHANT"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("MERCHANT")
        assertThat(r.amountMinor).isEqualTo(3000L)
    }

    @Test
    fun rejects_text_without_outgoing_verb() {
        // Has amount + "to" but no out-verb. Could be a balance update or info text.
        assertThat(
            extractor.extract("Your wallet balance is RM 100.00", "anything", now),
        ).isNull()
        // Cashback / earned messages — explicitly NOT outgoing.
        assertThat(
            extractor.extract("You earned RM 5.00 cashback to your wallet", "anything", now),
        ).isNull()
    }

    @Test
    fun rejects_text_without_amount() {
        assertThat(extractor.extract("Payment was processed.", "anything", now)).isNull()
    }

    @Test
    fun rejects_text_without_recipient_phrase() {
        // Has amount + verb but no "to/at/@" merchant marker.
        assertThat(extractor.extract("RM 5.00 was deducted.", "anything", now)).isNull()
    }

    @Test
    fun rejects_blank_text() {
        assertThat(extractor.extract("", "anything", now)).isNull()
        assertThat(extractor.extract("   ", "anything", now)).isNull()
    }

    @Test
    fun rejects_gwallet_text_that_lacks_a_verb() {
        // GWallet's notification has no out-verb in it. The strict parser handles this format;
        // the heuristic deliberately requires a verb so we don't double-extract.
        val gwallet = "CHONG TYRE AUTO SVC RM530.00 with CIMB Cash Rebate Plat MasterCard **1868"
        assertThat(extractor.extract(gwallet, "anything", now)).isNull()
    }

    @Test
    fun strips_trailing_punctuation_from_merchant() {
        val r = extractor.extract("transferred RM 5.00 to JOHN.", "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("JOHN")
    }
}
