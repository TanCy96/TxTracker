package cy.txtracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlDebitTest {
    @Test fun `default share is percent of amount, rounded to nearest cent`() {
        assertEquals(4000L, slDebitDefaultShareMinor(10_000L, 40))   // RM100 @ 40% = RM40.00
        assertEquals(4000L, slDebitDefaultShareMinor(10_001L, 40))   // 4000.4 -> 4000
        assertEquals(5020L, slDebitDefaultShareMinor(12_550L, 40))   // RM125.50 @ 40% = RM50.20
        assertEquals(0L, slDebitDefaultShareMinor(0L, 40))
    }

    @Test fun `default share clamps an out-of-range percent`() {
        assertEquals(10_000L, slDebitDefaultShareMinor(10_000L, 150)) // capped at the amount
        assertEquals(0L, slDebitDefaultShareMinor(10_000L, -5))       // floored at 0
    }

    @Test fun `valid share is between 1 and the full amount inclusive`() {
        assertTrue(isValidShareMinor(1L, 10_000L))
        assertTrue(isValidShareMinor(10_000L, 10_000L))
        assertFalse(isValidShareMinor(0L, 10_000L))
        assertFalse(isValidShareMinor(10_001L, 10_000L))
    }

    @Test fun `balance is deposits minus shares`() {
        assertEquals(46_000L, slDebitBalanceMinor(depositSumMinor = 50_000L, shareSumMinor = 4_000L))
        assertEquals(-4_000L, slDebitBalanceMinor(depositSumMinor = 0L, shareSumMinor = 4_000L))
    }
}
