package cy.txtracker.ui.format

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.domain.YearMonth
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test

class FormattersTest {

    @Test fun zero() = assertThat(formatMyr(0)).isEqualTo("RM 0.00")
    @Test fun under_one_ringgit() = assertThat(formatMyr(50)).isEqualTo("RM 0.50")
    @Test fun small_amount() = assertThat(formatMyr(1250)).isEqualTo("RM 12.50")
    @Test fun thousands_separator() = assertThat(formatMyr(123456)).isEqualTo("RM 1,234.56")
    @Test fun millions() = assertThat(formatMyr(1_234_567_89)).isEqualTo("RM 1,234,567.89")
    @Test fun negative_amount() = assertThat(formatMyr(-1250)).isEqualTo("-RM 12.50")
    @Test fun pads_cents_to_two_digits() = assertThat(formatMyr(1209)).isEqualTo("RM 12.09")

    @Test
    fun formatYearMonth_uses_full_month_name() {
        assertThat(formatYearMonth(YearMonth(2026, 5))).isEqualTo("May 2026")
        assertThat(formatYearMonth(YearMonth(2026, 1))).isEqualTo("January 2026")
        assertThat(formatYearMonth(YearMonth(2026, 12))).isEqualTo("December 2026")
    }

    @Test
    fun formatDayHeader_today() {
        val today = LocalDate(2026, 5, 9)
        assertThat(formatDayHeader(today, today)).isEqualTo("Today")
    }

    @Test
    fun formatDayHeader_yesterday() {
        val today = LocalDate(2026, 5, 9)
        assertThat(formatDayHeader(LocalDate(2026, 5, 8), today)).isEqualTo("Yesterday")
    }

    @Test
    fun formatDayHeader_same_year_omits_year() {
        val today = LocalDate(2026, 5, 9)
        // 2026-05-04 is a Monday.
        assertThat(formatDayHeader(LocalDate(2026, 5, 4), today)).isEqualTo("Mon, 4 May")
    }

    @Test
    fun formatDayHeader_different_year_includes_year() {
        val today = LocalDate(2026, 5, 9)
        // 2025-12-25 is a Thursday.
        assertThat(formatDayHeader(LocalDate(2025, 12, 25), today)).isEqualTo("Thu, 25 December 2025")
    }

    @Test
    fun formatTimeOfDay_pads_to_two_digits_in_kl_zone() {
        // 04:30 UTC == 12:30 KL.
        val instant = Instant.parse("2026-05-09T04:30:00Z")
        assertThat(formatTimeOfDay(instant, MalaysiaTimeZone)).isEqualTo("12:30")

        // 21:05 UTC == 05:05 next-day KL.
        val late = Instant.parse("2026-05-09T21:05:00Z")
        assertThat(formatTimeOfDay(late, MalaysiaTimeZone)).isEqualTo("05:05")
    }
}
