package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import kotlinx.datetime.Instant
import org.junit.Test

class CIMBParserTest {

    private val parser = CIMBParser()
    private val now = Instant.parse("2026-05-09T12:30:00Z")

    @Test
    fun parses_real_sample_from_user() {
        val text = "CIMB:MYR25.00 was charged on your card num 1868 @GRAB RIDES-EC on 08/05. " +
            "Pls call the num at the back of your card for any queries."

        val result = parser.parseText(text, sourceApp = CIMBParser.CIMB_OCTO_PACKAGE, postedAt = now)!!

        assertThat(result.merchantRaw).isEqualTo("GRAB RIDES-EC")
        assertThat(result.amountMinor).isEqualTo(2500L)
        assertThat(result.currency).isEqualTo("MYR")
        assertThat(result.direction).isEqualTo(Direction.OUT)
    }

    @Test
    fun parses_when_trailing_sentence_is_short_or_missing() {
        // The "Pls call..." sentence is variable; the regex should consume anything past the date.
        val text = "CIMB:MYR530.00 was charged on your card num 1868 @CHONG TYRE AUTO SVC on 09/05."
        val r = parser.parseText(text, "cimb", now)!!
        assertThat(r.merchantRaw).isEqualTo("CHONG TYRE AUTO SVC")
        assertThat(r.amountMinor).isEqualTo(53000L)
    }

    @Test
    fun handles_thousands_separator() {
        val text = "CIMB:MYR1,234.56 was charged on your card num 1234 @BIG STORE on 01/01. ..."
        val r = parser.parseText(text, "cimb", now)!!
        assertThat(r.amountMinor).isEqualTo(123456L)
    }

    @Test
    fun handles_multi_word_merchants() {
        val text = "CIMB:MYR12.50 was charged on your card num 1234 @MR D.I.Y. (M) SDN BHD on 09/05."
        val r = parser.parseText(text, "cimb", now)!!
        assertThat(r.merchantRaw).isEqualTo("MR D.I.Y. (M) SDN BHD")
    }

    @Test
    fun returns_null_for_unrelated_cimb_notifications() {
        // Account-level CIMB messages that aren't card charges.
        assertThat(parser.parseText("CIMB:Your password was changed.", "cimb", now)).isNull()
        assertThat(parser.parseText("CIMB:OTP for online banking is 123456", "cimb", now)).isNull()
        // Refund / reversal — different verb.
        assertThat(
            parser.parseText("CIMB:MYR25.00 was refunded to your card num 1868 @GRAB RIDES-EC on 08/05.", "cimb", now),
        ).isNull()
    }

    @Test
    fun returns_null_for_non_cimb_text() {
        assertThat(parser.parseText("MAYBANK:MYR25.00 was charged on your card 1234 @ABC on 09/05.", "cimb", now)).isNull()
        assertThat(parser.parseText("Hello world", "cimb", now)).isNull()
    }
}
