package cy.txtracker.ui.home

import cy.txtracker.ui.foreign.ForeignFilter

/**
 * Returns `HomeFilter.All` when [filter] is a category whose id no longer appears in the
 * current [breakdown] (i.e., the filter would point at an absent chip after this month's
 * data refresh). Returns [filter] unchanged in every other case. Pure.
 */
internal fun snapStaleHomeCategoryToAll(
    filter: HomeFilter,
    breakdown: List<CategoryBreakdownEntry>,
): HomeFilter {
    if (filter !is HomeFilter.Category) return filter
    val visible = breakdown.any { it.category?.id == filter.id }
    return if (visible) filter else HomeFilter.All
}

/**
 * Foreign equivalent of [snapStaleHomeCategoryToAll]. Same rule, different sealed type.
 */
internal fun snapStaleForeignCategoryToAll(
    filter: ForeignFilter,
    breakdown: List<CategoryBreakdownEntry>,
): ForeignFilter {
    if (filter !is ForeignFilter.Category) return filter
    val visible = breakdown.any { it.category?.id == filter.id }
    return if (visible) filter else ForeignFilter.All
}
