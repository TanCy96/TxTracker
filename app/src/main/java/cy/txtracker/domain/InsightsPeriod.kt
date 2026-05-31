package cy.txtracker.domain

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/** Selectable time window for the Insights screen. */
enum class InsightsPeriod { THIS_MONTH, LAST_MONTH, LAST_3_MONTHS, LAST_6_MONTHS, THIS_YEAR, ALL_TIME, CUSTOM }

/** A resolved half-open instant window `[startInclusive, endExclusive)`. */
data class InsightsRange(val startInclusive: Instant, val endExclusive: Instant)

/**
 * Resolves an [InsightsPeriod] to its concrete instant window, reusing [YearMonth] arithmetic
 * and [MalaysiaTimeZone] so day/month boundaries match the rest of the app. Pure and total:
 * [clock] / [zone] are injected for deterministic tests.
 *
 * - The "last N months" windows are whole calendar months **ending with the in-progress month**
 *   (e.g. in May, "Last 3 months" = Mar 1 .. Jun 1).
 * - [InsightsPeriod.ALL_TIME] runs from [earliestTransaction] (or [Instant.DISTANT_PAST] when there
 *   is no data) to now.
 * - [InsightsPeriod.CUSTOM] interprets [customStart]/[customEnd] as inclusive Malaysia-local days
 *   (mirroring CSV export); a missing [customEnd] yields a single day, and a missing [customStart]
 *   falls back to [InsightsPeriod.THIS_MONTH].
 */
fun resolveInsightsPeriod(
    period: InsightsPeriod,
    customStart: LocalDate? = null,
    customEnd: LocalDate? = null,
    earliestTransaction: Instant? = null,
    zone: TimeZone = MalaysiaTimeZone,
    clock: Clock = Clock.System,
): InsightsRange {
    val current = YearMonth.current(zone, clock)
    return when (period) {
        InsightsPeriod.THIS_MONTH ->
            InsightsRange(current.start(zone), current.endExclusive(zone))

        InsightsPeriod.LAST_MONTH ->
            current.previous().let { InsightsRange(it.start(zone), it.endExclusive(zone)) }

        InsightsPeriod.LAST_3_MONTHS ->
            InsightsRange(current.minusMonths(2).start(zone), current.endExclusive(zone))

        InsightsPeriod.LAST_6_MONTHS ->
            InsightsRange(current.minusMonths(5).start(zone), current.endExclusive(zone))

        InsightsPeriod.THIS_YEAR ->
            InsightsRange(YearMonth(current.year, 1).start(zone), YearMonth(current.year, 12).endExclusive(zone))

        InsightsPeriod.ALL_TIME ->
            InsightsRange(earliestTransaction ?: Instant.DISTANT_PAST, clock.now())

        InsightsPeriod.CUSTOM -> {
            if (customStart == null) {
                resolveInsightsPeriod(InsightsPeriod.THIS_MONTH, zone = zone, clock = clock)
            } else {
                val endExclusiveDay = (customEnd ?: customStart).plus(1, DateTimeUnit.DAY)
                InsightsRange(customStart.atStartOfDayIn(zone), endExclusiveDay.atStartOfDayIn(zone))
            }
        }
    }
}

/** Rolls back [n] whole calendar months. */
private fun YearMonth.minusMonths(n: Int): YearMonth {
    var ym = this
    repeat(n) { ym = ym.previous() }
    return ym
}
