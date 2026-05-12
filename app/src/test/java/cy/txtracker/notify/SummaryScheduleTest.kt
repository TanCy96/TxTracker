package cy.txtracker.notify

import com.google.common.truth.Truth.assertThat
import cy.txtracker.service.SummaryCadence
import kotlinx.datetime.Instant
import org.junit.Test

class SummaryScheduleTest {

    // 2026-05-12 is a Tuesday in MalaysiaTimeZone (UTC+8).
    private val tueAfternoon = Instant.parse("2026-05-12T08:30:00Z") // 16:30 MYT
    private val tueMidnight = Instant.parse("2026-05-11T16:00:00Z")  // 00:00 MYT 2026-05-12
    private val tuePre20h = Instant.parse("2026-05-12T11:59:00Z")    // 19:59 MYT
    private val tuePost20h = Instant.parse("2026-05-12T12:01:00Z")   // 20:01 MYT

    @Test
    fun rangeFor_daily_starts_at_midnight_myt() {
        val r = rangeFor(SummaryCadence.DAILY, tueAfternoon)
        assertThat(r.start).isEqualTo(tueMidnight)
        assertThat(r.endExclusive).isEqualTo(tueAfternoon)
        assertThat(r.label).isEqualTo("Today")
    }

    @Test
    fun rangeFor_weekly_starts_at_monday_midnight() {
        val r = rangeFor(SummaryCadence.WEEKLY, tueAfternoon)
        // Monday 2026-05-11 at 00:00 MYT = 2026-05-10T16:00:00Z
        assertThat(r.start).isEqualTo(Instant.parse("2026-05-10T16:00:00Z"))
        assertThat(r.label).isEqualTo("This week")
    }

    @Test
    fun rangeFor_monthly_starts_at_first_of_month_midnight() {
        val r = rangeFor(SummaryCadence.MONTHLY, tueAfternoon)
        // 2026-05-01 at 00:00 MYT = 2026-04-30T16:00:00Z
        assertThat(r.start).isEqualTo(Instant.parse("2026-04-30T16:00:00Z"))
        assertThat(r.label).isEqualTo("This month")
    }

    @Test
    fun millisUntilNextFiring_daily_future_today() {
        val delay = millisUntilNextFiring(SummaryCadence.DAILY, 20, tuePre20h)
        // 19:59 → 20:00 = 60_000 ms
        assertThat(delay).isEqualTo(60_000L)
    }

    @Test
    fun millisUntilNextFiring_daily_rolls_to_tomorrow() {
        val delay = millisUntilNextFiring(SummaryCadence.DAILY, 20, tuePost20h)
        // 20:01 → next 20:00 = (24h - 1min) = 86_340_000 ms
        assertThat(delay).isEqualTo(86_340_000L)
    }

    @Test
    fun millisUntilNextFiring_weekly_rolls_when_past_monday() {
        // tuePre20h is Tuesday 19:59 MYT. Next Monday 20:00 MYT is 6 days + 1 minute away.
        val delay = millisUntilNextFiring(SummaryCadence.WEEKLY, 20, tuePre20h)
        assertThat(delay).isEqualTo(6L * 24 * 60 * 60 * 1000 + 60_000L)
    }

    @Test
    fun millisUntilNextFiring_monthly_rolls_to_next_first() {
        // 2026-05-12 19:59 MYT → next 2026-06-01 20:00 MYT.
        // 2026-06-01 20:00 MYT = 2026-06-01T12:00:00Z
        // 2026-05-12 19:59 MYT = 2026-05-12T11:59:00Z
        // Delta = (2026-06-01T12:00:00Z) - (2026-05-12T11:59:00Z)
        //       = 20 days + 1 minute - 23 hours = approx 19d 1h 1m
        // Let's compute exactly: from 2026-05-12T11:59:00Z to 2026-06-01T12:00:00Z
        //   2026-05-12 → 2026-06-01 = 20 days
        //   11:59:00Z → 12:00:00Z   = +1 minute
        // Total = 20 * 86_400_000 + 60_000 = 1_728_060_000 ms
        val expected = 20L * 24 * 60 * 60 * 1000 + 60_000L
        val delay = millisUntilNextFiring(SummaryCadence.MONTHLY, 20, tuePre20h)
        assertThat(delay).isEqualTo(expected)
    }
}
