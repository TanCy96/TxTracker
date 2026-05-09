package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import kotlinx.datetime.Instant
import org.junit.Test

class GrabParserTest {

    private val parser = GrabParser()
    private val now = Instant.parse("2026-05-09T12:30:00Z")

    @Test
    fun parses_real_sample_from_user() {
        val text = "Your Mastercard 1868 has been charged RM 25.00 for booking A-9AK6JSBWXF8SAV"

        val result = parser.parseText(text, sourceApp = GrabParser.GRAB_PACKAGE, postedAt = now)!!

        assertThat(result.merchantRaw).isEqualTo("GRAB")
        assertThat(result.amountMinor).isEqualTo(2500L)
        assertThat(result.currency).isEqualTo("MYR")
        assertThat(result.direction).isEqualTo(Direction.OUT)
        assertThat(result.rawText).isEqualTo(text)
    }

    @Test
    fun handles_no_space_after_RM() {
        val r = parser.parseText(
            "Your Visa 9999 has been charged RM50.00 for booking B-XYZ", "grab", now,
        )!!
        assertThat(r.amountMinor).isEqualTo(5000L)
    }

    @Test
    fun handles_multi_word_card_names() {
        val r = parser.parseText(
            "Your Cash Back Mastercard 1234 has been charged RM 5.50 for booking C-ABC", "grab", now,
        )!!
        assertThat(r.amountMinor).isEqualTo(550L)
        assertThat(r.merchantRaw).isEqualTo("GRAB")
    }

    @Test
    fun handles_thousands_separator() {
        val r = parser.parseText(
            "Your Mastercard 1234 has been charged RM 1,234.56 for booking D-EEE", "grab", now,
        )!!
        assertThat(r.amountMinor).isEqualTo(123456L)
    }

    @Test
    fun returns_null_for_unrelated_text() {
        assertThat(parser.parseText("Your trip is on the way", "grab", now)).isNull()
        assertThat(parser.parseText("Promo: 50% off your next ride!", "grab", now)).isNull()
        assertThat(parser.parseText("", "grab", now)).isNull()
    }

    @Test
    fun returns_null_for_partial_matches() {
        // No "for booking" suffix → no match.
        assertThat(parser.parseText("Your Mastercard 1234 has been charged RM 25.00", "grab", now)).isNull()
        // Missing card number.
        assertThat(parser.parseText("Your Mastercard has been charged RM 25.00 for booking A-XX", "grab", now))
            .isNull()
    }
}
