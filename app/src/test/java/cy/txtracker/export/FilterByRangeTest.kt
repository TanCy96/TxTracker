package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import cy.txtracker.data.Transaction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test

class FilterByRangeTest {

    private fun tx(occurredAt: Instant) = Transaction(
        id = 0,
        amountMinor = 1000,
        currency = "MYR",
        merchantRaw = "M",
        merchantNormalized = "M",
        categoryId = null,
        description = null,
        occurredAt = occurredAt,
        timeBucket = TimeBucket.MIDDAY,
        sourceApp = "manual",
        rawText = null,
        direction = Direction.OUT,
        createdAt = occurredAt,
        notificationDedupeKey = "k-$occurredAt",
        fundingSourceId = null,
    )

    private val january = ExportDateRange(LocalDate(2026, 1, 1), LocalDate(2026, 1, 31))

    @Test
    fun bounds_are_malaysia_start_of_day_and_next_day_after_end() {
        val (start, endExclusive) = malaysiaDateRangeBounds(january)
        // 2026-01-01 00:00 +08:00 == 2025-12-31 16:00 UTC
        assertThat(start).isEqualTo(Instant.parse("2025-12-31T16:00:00Z"))
        // 2026-02-01 00:00 +08:00 == 2026-01-31 16:00 UTC
        assertThat(endExclusive).isEqualTo(Instant.parse("2026-01-31T16:00:00Z"))
    }

    @Test
    fun tx_exactly_at_start_is_included() {
        val t = tx(Instant.parse("2025-12-31T16:00:00Z")) // 2026-01-01 00:00 MYT
        assertThat(filterByRange(listOf(t), january)).containsExactly(t)
    }

    @Test
    fun tx_late_on_end_day_is_included() {
        val t = tx(Instant.parse("2026-01-31T15:30:00Z")) // 2026-01-31 23:30 MYT
        assertThat(filterByRange(listOf(t), january)).containsExactly(t)
    }

    @Test
    fun tx_at_next_malaysia_midnight_is_excluded() {
        val t = tx(Instant.parse("2026-01-31T16:00:00Z")) // 2026-02-01 00:00 MYT == endExclusive
        assertThat(filterByRange(listOf(t), january)).isEmpty()
    }

    @Test
    fun tx_just_before_start_is_excluded() {
        val t = tx(Instant.parse("2025-12-31T15:00:00Z")) // 2025-12-31 23:00 MYT
        assertThat(filterByRange(listOf(t), january)).isEmpty()
    }

    @Test
    fun filtered_by_malaysia_day_not_utc_day() {
        // 2026-01-31 17:00 UTC is still Jan 31 in UTC, but 2026-02-01 01:00 in MYT → February.
        // A January range must EXCLUDE it; proves we filter by Malaysia day, not UTC day.
        val t = tx(Instant.parse("2026-01-31T17:00:00Z"))
        assertThat(filterByRange(listOf(t), january)).isEmpty()
    }

    @Test
    fun single_day_range_includes_that_whole_malaysia_day() {
        val day = ExportDateRange(LocalDate(2026, 1, 1), LocalDate(2026, 1, 1))
        val t = tx(Instant.parse("2026-01-01T15:30:00Z")) // 2026-01-01 23:30 MYT
        assertThat(filterByRange(listOf(t), day)).containsExactly(t)
    }

    @Test
    fun null_range_returns_input_unchanged() {
        val txs = listOf(tx(Instant.parse("2020-06-15T00:00:00Z")))
        assertThat(filterByRange(txs, null)).isEqualTo(txs)
    }
}
