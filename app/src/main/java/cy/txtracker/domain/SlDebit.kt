package cy.txtracker.domain

/**
 * Pure helpers for the SL Debit feature. No Android / Room dependencies so they are
 * directly JVM-unit-testable. See docs/superpowers/specs/2026-05-29-sl-debit-design.md.
 */

/**
 * The default SL Debit share for a transaction: [percent] of [amountMinor], rounded to the
 * nearest minor unit (cent). [percent] is clamped to 0..100 of the amount so a misconfigured
 * value can never produce a share outside `[0, amountMinor]`.
 */
fun slDebitDefaultShareMinor(amountMinor: Long, percent: Int): Long {
    val p = percent.coerceIn(0, 100)
    val raw = Math.round(amountMinor * p / 100.0)
    return raw.coerceIn(0L, amountMinor)
}

/** A share is valid (the toggle may be saved) when it is in `1..amountMinor`. */
fun isValidShareMinor(shareMinor: Long, amountMinor: Long): Boolean =
    shareMinor in 1L..amountMinor

/** SL Debit pool balance: total deposited minus total shared (drawn down). May be negative. */
fun slDebitBalanceMinor(depositSumMinor: Long, shareSumMinor: Long): Long =
    depositSumMinor - shareSumMinor
