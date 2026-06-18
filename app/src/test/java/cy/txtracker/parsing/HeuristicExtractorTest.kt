package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import cy.txtracker.data.UNDEFINED_MERCHANT
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
    fun strips_trailing_tap_cta_so_at_merchant_wins_over_to_cta() {
        // Wise pushes: "<amt> <CCY> spent at <MERCHANT>. Tap to see this transaction".
        // Without stripping the "Tap to …" suffix, the `to <X>` recipient pattern
        // hijacks the CTA tail ("see this transaction") and that ends up as the merchant.
        val text = "199 CNY spent at W Management. Tap to see this transaction"
        val r = extractor.extract(text, "com.wise.android", now)!!
        assertThat(r.merchantRaw).isEqualTo("W Management")
        assertThat(r.amountMinor).isEqualTo(19900L)
        assertThat(r.currency).isEqualTo("CNY")
    }

    @Test
    fun strips_trailing_tap_here_to_cta() {
        val text = "RM 12.00 paid to STARBUCKS. Tap here to view details."
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("STARBUCKS")
    }

    @Test
    fun skips_date_fragments_when_finding_amount() {
        // HSBC capture-all email body. The old regex matched "18MAY" inside "18MAY2026"
        // as amtB=18 / suffix=MAY because there was no word-boundary fence around amtB.
        // Now the regex requires non-alphanumeric on both sides of the digits+code pair,
        // so the date is skipped and "MYR 161.00" wins.
        val text = "We have debited your account due to DuitNow Transfer via Mobile Banking. " +
            "Transaction Date: 18MAY2026. Amount: MYR 161.00 to ACME SDN BHD"
        val r = extractor.extract(text, "com.hsbc.email", now)!!
        assertThat(r.amountMinor).isEqualTo(16_100L)
        assertThat(r.currency).isEqualTo("MYR")
    }

    @Test
    fun handles_hsbc_sms_debited_with_no_recipient_falls_back_to_undefined() {
        // HSBC SMS: "<date>: Debited your A/C ending <last4> with <AMT> for <DESC> via
        // <CHANNEL>". No `to`/`at`/`@` anchor at all — HSBC doesn't include the recipient
        // in this message format. We still capture the transaction (amount + out-verb are
        // unambiguous), but emit UNDEFINED_MERCHANT so downstream learning doesn't lock
        // arbitrary text in as a merchant name. Note: double space before the account
        // last4 is a real capture artifact and must not affect parsing.
        //
        // Also guards against the leading "26MAY2026" being mistaken for a 26 MAY amount:
        // the amount regex's `(?![A-Za-z0-9])` lookahead fails on the trailing "2026", so
        // the date is skipped and "MYR 13.00" wins.
        val text = "26MAY2026: Debited your A/C ending  0025 with MYR 13.00 " +
            "for DuitNow Transfer via Mobile Banking. Not you? Pls call us."
        val r = extractor.extract(text, "com.hsbc.hsbcclassic", now)!!
        assertThat(r.amountMinor).isEqualTo(1300L)
        assertThat(r.currency).isEqualTo("MYR")
        assertThat(r.merchantRaw).isEqualTo(UNDEFINED_MERCHANT)
        assertThat(r.direction).isEqualTo(Direction.OUT)
    }

    @Test
    fun ddmmmyyyy_date_prefix_does_not_match_as_amount() {
        // Defense-in-depth check for the HSBC date-prefix shape. Bare amtB+suffix scanning
        // could in principle interpret "26MAY" inside "26MAY2026" as amount=26 / code=MAY.
        // The trailing-fence lookahead `(?![A-Za-z0-9])` after the 3-letter code prevents
        // this — the next char "2" is alphanumeric, so the match is rejected. The amount
        // parser then continues and picks up "MYR 13.00" via the prefix form.
        val text = "26MAY2026: Debited your A/C ending 0025 with MYR 13.00 " +
            "for DuitNow Transfer via Mobile Banking."
        val r = extractor.extract(text, "com.hsbc.hsbcclassic", now)!!
        assertThat(r.amountMinor).isEqualTo(1300L)
        assertThat(r.currency).isEqualTo("MYR")
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
    fun captures_with_undefined_merchant_when_no_recipient_phrase() {
        // Amount + out-verb but no recipient anchor — HSBC-shaped notifications and similar
        // ambient bank messages. Previously rejected; now captured with UNDEFINED_MERCHANT
        // so the user can label manually without the parser inventing a merchant name.
        val r = extractor.extract("RM 5.00 was deducted.", "anything", now)!!
        assertThat(r.amountMinor).isEqualTo(500L)
        assertThat(r.merchantRaw).isEqualTo(UNDEFINED_MERCHANT)
        assertThat(r.direction).isEqualTo(Direction.OUT)
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

    @Test
    fun handles_4_digit_amount_without_thousands_separator() {
        // The previous AMOUNT regex required commas for 4+ digit amounts: it capped the
        // leading group at `\d{1,3}` and only allowed extra digits via `(?:,\d{3})*`.
        // Banks that omit the thousands separator (CIMB observed) were parsed wrong —
        // "1163.27" matched as "116" → RM 116.00.
        val text = "Paid RM 1163.27 to BIG STORE"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.amountMinor).isEqualTo(116327L)
        assertThat(r.merchantRaw).isEqualTo("BIG STORE")
    }

    @Test
    fun handles_4_digit_suffix_form_amount_without_thousands_separator() {
        val text = "transferred 1163.27 GBP to FRIEND"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.amountMinor).isEqualTo(116327L)
        assertThat(r.currency).isEqualTo("GBP")
    }

    @Test
    fun handles_tng_wallet_prefix_format_user_reported() {
        // TnG wallet pushes the merchant as the SUBJECT at the start of the body, followed
        // by a colon and then the amount: "MERCHANT: RM<amt> has been deducted ...". None of
        // the to/at/@/in patterns anchor onto this shape because the merchant isn't the
        // object of a preposition — it's the head. A dedicated wallet-prefix pattern is
        // required to avoid falling through to UNDEFINED_MERCHANT.
        val text = "Restoran Holiao Noodles Old Klang Road: RM45.60 has been deducted from " +
            "your TNG e-wallet. Merchant Reference No. XXXXXXXX"
        val r = extractor.extract(text, "my.com.tngdigital.ewallet", now)!!
        assertThat(r.amountMinor).isEqualTo(4560L)
        assertThat(r.currency).isEqualTo("MYR")
        assertThat(r.merchantRaw).isEqualTo("Restoran Holiao Noodles Old Klang Road")
        assertThat(r.direction).isEqualTo(Direction.OUT)
    }

    @Test
    fun wallet_prefix_pattern_does_not_shadow_to_recipient_when_both_present() {
        // Defensive: a text like "RECEIPT: paid RM 5 to JOHN" has both a head-colon and a
        // `to X` clause. The existing `to X` recipient pattern must win because it's the
        // more explicit recipient. The wallet pattern is placed LAST in RECIPIENT_PATTERNS
        // for exactly this reason.
        val text = "RECEIPT: paid RM 5.00 to JOHN"
        val r = extractor.extract(text, "anything", now)!!
        assertThat(r.merchantRaw).isEqualTo("JOHN")
    }

    @Test
    fun handles_gx_transfer_success_with_no_out_verb() {
        // GX Bank: "RM<amt> to <RECIPIENT> is successful" — has a recipient anchor but NO
        // out-verb, so the OUT_VERB gate used to reject it and the entry fell to the pool.
        val r = extractor.extract("RM4.00 to CHEE NYOK LAN is successful", "my.com.gxsbank", now)!!
        assertThat(r.amountMinor).isEqualTo(400L)
        assertThat(r.currency).isEqualTo("MYR")
        assertThat(r.merchantRaw).isEqualTo("CHEE NYOK LAN")
        assertThat(r.direction).isEqualTo(Direction.OUT)
    }

    @Test
    fun handles_gx_transfer_success_hyphenated_recipient() {
        val r = extractor.extract("RM14.50 to AA PHARMACY-SEA PAR is successful", "my.com.gxsbank", now)!!
        assertThat(r.amountMinor).isEqualTo(1450L)
        assertThat(r.merchantRaw).isEqualTo("AA PHARMACY-SEA PAR")
    }

    @Test
    fun transfer_success_shape_does_not_match_promo_without_recipient() {
        // Promo text that mentions an amount + "successful" but is not a "to <X>" transfer.
        assertThat(
            extractor.extract("RM5.00 cashback claim is successful", "anything", now),
        ).isNull()
    }

    @Test
    fun transfer_success_shape_does_not_hijack_successfully_transferred_phrasing() {
        // "...successfully transferred to X" uses an out-verb (transferred) and the word
        // "successfully" (not "is successful"), so it must be handled by the verb+recipient
        // path, NOT the transfer-success shape. The trailing \b in TRANSFER_SUCCESS_PATTERN
        // is what prevents this from matching there.
        val r = extractor.extract("RM 1.00 has been successfully transferred to LIM SHER LYNN.", "my.com.tngdigital.ewallet", now)!!
        assertThat(r.merchantRaw).isEqualTo("LIM SHER LYNN")
        assertThat(r.amountMinor).isEqualTo(100L)
        assertThat(r.direction).isEqualTo(Direction.OUT)
    }
}
