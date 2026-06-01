package cy.txtracker.ui.insights

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import cy.txtracker.data.Direction
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.Transaction
import cy.txtracker.domain.TimeBucket
import cy.txtracker.domain.YearMonth
import kotlinx.datetime.Instant
import org.junit.Test

class InsightsAggregatorTest {

    private fun tx(
        amountMinor: Long,
        occurredAt: Instant,
        categoryId: Long? = null,
        fundingSourceId: Long? = null,
        direction: Direction = Direction.OUT,
    ) = Transaction(
        id = 0,
        amountMinor = amountMinor,
        currency = "MYR",
        merchantRaw = "M",
        merchantNormalized = "M",
        categoryId = categoryId,
        description = null,
        occurredAt = occurredAt,
        timeBucket = TimeBucket.MIDDAY,
        sourceApp = "manual",
        rawText = null,
        direction = direction,
        createdAt = occurredAt,
        notificationDedupeKey = "k-$occurredAt-$amountMinor-$categoryId-$fundingSourceId-$direction",
        fundingSourceId = fundingSourceId,
    )

    private fun category(id: Long, name: String, sortOrder: Int, color: Int = 0xFF000000.toInt()) =
        Category(id = id, name = name, color = color, isCustom = false, sortOrder = sortOrder)

    private fun funding(id: Long, kind: FundingSourceKind) = FundingSource(
        id = id,
        kind = kind,
        displayName = kind.name,
        last4 = null,
        sourceAppHint = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private val food = category(1, "Food", sortOrder = 0, color = 0xFFEF5350.toInt())
    private val transport = category(2, "Transport", sortOrder = 1, color = 0xFF42A5F5.toInt())
    private val categoriesById = mapOf(1L to food, 2L to transport)

    private val may = Instant.parse("2026-05-10T04:00:00Z") // May 10 12:00 MYT
    private val june = Instant.parse("2026-06-10T04:00:00Z") // Jun 10 12:00 MYT

    // ---- groupedBreakdown: category ----

    @Test
    fun breakdown_by_category_sums_and_orders_with_unverified_last() {
        val txs = listOf(
            tx(1000, may, categoryId = 2),
            tx(2000, may, categoryId = 1),
            tx(500, may, categoryId = 1),
            tx(300, may, categoryId = null),
        )
        val slices = groupedBreakdown(txs, GroupBy.CATEGORY, categoriesById, emptyMap())
        assertThat(slices.map { it.label }).containsExactly("Food", "Transport", "Unverified").inOrder()
        assertThat(slices.map { it.totalMinor }).containsExactly(2500L, 1000L, 300L).inOrder()
        assertThat(slices[0].colorArgb).isEqualTo(0xFFEF5350.toInt())
        assertThat(slices[0].key).isEqualTo("cat:1")
    }

    @Test
    fun breakdown_excludes_IN_direction() {
        val txs = listOf(
            tx(1000, may, categoryId = 1),
            tx(9999, may, categoryId = 1, direction = Direction.IN),
        )
        val slices = groupedBreakdown(txs, GroupBy.CATEGORY, categoriesById, emptyMap())
        assertThat(slices.single().totalMinor).isEqualTo(1000L)
    }

    // ---- groupedBreakdown: funding source ----

    @Test
    fun breakdown_by_funding_groups_by_kind_with_unattributed_last() {
        val fundingById = mapOf(
            10L to funding(10, FundingSourceKind.CREDIT_CARD),
            11L to funding(11, FundingSourceKind.CASH),
        )
        val txs = listOf(
            tx(1000, may, fundingSourceId = 10),
            tx(2000, may, fundingSourceId = 11),
            tx(500, may, fundingSourceId = null),
        )
        val slices = groupedBreakdown(txs, GroupBy.FUNDING_SOURCE, categoriesById, fundingById)
        assertThat(slices.map { it.label }).containsExactly("Credit Card", "Cash", "Unattributed").inOrder()
        assertThat(slices.map { it.totalMinor }).containsExactly(1000L, 2000L, 500L).inOrder()
    }

    // ---- dailyStacked ----

    @Test
    fun daily_buckets_by_malaysia_day_not_utc() {
        val txs = listOf(
            tx(1000, Instant.parse("2026-01-31T10:00:00Z"), categoryId = 1), // Jan 31 18:00 MYT
            tx(2000, Instant.parse("2026-01-31T17:00:00Z"), categoryId = 1), // Feb 1 01:00 MYT
        )
        val days = dailyStacked(txs, GroupBy.CATEGORY, categoriesById, emptyMap())
        assertThat(days.map { it.date.toString() }).containsExactly("2026-01-31", "2026-02-01").inOrder()
        assertThat(days[0].totalsByKey["cat:1"]).isEqualTo(1000L)
        assertThat(days[1].totalsByKey["cat:1"]).isEqualTo(2000L)
    }

    @Test
    fun daily_sub_totals_split_by_series_and_sum_to_day_total() {
        val txs = listOf(
            tx(1000, may, categoryId = 1),
            tx(2000, may, categoryId = 2),
            tx(500, may, categoryId = 1),
        )
        val day = dailyStacked(txs, GroupBy.CATEGORY, categoriesById, emptyMap()).single()
        assertThat(day.totalsByKey["cat:1"]).isEqualTo(1500L)
        assertThat(day.totalsByKey["cat:2"]).isEqualTo(2000L)
        assertThat(day.totalMinor).isEqualTo(3500L)
    }

    // ---- monthlyTotals ----

    @Test
    fun monthly_totals_bucket_by_year_month_and_exclude_IN() {
        val txs = listOf(
            tx(1000, may, categoryId = 1),
            tx(2000, june, categoryId = 1),
            tx(3000, june, categoryId = 2),
            tx(9999, june, categoryId = 1, direction = Direction.IN),
        )
        assertThat(monthlyTotals(txs)).containsExactly(
            MonthBucket(YearMonth(2026, 5), 1000L),
            MonthBucket(YearMonth(2026, 6), 5000L),
        ).inOrder()
    }

    @Test
    fun monthly_totals_for_category_filters_to_one_category() {
        val txs = listOf(
            tx(1000, may, categoryId = 1),
            tx(2000, may, categoryId = 2),
            tx(3000, june, categoryId = 1),
        )
        assertThat(monthlyTotalsForCategory(txs, categoryId = 1)).containsExactly(
            MonthBucket(YearMonth(2026, 5), 1000L),
            MonthBucket(YearMonth(2026, 6), 3000L),
        ).inOrder()
    }

    // ---- chartAmountMinor (MERGE-POINT guard) ----

    @Test
    fun chart_amount_is_amount_minor_on_main() {
        assertThat(tx(1234, may).chartAmountMinor()).isEqualTo(1234L)
    }

    // ---- transactionsForKey (drill-down) ----

    @Test
    fun transactions_for_category_key_filters_to_that_category() {
        val txs = listOf(
            tx(1000, may, categoryId = 1),
            tx(2000, may, categoryId = 2),
            tx(500, may, categoryId = 1),
            tx(300, may, categoryId = null),
        )
        val food = transactionsForKey(txs, "cat:1", GroupBy.CATEGORY, categoriesById, emptyMap())
        assertThat(food.map { it.amountMinor }).containsExactly(1000L, 500L)
    }

    @Test
    fun transactions_for_unverified_key() {
        val txs = listOf(tx(1000, may, categoryId = 1), tx(300, may, categoryId = null))
        val unverified = transactionsForKey(txs, "unverified", GroupBy.CATEGORY, categoriesById, emptyMap())
        assertThat(unverified.map { it.amountMinor }).containsExactly(300L)
    }

    @Test
    fun transactions_for_funding_key_excludes_IN() {
        val fundingById = mapOf(10L to funding(10, FundingSourceKind.CREDIT_CARD))
        val txs = listOf(
            tx(1000, may, fundingSourceId = 10),
            tx(2000, may, fundingSourceId = 10, direction = Direction.IN),
            tx(500, may, fundingSourceId = null),
        )
        val card = transactionsForKey(txs, "fund:CREDIT_CARD", GroupBy.FUNDING_SOURCE, categoriesById, fundingById)
        assertThat(card.map { it.amountMinor }).containsExactly(1000L)
    }
}
