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
