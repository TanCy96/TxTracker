package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import kotlinx.datetime.Instant
import org.junit.Test

class GoogleWalletParserTest {

    private val parser = GoogleWalletParser()
    private val now = Instant.parse("2026-05-09T12:30:00Z")

    @Test
    fun parses_real_sample_from_user() {
        val text = "CHONG TYRE AUTO SVC RM530.00 with CIMB Cash Rebate Plat MasterCard **1868"

        val result = parser.parseText(text, sourceApp = GoogleWalletParser.GOOGLE_WALLET_PACKAGE, postedAt = now)!!

        assertThat(result.merchantRaw).isEqualTo("CHONG TYRE AUTO SVC")
        assertThat(result.amountMinor).isEqualTo(53000L)
        assertThat(result.currency).isEqualTo("MYR")
        assertThat(result.direction).isEqualTo(Direction.OUT)
        assertThat(result.occurredAt).isEqualTo(now)
        assertThat(result.sourceApp).isEqualTo(GoogleWalletParser.GOOGLE_WALLET_PACKAGE)
        assertThat(result.rawText).isEqualTo(text)
    }

    @Test
    fun handles_thousands_separator_in_amount() {
        val text = "BIG PURCHASE STORE RM1,234.56 with VISA Card **0000"
        val result = parser.parseText(text, "wallet", now)!!
        assertThat(result.amountMinor).isEqualTo(123456L)
        assertThat(result.merchantRaw).isEqualTo("BIG PURCHASE STORE")
    }

    @Test
    fun handles_small_amount_under_one_ringgit() {
        val text = "PARKING METER RM0.50 with Debit Card **9999"
        val result = parser.parseText(text, "wallet", now)!!
        assertThat(result.amountMinor).isEqualTo(50L)
    }

    @Test
    fun trims_surrounding_whitespace() {
        val text = "   CAFE X RM12.00 with MasterCard **1111   "
        val result = parser.parseText(text, "wallet", now)!!
        assertThat(result.merchantRaw).isEqualTo("CAFE X")
        assertThat(result.amountMinor).isEqualTo(1200L)
    }

    @Test
    fun returns_null_for_unrelated_text() {
        // Random GWallet promo / UI notification we should ignore.
        assertThat(parser.parseText("Your card is now active in Google Wallet", "wallet", now)).isNull()
        assertThat(parser.parseText("", "wallet", now)).isNull()
    }

    @Test
    fun returns_null_when_amount_format_does_not_match() {
        // Missing decimals — regex requires \d{2}.
        assertThat(parser.parseText("ABC RM5 with VISA **1234", "wallet", now)).isNull()
        // Wrong currency.
        assertThat(parser.parseText("ABC SGD5.00 with VISA **1234", "wallet", now)).isNull()
        // Missing card tail.
        assertThat(parser.parseText("ABC RM5.00 with VISA", "wallet", now)).isNull()
    }

    @Test
    fun keeps_raw_merchant_with_internal_punctuation() {
        // The parser doesn't normalize; it returns the merchant as-it-appears for the listener
        // to normalize separately. Internal punctuation is preserved.
        val text = "MR. D.I.Y. (M) SDN BHD RM18.90 with MasterCard **2222"
        val result = parser.parseText(text, "wallet", now)!!
        assertThat(result.merchantRaw).isEqualTo("MR. D.I.Y. (M) SDN BHD")
    }

    @Test
    fun package_set_contains_google_wallet() {
        assertThat(parser.packageNames).contains("com.google.android.apps.walletnfcrel")
    }
}
