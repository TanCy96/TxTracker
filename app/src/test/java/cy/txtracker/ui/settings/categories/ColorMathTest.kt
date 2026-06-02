package cy.txtracker.ui.settings.categories

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColorMathTest {

    // --- parseHexColor ------------------------------------------------------

    @Test
    fun parseHex_six_digits_with_hash() {
        assertThat(parseHexColor("#3A7BD5")).isEqualTo(0xFF3A7BD5.toInt())
    }

    @Test
    fun parseHex_six_digits_without_hash() {
        assertThat(parseHexColor("3A7BD5")).isEqualTo(0xFF3A7BD5.toInt())
    }

    @Test
    fun parseHex_is_case_insensitive() {
        assertThat(parseHexColor("#3a7bd5")).isEqualTo(0xFF3A7BD5.toInt())
    }

    @Test
    fun parseHex_three_digit_shorthand_expands() {
        // #3AD -> #33AADD
        assertThat(parseHexColor("#3AD")).isEqualTo(0xFF33AADD.toInt())
    }

    @Test
    fun parseHex_tolerates_surrounding_whitespace() {
        assertThat(parseHexColor("  #3A7BD5  ")).isEqualTo(0xFF3A7BD5.toInt())
    }

    @Test
    fun parseHex_always_opaque() {
        val color = parseHexColor("#000000")!!
        assertThat(color ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun parseHex_rejects_invalid_input() {
        assertThat(parseHexColor("")).isNull()
        assertThat(parseHexColor("#")).isNull()
        assertThat(parseHexColor("xyz")).isNull()
        assertThat(parseHexColor("#12345")).isNull()    // 5 digits
        assertThat(parseHexColor("#GGGGGG")).isNull()    // non-hex
        assertThat(parseHexColor("#FF3A7BD5")).isNull()  // 8 digits (alpha) not accepted
    }

    // --- formatHexColor -----------------------------------------------------

    @Test
    fun formatHex_uppercases_and_drops_alpha() {
        assertThat(formatHexColor(0xFF3A7BD5.toInt())).isEqualTo("#3A7BD5")
        assertThat(formatHexColor(0x803A7BD5.toInt())).isEqualTo("#3A7BD5")
    }

    @Test
    fun formatHex_pads_leading_zeros() {
        assertThat(formatHexColor(0xFF000000.toInt())).isEqualTo("#000000")
        assertThat(formatHexColor(0xFF0A0B0C.toInt())).isEqualTo("#0A0B0C")
    }

    @Test
    fun parseHex_formatHex_round_trip() {
        val hex = "#1DA1F2"
        assertThat(formatHexColor(parseHexColor(hex)!!)).isEqualTo(hex)
    }

    // --- hsvToColorInt ------------------------------------------------------

    @Test
    fun hsv_primaries_map_to_expected_rgb() {
        assertThat(hsvToColorInt(0f, 1f, 1f)).isEqualTo(0xFFFF0000.toInt())    // red
        assertThat(hsvToColorInt(120f, 1f, 1f)).isEqualTo(0xFF00FF00.toInt())  // green
        assertThat(hsvToColorInt(240f, 1f, 1f)).isEqualTo(0xFF0000FF.toInt())  // blue
    }

    @Test
    fun hsv_zero_saturation_is_greyscale() {
        assertThat(hsvToColorInt(0f, 0f, 1f)).isEqualTo(0xFFFFFFFF.toInt())  // white
        assertThat(hsvToColorInt(0f, 0f, 0f)).isEqualTo(0xFF000000.toInt())  // black
    }

    @Test
    fun hsv_result_is_always_opaque() {
        assertThat(hsvToColorInt(200f, 0.5f, 0.5f) ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun hsv_hue_360_equals_hue_0() {
        assertThat(hsvToColorInt(360f, 1f, 1f)).isEqualTo(hsvToColorInt(0f, 1f, 1f))
    }

    // --- colorIntToHsv ------------------------------------------------------

    @Test
    fun colorToHsv_red() {
        val hsv = colorIntToHsv(0xFFFF0000.toInt())
        assertThat(hsv[0]).isWithin(0.5f).of(0f)
        assertThat(hsv[1]).isWithin(0.01f).of(1f)
        assertThat(hsv[2]).isWithin(0.01f).of(1f)
    }

    @Test
    fun hsv_round_trips_through_color_int() {
        val samples = listOf(
            Triple(15f, 0.8f, 0.9f),
            Triple(123f, 0.42f, 0.67f),
            Triple(213f, 0.55f, 0.83f),
            Triple(300f, 1f, 0.5f),
        )
        for ((h, s, v) in samples) {
            val hsv = colorIntToHsv(hsvToColorInt(h, s, v))
            assertThat(hsv[0]).isWithin(1.5f).of(h)
            assertThat(hsv[1]).isWithin(0.02f).of(s)
            assertThat(hsv[2]).isWithin(0.02f).of(v)
        }
    }
}
