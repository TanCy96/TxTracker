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
    fun gwallet_card_spend_shape_extracts_merchant_from_head() {
        // No verb, but the "MERCHANT RM<amt> with <card> ••<last4>" structure is unambiguous —
        // it's a card spend. Pre-widening the heuristic rejected this; the strict parser was
        // supposed to catch it but its regex expected `**1868` and modern Google Wallet uses
        // `••1868` (U+2022 bullets).
        val gwallet = "HEXTAR LUCKIN (M) SB RM7.41 with CIMB Cash Rebate Plat MasterCard ••1868"
        val r = extractor.extract(gwallet, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("HEXTAR LUCKIN (M) SB")
        assertThat(r.amountMinor).isEqualTo(741L)
        assertThat(r.direction).isEqualTo(Direction.OUT)
    }

    @Test
    fun gwallet_card_spend_works_with_legacy_asterisk_suffix() {
        // Older Google Wallet pushes used **<last4> instead of ••<last4>. Both shapes
        // must work so nothing regresses if the user has older captured rows replayed.
        val text = "CHONG TYRE AUTO SVC RM530.00 with CIMB Cash Rebate Plat MasterCard **1868"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("CHONG TYRE AUTO SVC")
        assertThat(r.amountMinor).isEqualTo(53000L)
    }

    @Test
    fun card_spend_pattern_does_not_match_text_without_card_suffix() {
        // Promo / marketing text that mentions an amount and "with" but no card-bullets
        // suffix must NOT trigger the card-spend path. The OUT_VERB requirement protects
        // these.
        val text = "Pay RM 5.00 with your wallet to earn 5% cashback"
        // No verb? "Pay" is not in our OUT_VERB list; "earn" indicates incoming. No
        // recipient pattern fires either (no "to MERCHANT" before "for|on|..."). And the
        // card-spend pattern doesn't match because there's no <bullets>+<last4> trailer.
        assertThat(extractor.extract(text, "anything", now)).isNull()
    }

    @Test
    fun card_spend_pattern_handles_multibullet_card_suffix() {
        // Some captures observed had 4 bullets: ••••1868. The regex must accept any
        // run of bullets/asterisks before the last4.
        val text = "BOOK STORE RM12.00 with HSBC Platinum ••••5678"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("BOOK STORE")
        assertThat(r.amountMinor).isEqualTo(1200L)
    }

    @Test
    fun strips_trailing_punctuation_from_merchant() {
        val r = extractor.extract("transferred RM 5.00 to JOHN.", "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("JOHN")
    }

    @Test
    fun handles_cimb_fpx_payment_with_accepted_suffix() {
        // Real capture: CIMB Clicks FPX confirmation. Two parser gaps to close:
        //   1. "Payment" (noun) gates the OUT_VERB check — was not in the verb list.
        //   2. "to TAOBAO accepted on ..." — "accepted" must terminate the merchant
        //      match, otherwise the lookahead's `on <date>` slurps "accepted" into the
        //      merchant name as "TAOBAO accepted".
        val text = "CIMB: FPX Payment RM256.59 to TAOBAO accepted on 12-May-2026, 10:57:03. " +
            "Call the no at the back of your card for queries"
        val r = extractor.extract(text, "com.cimb.cimbclicks.my", now)!!
        assertThat(r.merchantRaw).isEqualTo("TAOBAO")
        assertThat(r.amountMinor).isEqualTo(25659L)
        assertThat(r.direction).isEqualTo(Direction.OUT)
    }

    @Test
    fun handles_to_merchant_successfully_suffix() {
        // Confirmation-style language some banks use. "successfully" should be a stop word
        // so it doesn't get pulled into the merchant.
        val text = "Paid RM 12.00 to MERCHANT successfully on 09/05"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("MERCHANT")
        assertThat(r.amountMinor).isEqualTo(1200L)
    }

    @Test
    fun handles_wise_p2p_myr_possessive_recipient() {
        // Real Wise sample: "Your transfer of 1 MYR is now in <Person>'s account"
        val text = "Your transfer of 1 MYR is now in TAN CHI YANG's account"
        val r = extractor.extract(text, "com.transferwise.android", now)!!
        assertThat(r.amountMinor).isEqualTo(100L)        // 1 MYR = 100 minor units
        assertThat(r.currency).isEqualTo("MYR")
        assertThat(r.merchantRaw).isEqualTo("TAN CHI YANG")
        assertThat(r.direction).isEqualTo(Direction.OUT)
    }

    @Test
    fun handles_suffix_code_form_gbp() {
        val text = "Your transfer of 100 GBP is now in JANE's account"
        val r = extractor.extract(text, "com.transferwise.android", now)!!
        assertThat(r.amountMinor).isEqualTo(10000L)
        assertThat(r.currency).isEqualTo("GBP")
        assertThat(r.merchantRaw).isEqualTo("JANE")
    }

    @Test
    fun handles_symbol_prefix_unambiguous_gbp() {
        // £ unambiguously maps to GBP without any user default.
        val text = "paid £20.00 to JOHN"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.amountMinor).isEqualTo(2000L)
        assertThat(r.currency).isEqualTo("GBP")
        assertThat(r.merchantRaw).isEqualTo("JOHN")
    }

    @Test
    fun handles_symbol_prefix_ambiguous_with_default() {
        // $ requires a user default. Test passes one in.
        val text = "paid $15.99 to COFFEE BEAN"
        val r = extractor.extract(
            text = text,
            sourceApp = "anything",
            postedAt = now,
            symbolDefaults = mapOf("$" to "USD"),
        )!!
        assertThat(r.currency).isEqualTo("USD")
        assertThat(r.amountMinor).isEqualTo(1599L)
    }

    @Test
    fun handles_symbol_prefix_ambiguous_without_default_falls_through_to_unknown() {
        val text = "paid $15.99 to MERCH"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.currency).isEqualTo("UNKNOWN")
        assertThat(r.amountMinor).isEqualTo(1599L)
    }

    @Test
    fun handles_integer_amount_no_decimals() {
        // "1 MYR" — no `.00`. Previously required two decimals; the widened regex
        // makes them optional.
        val text = "transferred 1 MYR to FRIEND"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.amountMinor).isEqualTo(100L)
        assertThat(r.currency).isEqualTo("MYR")
    }

    @Test
    fun thousands_separator_works_for_suffix_form() {
        val text = "transferred 1,234.56 GBP to FRIEND"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.amountMinor).isEqualTo(123456L)
        assertThat(r.currency).isEqualTo("GBP")
    }
}
