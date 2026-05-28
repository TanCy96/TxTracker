package cy.txtracker.ui.home

import cy.txtracker.data.FundingSourceKind

/**
 * Returns true when [kind] should be shown given the active [active] filter set.
 * An empty set means "no filter — show everything". A non-empty set requires the tx's
 * kind to be present. Null kind (unlinked tx) never matches an active filter.
 */
fun matchesFundingFilter(active: Set<FundingSourceKind>, kind: FundingSourceKind?): Boolean {
    if (active.isEmpty()) return true
    return kind != null && kind in active
}
