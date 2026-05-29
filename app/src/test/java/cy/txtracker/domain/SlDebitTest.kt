package cy.txtracker.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SlDebitTest {
    @Test fun `default share is percent of amount, rounded to nearest cent`() {
        assertThat(slDebitDefaultShareMinor(10_000L, 40)).isEqualTo(4000L)   // RM100 @ 40% = RM40.00
        assertThat(slDebitDefaultShareMinor(10_001L, 40)).isEqualTo(4000L)   // 4000.4 -> 4000
        assertThat(slDebitDefaultShareMinor(12_550L, 40)).isEqualTo(5020L)   // RM125.50 @ 40% = RM50.20
        assertThat(slDebitDefaultShareMinor(0L, 40)).isEqualTo(0L)
    }

    @Test fun `default share clamps an out-of-range percent`() {
        assertThat(slDebitDefaultShareMinor(10_000L, 150)).isEqualTo(10_000L) // capped at the amount
        assertThat(slDebitDefaultShareMinor(10_000L, -5)).isEqualTo(0L)       // floored at 0
    }

    @Test fun `valid share is between 1 and the full amount inclusive`() {
        assertThat(isValidShareMinor(1L, 10_000L)).isTrue()
        assertThat(isValidShareMinor(10_000L, 10_000L)).isTrue()
        assertThat(isValidShareMinor(0L, 10_000L)).isFalse()
        assertThat(isValidShareMinor(10_001L, 10_000L)).isFalse()
    }

    @Test fun `balance is deposits minus shares`() {
        assertThat(slDebitBalanceMinor(depositSumMinor = 50_000L, shareSumMinor = 4_000L)).isEqualTo(46_000L)
        assertThat(slDebitBalanceMinor(depositSumMinor = 0L, shareSumMinor = 4_000L)).isEqualTo(-4_000L)
    }
}
