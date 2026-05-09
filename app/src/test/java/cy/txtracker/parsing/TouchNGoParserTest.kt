package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import kotlinx.datetime.Instant
import org.junit.Test

class TouchNGoParserTest {

    private val parser = TouchNGoParser()
    private val now = Instant.parse("2026-05-09T12:30:00Z")

    @Test
    fun parses_real_sample_from_user() {
        val text = "You have paid RM16.00 to V.SHANTHI A/P AVELLAUTHAM"

        val result = parser.parseText(text, sourceApp = TouchNGoParser.TNG_PACKAGE, postedAt = now)!!

        assertThat(result.merchantRaw).isEqualTo("V.SHANTHI A/P AVELLAUTHAM")
        assertThat(result.amountMinor).isEqualTo(1600L)
        assertThat(result.currency).isEqualTo("MYR")
        assertThat(result.direction).isEqualTo(Direction.OUT)
        assertThat(result.sourceApp).isEqualTo(TouchNGoParser.TNG_PACKAGE)
    }

    @Test
    fun handles_thousands_separator() {
        val r = parser.parseText("You have paid RM1,234.56 to BIG SPENDER STORE", "tng", now)!!
        assertThat(r.amountMinor).isEqualTo(123456L)
        assertThat(r.merchantRaw).isEqualTo("BIG SPENDER STORE")
    }

    @Test
    fun handles_short_merchant_names() {
        val r = parser.parseText("You have paid RM5.50 to AB", "tng", now)!!
        assertThat(r.merchantRaw).isEqualTo("AB")
        assertThat(r.amountMinor).isEqualTo(550L)
    }

    @Test
    fun trims_surrounding_whitespace() {
        val r = parser.parseText("   You have paid RM10.00 to CAFE X   ", "tng", now)!!
        assertThat(r.merchantRaw).isEqualTo("CAFE X")
    }

    @Test
    fun returns_null_for_other_tng_notifications() {
        // Only "paid to" is supported — reload, received, promo notifications return null.
        assertThat(parser.parseText("You have reloaded RM50.00 to your wallet", "tng", now)).isNull()
        assertThat(parser.parseText("You have received RM20.00 from FRIEND", "tng", now)).isNull()
        assertThat(parser.parseText("Your wallet balance is now RM100.00", "tng", now)).isNull()
        assertThat(parser.parseText("Promo: 10% off all transactions", "tng", now)).isNull()
    }

    @Test
    fun returns_null_for_malformed_amounts() {
        assertThat(parser.parseText("You have paid RM5 to ABC", "tng", now)).isNull()  // missing decimals
        assertThat(parser.parseText("You have paid SGD5.00 to ABC", "tng", now)).isNull()  // wrong currency
    }
}
