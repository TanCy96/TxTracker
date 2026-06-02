package cy.txtracker.domain

/**
 * Pure helpers for the reimbursed-by-others share. No Android / Room dependencies so they
 * are directly JVM-unit-testable. See docs/superpowers/specs/2026-06-01-reimbursed-share-design.md.
 */

/**
 * A reimbursed amount is valid (the toggle may be saved) when it is in `(0, amountMinor]` —
 * i.e. `1..amountMinor`. The original `amountMinor` is never reduced; `reimbursedMinor` is the
 * portion others returned, subtracted only when computing net spend.
 */
fun isValidReimbursedMinor(reimbursedMinor: Long, amountMinor: Long): Boolean =
    reimbursedMinor in 1L..amountMinor

/**
 * The cached `Transaction.reimbursedMinor` value derived from a transaction's reimbursement
 * entries: the sum of [entryAmountsMinor], or `null` when there are no positive entries
 * (so the column reads "not reimbursed" exactly as a single cleared toggle did).
 */
fun reimbursedTotalMinor(entryAmountsMinor: List<Long>): Long? =
    entryAmountsMinor.sum().takeIf { it > 0 }

/**
 * The full set of reimbursement entries is valid when there is at least one entry, every
 * entry amount is positive, and their sum is within `(0, amountMinor]`. Built on
 * [isValidReimbursedMinor] so the ceiling rule stays in one place.
 */
fun isValidReimbursementTotal(entryAmountsMinor: List<Long>, amountMinor: Long): Boolean =
    entryAmountsMinor.isNotEmpty() &&
        entryAmountsMinor.all { it > 0 } &&
        isValidReimbursedMinor(entryAmountsMinor.sum(), amountMinor)
