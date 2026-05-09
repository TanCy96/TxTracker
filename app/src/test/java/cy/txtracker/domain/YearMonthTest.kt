package cy.txtracker.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Test

class YearMonthTest {

    @Test
    fun construction_validates_month_range() {
        runCatching { YearMonth(2026, 0) }.onSuccess { error("expected throw") }
        runCatching { YearMonth(2026, 13) }.onSuccess { error("expected throw") }
    }

    @Test
    fun next_wraps_at_year_end() {
        assertThat(YearMonth(2026, 5).next()).isEqualTo(YearMonth(2026, 6))
        assertThat(YearMonth(2026, 12).next()).isEqualTo(YearMonth(2027, 1))
    }

    @Test
    fun previous_wraps_at_year_start() {
        assertThat(YearMonth(2026, 5).previous()).isEqualTo(YearMonth(2026, 4))
        assertThat(YearMonth(2026, 1).previous()).isEqualTo(YearMonth(2025, 12))
    }

    @Test
    fun start_returns_midnight_in_zone() {
        val start = YearMonth(2026, 5).start(MalaysiaTimeZone)
        // 2026-05-01 00:00 KL == 2026-04-30 16:00 UTC.
        assertThat(start).isEqualTo(Instant.parse("2026-04-30T16:00:00Z"))
    }

    @Test
    fun endExclusive_is_start_of_next_month() {
        val end = YearMonth(2026, 5).endExclusive(MalaysiaTimeZone)
        // 2026-06-01 00:00 KL == 2026-05-31 16:00 UTC.
        assertThat(end).isEqualTo(Instant.parse("2026-05-31T16:00:00Z"))
    }

    @Test
    fun current_uses_supplied_clock_and_zone() {
        // 2026-12-31 23:30 UTC is 2026-01-01 07:30 KL — different month from UTC.
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-12-31T23:30:00Z")
        }
        assertThat(YearMonth.current(MalaysiaTimeZone, fixedClock)).isEqualTo(YearMonth(2027, 1))
        assertThat(YearMonth.current(TimeZone.UTC, fixedClock)).isEqualTo(YearMonth(2026, 12))
    }
}
