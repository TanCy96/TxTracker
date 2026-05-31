package cy.txtracker.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test

class InsightsPeriodTest {

    // 2026-05-15 12:00 MYT — unambiguously mid-May 2026 regardless of zone.
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-05-15T04:00:00Z")
    }

    private fun resolve(
        period: InsightsPeriod,
        start: LocalDate? = null,
        end: LocalDate? = null,
        earliest: Instant? = null,
    ) = resolveInsightsPeriod(
        period = period,
        customStart = start,
        customEnd = end,
        earliestTransaction = earliest,
        zone = MalaysiaTimeZone,
        clock = clock,
    )

    @Test
    fun this_month_matches_year_month_bounds() {
        val r = resolve(InsightsPeriod.THIS_MONTH)
        assertThat(r.startInclusive).isEqualTo(Instant.parse("2026-04-30T16:00:00Z"))
        assertThat(r.endExclusive).isEqualTo(Instant.parse("2026-05-31T16:00:00Z"))
    }

    @Test
    fun last_month_is_previous_calendar_month() {
        val r = resolve(InsightsPeriod.LAST_MONTH)
        assertThat(r.startInclusive).isEqualTo(Instant.parse("2026-03-31T16:00:00Z"))
        assertThat(r.endExclusive).isEqualTo(Instant.parse("2026-04-30T16:00:00Z"))
    }

    @Test
    fun last_3_months_spans_three_whole_months_ending_this_month() {
        // Mar 1 .. Jun 1 (Mar, Apr, May).
        val r = resolve(InsightsPeriod.LAST_3_MONTHS)
        assertThat(r.startInclusive).isEqualTo(Instant.parse("2026-02-28T16:00:00Z"))
        assertThat(r.endExclusive).isEqualTo(Instant.parse("2026-05-31T16:00:00Z"))
    }

    @Test
    fun last_6_months_spans_six_whole_months_ending_this_month() {
        // Dec 1 2025 .. Jun 1 2026 (Dec, Jan, Feb, Mar, Apr, May).
        val r = resolve(InsightsPeriod.LAST_6_MONTHS)
        assertThat(r.startInclusive).isEqualTo(Instant.parse("2025-11-30T16:00:00Z"))
        assertThat(r.endExclusive).isEqualTo(Instant.parse("2026-05-31T16:00:00Z"))
    }

    @Test
    fun this_year_spans_jan_to_next_jan() {
        val r = resolve(InsightsPeriod.THIS_YEAR)
        assertThat(r.startInclusive).isEqualTo(Instant.parse("2025-12-31T16:00:00Z"))
        assertThat(r.endExclusive).isEqualTo(Instant.parse("2026-12-31T16:00:00Z"))
    }

    @Test
    fun all_time_uses_earliest_transaction_when_supplied() {
        val earliest = Instant.parse("2024-01-10T08:00:00Z")
        val r = resolve(InsightsPeriod.ALL_TIME, earliest = earliest)
        assertThat(r.startInclusive).isEqualTo(earliest)
        assertThat(r.endExclusive).isEqualTo(Instant.parse("2026-05-15T04:00:00Z"))
    }

    @Test
    fun all_time_falls_back_to_distant_past_without_earliest() {
        val r = resolve(InsightsPeriod.ALL_TIME, earliest = null)
        assertThat(r.startInclusive).isEqualTo(Instant.DISTANT_PAST)
        assertThat(r.endExclusive).isEqualTo(Instant.parse("2026-05-15T04:00:00Z"))
    }

    @Test
    fun custom_uses_supplied_local_date_bounds_in_malaysia_time() {
        val r = resolve(InsightsPeriod.CUSTOM, start = LocalDate(2026, 1, 1), end = LocalDate(2026, 1, 31))
        // 2026-01-01 00:00 MYT .. 2026-02-01 00:00 MYT.
        assertThat(r.startInclusive).isEqualTo(Instant.parse("2025-12-31T16:00:00Z"))
        assertThat(r.endExclusive).isEqualTo(Instant.parse("2026-01-31T16:00:00Z"))
    }

    @Test
    fun custom_with_only_start_is_a_single_day() {
        val r = resolve(InsightsPeriod.CUSTOM, start = LocalDate(2026, 1, 1))
        assertThat(r.startInclusive).isEqualTo(Instant.parse("2025-12-31T16:00:00Z"))
        assertThat(r.endExclusive).isEqualTo(Instant.parse("2026-01-01T16:00:00Z"))
    }

    @Test
    fun custom_without_bounds_falls_back_to_this_month() {
        val r = resolve(InsightsPeriod.CUSTOM)
        assertThat(r.startInclusive).isEqualTo(Instant.parse("2026-04-30T16:00:00Z"))
        assertThat(r.endExclusive).isEqualTo(Instant.parse("2026-05-31T16:00:00Z"))
    }
}
