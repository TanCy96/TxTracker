package cy.txtracker.domain

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * A calendar year+month, used by the home screen to query a month's transactions and totals.
 *
 * Backed by ints rather than `kotlinx.datetime.LocalDate` so navigation arithmetic
 * (`next()` / `previous()`) is trivially correct and obviously total.
 */
data class YearMonth(val year: Int, val month: Int) {
    init {
        require(month in 1..12) { "month must be 1..12, got $month" }
    }

    /** Inclusive start instant of this month, midnight local in [zone]. */
    fun start(zone: TimeZone = MalaysiaTimeZone): Instant =
        LocalDate(year, month, 1).atStartOfDayIn(zone)

    /** Exclusive end instant: the start of the following month. */
    fun endExclusive(zone: TimeZone = MalaysiaTimeZone): Instant = next().start(zone)

    fun next(): YearMonth =
        if (month == 12) YearMonth(year + 1, 1) else YearMonth(year, month + 1)

    fun previous(): YearMonth =
        if (month == 1) YearMonth(year - 1, 12) else YearMonth(year, month - 1)

    companion object {
        fun current(
            zone: TimeZone = MalaysiaTimeZone,
            clock: Clock = Clock.System,
        ): YearMonth {
            val now = clock.now().toLocalDateTime(zone)
            return YearMonth(now.year, now.monthNumber)
        }
    }
}
