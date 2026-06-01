package cy.txtracker.ui.insights

import cy.txtracker.data.Category
import cy.txtracker.data.Direction
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.Transaction
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.domain.YearMonth
import cy.txtracker.ui.common.KIND_ORDER
import cy.txtracker.ui.common.fundingBucketLabel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Pure chart aggregation over a window's transactions. No Android/Compose deps, so it is the
 * JVM-unit-test surface for Insights.
 *
 * **All spend sums route through [chartAmountMinor]; never read [Transaction.amountMinor] directly
 * in a fold here** — see the MERGE-POINT note below.
 */

/**
 * Effective spend amount for charts, in minor units — net of the SL Debit share, matching the
 * netted Home / `observeTotalBetween` queries. This is the feature/share-debit adaptation of the
 * MERGE-POINT through which every chart spend sum flows (on `main` it is just `amountMinor`).
 * Foreign-currency rows always have `slShareMinor == null`, so per-currency charts are unaffected.
 */
internal fun Transaction.chartAmountMinor(): Long = amountMinor - (slShareMinor ?: 0L)

// Fixed colors for buckets that have no DB-stored color (category slices use Category.color).
private const val NEUTRAL_COLOR = 0xFF9E9E9E.toInt()
private const val UNVERIFIED_KEY = "unverified"
private const val UNVERIFIED_LABEL = "Unverified"
private const val UNATTRIBUTED_KEY = "unattributed"
private const val UNATTRIBUTED_LABEL = "Unattributed"

private fun fundingKindColor(kind: FundingSourceKind): Int = when (kind) {
    FundingSourceKind.CREDIT_CARD -> 0xFF5C6BC0.toInt()
    FundingSourceKind.E_WALLET -> 0xFF26A69A.toInt()
    FundingSourceKind.DEBIT_BANK -> 0xFF66BB6A.toInt()
    FundingSourceKind.CASH -> 0xFFFFA726.toInt()
}

/** A transaction's series identity for the active grouping. [sortRank] orders the legend/slices. */
private data class SeriesInfo(val key: String, val label: String, val colorArgb: Int, val sortRank: Int)

private fun classify(
    tx: Transaction,
    groupBy: GroupBy,
    categoriesById: Map<Long, Category>,
    fundingById: Map<Long, FundingSource>,
): SeriesInfo = when (groupBy) {
    GroupBy.CATEGORY -> {
        val cat = tx.categoryId?.let { categoriesById[it] }
        if (cat != null) SeriesInfo("cat:${cat.id}", cat.name, cat.color, cat.sortOrder)
        else SeriesInfo(UNVERIFIED_KEY, UNVERIFIED_LABEL, NEUTRAL_COLOR, Int.MAX_VALUE)
    }
    GroupBy.FUNDING_SOURCE -> {
        val kind = tx.fundingSourceId?.let { fundingById[it]?.kind }
        if (kind != null) {
            SeriesInfo("fund:${kind.name}", fundingBucketLabel(kind), fundingKindColor(kind), KIND_ORDER.indexOf(kind))
        } else {
            SeriesInfo(UNATTRIBUTED_KEY, UNATTRIBUTED_LABEL, NEUTRAL_COLOR, Int.MAX_VALUE)
        }
    }
}

/** Per-series totals for the pie / legend, sorted by category sortOrder (funding: canonical kind order), unverified/unattributed last. */
fun groupedBreakdown(
    txs: List<Transaction>,
    groupBy: GroupBy,
    categoriesById: Map<Long, Category>,
    fundingById: Map<Long, FundingSource>,
): List<BreakdownSlice> =
    txs.filter { it.direction == Direction.OUT }
        .groupBy { classify(it, groupBy, categoriesById, fundingById).key }
        .map { (_, rows) ->
            val info = classify(rows.first(), groupBy, categoriesById, fundingById)
            info to BreakdownSlice(info.key, info.label, info.colorArgb, rows.sumOf { it.chartAmountMinor() })
        }
        .sortedWith(compareBy({ it.first.sortRank }, { it.first.label }))
        .map { it.second }

/** One bucket per Malaysia-local day in ascending order; per-day totals split by series key. */
fun dailyStacked(
    txs: List<Transaction>,
    groupBy: GroupBy,
    categoriesById: Map<Long, Category>,
    fundingById: Map<Long, FundingSource>,
    zone: TimeZone = MalaysiaTimeZone,
): List<DayBucket> =
    txs.filter { it.direction == Direction.OUT }
        .groupBy { it.occurredAt.toLocalDateTime(zone).date }
        .toSortedMap()
        .map { (date, rows) ->
            val byKey = rows.groupBy { classify(it, groupBy, categoriesById, fundingById).key }
                .mapValues { (_, r) -> r.sumOf { it.chartAmountMinor() } }
            DayBucket(date, byKey)
        }

/** Total spend per calendar month in ascending order. */
fun monthlyTotals(txs: List<Transaction>, zone: TimeZone = MalaysiaTimeZone): List<MonthBucket> =
    txs.filter { it.direction == Direction.OUT }
        .groupBy { val d = it.occurredAt.toLocalDateTime(zone); YearMonth(d.year, d.monthNumber) }
        .map { (ym, rows) -> MonthBucket(ym, rows.sumOf { it.chartAmountMinor() }) }
        .sortedWith(compareBy({ it.yearMonth.year }, { it.yearMonth.month }))

/** Monthly totals restricted to a single category (null = the Unverified bucket). */
fun monthlyTotalsForCategory(
    txs: List<Transaction>,
    categoryId: Long?,
    zone: TimeZone = MalaysiaTimeZone,
): List<MonthBucket> = monthlyTotals(txs.filter { it.categoryId == categoryId }, zone)

/** OUT rows whose grouping series matches [key] (a [BreakdownSlice.key]) — drives chart drill-down. */
fun transactionsForKey(
    txs: List<Transaction>,
    key: String,
    groupBy: GroupBy,
    categoriesById: Map<Long, Category>,
    fundingById: Map<Long, FundingSource>,
): List<Transaction> =
    txs.filter { it.direction == Direction.OUT && classify(it, groupBy, categoriesById, fundingById).key == key }

/** Spend-vs-budget progress; null when no positive budget is set. [fraction] is uncapped. */
fun budgetProgress(spentMinor: Long, budgetMinor: Long?): BudgetProgress? =
    budgetMinor?.takeIf { it > 0 }?.let {
        BudgetProgress(
            spentMinor = spentMinor,
            budgetMinor = it,
            fraction = spentMinor.toFloat() / it,
            overBudget = spentMinor > it,
        )
    }

/**
 * Joins this-month per-category spend with saved category budgets. Drops budgets whose category no
 * longer exists and orders over-budget first, then by how far through the budget, then sortOrder.
 */
fun categoryBudgetProgress(
    monthCategorySpend: Map<Long, Long>,
    budgets: Map<Long, Long>,
    categoriesById: Map<Long, Category>,
): List<CategoryBudgetProgress> =
    budgets.mapNotNull { (categoryId, budgetMinor) ->
        val category = categoriesById[categoryId] ?: return@mapNotNull null
        val progress = budgetProgress(monthCategorySpend[categoryId] ?: 0L, budgetMinor) ?: return@mapNotNull null
        CategoryBudgetProgress(category, progress)
    }.sortedWith(
        compareByDescending<CategoryBudgetProgress> { it.progress.overBudget }
            .thenByDescending { it.progress.fraction }
            .thenBy { it.category.sortOrder },
    )
