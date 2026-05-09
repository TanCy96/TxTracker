package cy.txtracker.ui.manual

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParseAmountMinorTest {

    @Test fun integer_ringgit() = assertThat(parseAmountMinor("12")).isEqualTo(1200L)
    @Test fun one_decimal_pads_to_two() = assertThat(parseAmountMinor("12.5")).isEqualTo(1250L)
    @Test fun two_decimals() = assertThat(parseAmountMinor("12.50")).isEqualTo(1250L)
    @Test fun under_one_ringgit() = assertThat(parseAmountMinor("0.50")).isEqualTo(50L)
    @Test fun zero() = assertThat(parseAmountMinor("0")).isEqualTo(0L)
    @Test fun trims_whitespace() = assertThat(parseAmountMinor("  12.50  ")).isEqualTo(1250L)

    @Test fun blank_returns_null() = assertThat(parseAmountMinor("")).isNull()
    @Test fun whitespace_returns_null() = assertThat(parseAmountMinor("   ")).isNull()
    @Test fun letters_return_null() = assertThat(parseAmountMinor("12abc")).isNull()
    @Test fun three_decimals_return_null() = assertThat(parseAmountMinor("12.345")).isNull()
    @Test fun multiple_dots_return_null() = assertThat(parseAmountMinor("12.34.56")).isNull()
    @Test fun negative_returns_null() = assertThat(parseAmountMinor("-12.50")).isNull()
    @Test fun thousands_separator_returns_null() {
        // Manual entry doesn't accept formatted numbers — we sanitize at the field level.
        assertThat(parseAmountMinor("1,234.56")).isNull()
    }
}
