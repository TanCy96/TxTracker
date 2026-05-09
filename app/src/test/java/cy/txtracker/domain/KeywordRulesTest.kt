package cy.txtracker.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeywordRulesTest {

    @Test fun mcdonalds_is_food() = assertThat(KeywordRules.match("MCDONALDS")).isEqualTo("Food")
    @Test fun starbucks_is_food() = assertThat(KeywordRules.match("STARBUCKS COFFEE")).isEqualTo("Food")
    @Test fun grabfood_is_food_not_transport() =
        assertThat(KeywordRules.match("GRABFOOD MALAYSIA")).isEqualTo("Food")
    @Test fun grab_alone_is_transport() =
        assertThat(KeywordRules.match("GRAB MALAYSIA")).isEqualTo("Transport")
    @Test fun grab_at_end_is_transport() =
        assertThat(KeywordRules.match("RIDE WITH GRAB")).isEqualTo("Transport")

    @Test fun tng_is_transport() = assertThat(KeywordRules.match("TNG RELOAD")).isEqualTo("Transport")
    @Test fun toll_is_transport() = assertThat(KeywordRules.match("PLUS TOLL HIGHWAY")).isEqualTo("Transport")

    @Test fun shell_is_fuel() = assertThat(KeywordRules.match("SHELL SECTION 17")).isEqualTo("Fuel")
    @Test fun petronas_is_fuel() = assertThat(KeywordRules.match("PETRONAS PETALING JAYA")).isEqualTo("Fuel")

    @Test fun parking_is_parking() =
        assertThat(KeywordRules.match("WILSON PARKING SUNWAY")).isEqualTo("Parking")
    @Test fun mbpj_is_parking() = assertThat(KeywordRules.match("MBPJ PARKING")).isEqualTo("Parking")

    @Test fun tesco_is_groceries() = assertThat(KeywordRules.match("TESCO EXTRA")).isEqualTo("Groceries")
    @Test fun speedmart_is_groceries() = assertThat(KeywordRules.match("99 SPEEDMART")).isEqualTo("Groceries")

    @Test fun uniqlo_is_apparel() = assertThat(KeywordRules.match("UNIQLO MID VALLEY")).isEqualTo("Apparel")

    @Test fun gsc_is_entertainment() = assertThat(KeywordRules.match("GSC PAVILION")).isEqualTo("Entertainment")
    @Test fun netflix_is_entertainment() = assertThat(KeywordRules.match("NETFLIX")).isEqualTo("Entertainment")

    @Test fun unifi_is_utilities() = assertThat(KeywordRules.match("UNIFI HOME")).isEqualTo("Utilities")
    @Test fun tnb_is_utilities() = assertThat(KeywordRules.match("TNB MALAYSIA")).isEqualTo("Utilities")

    @Test fun pharmacy_is_health() = assertThat(KeywordRules.match("WATSONS PHARMACY")).isEqualTo("Health")
    @Test fun klinik_is_health() = assertThat(KeywordRules.match("KLINIK ABC")).isEqualTo("Health")

    @Test fun unmatched_merchant_returns_null() =
        assertThat(KeywordRules.match("CHONG TYRE AUTO")).isNull()
    @Test fun empty_returns_null() = assertThat(KeywordRules.match("")).isNull()

    @Test
    fun internal_word_lookalikes_do_not_falsely_match() {
        // "GRAB" appears inside "GRABFOOD" but Food has higher precedence so still wins.
        // "TNG" without word boundaries shouldn't match merchants like "STING" or "RING".
        assertThat(KeywordRules.match("STING ENERGY")).isNull()
    }
}
