package cy.txtracker.notify

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationsTest {

    @Test
    fun formatMinor_pads_two_decimal_digits() {
        assertThat(formatMinor(0L)).isEqualTo("0.00")
        assertThat(formatMinor(5L)).isEqualTo("0.05")
        assertThat(formatMinor(50L)).isEqualTo("0.50")
        assertThat(formatMinor(100L)).isEqualTo("1.00")
        assertThat(formatMinor(18050L)).isEqualTo("180.50")
        assertThat(formatMinor(123456L)).isEqualTo("1234.56")
    }
}
