package cy.txtracker.notify

import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.service.SummaryCadence
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** (rangeStartInclusive, rangeEndExclusive, label). Used by [SummaryWorker]. */
data class SummaryRange(val start: Instant, val endExclusive: Instant, val label: String)

/**
 * Returns the time range the summary should cover for [cadence] given [now],
 * along with a user-facing label. All boundaries are computed in
 * [MalaysiaTimeZone]; [endExclusive] is always exactly [now].
 */
fun rangeFor(cadence: SummaryCadence, now: Instant): SummaryRange {
    val tz = MalaysiaTimeZone
    val localNow = now.toLocalDateTime(tz)
    return when (cadence) {
        SummaryCadence.OFF -> error("rangeFor called with OFF — caller should guard")
        SummaryCadence.DAILY -> {
            val startOfDay = localNow.date.atStartOfDayIn(tz)
            SummaryRange(startOfDay, now, "Today")
        }
        SummaryCadence.WEEKLY -> {
            // kotlinx.datetime DayOfWeek.ordinal: MONDAY=0 .. SUNDAY=6
            val daysFromMonday = localNow.dayOfWeek.ordinal
            val mondayDate = localNow.date.minus(daysFromMonday, DateTimeUnit.DAY)
            SummaryRange(mondayDate.atStartOfDayIn(tz), now, "This week")
        }
        SummaryCadence.MONTHLY -> {
            val firstOfMonth = LocalDateTime(
                localNow.year, localNow.month, 1, 0, 0,
            ).toInstant(tz)
            SummaryRange(firstOfMonth, now, "This month")
        }
    }
}

/**
 * Milliseconds until the next firing time for [cadence] at [hour] in
 * [MalaysiaTimeZone], measured from [now]. For DAILY: today at [hour] if still
 * future, otherwise tomorrow at [hour]. For WEEKLY: next Monday at [hour]
 * (including today if today is Monday and the hour is still future). For
 * MONTHLY: next 1st-of-month at [hour]. OFF is invalid input.
 */
fun millisUntilNextFiring(cadence: SummaryCadence, hour: Int, now: Instant): Long {
    require(cadence != SummaryCadence.OFF)
    require(hour in 0..23)
    val tz = MalaysiaTimeZone
    val localNow = now.toLocalDateTime(tz)
    val todayAtHour = LocalDateTime(
        localNow.year, localNow.month, localNow.dayOfMonth, hour, 0,
    ).toInstant(tz)

    val target: Instant = when (cadence) {
        SummaryCadence.OFF -> error("filtered")
        SummaryCadence.DAILY -> {
            if (todayAtHour > now) todayAtHour
            else todayAtHour.plus(1, DateTimeUnit.DAY, tz)
        }
        SummaryCadence.WEEKLY -> {
            // MONDAY.ordinal=0; daysToMonday = (0 - ordinal).mod(7)
            val daysToMonday = ((-localNow.dayOfWeek.ordinal).mod(7))
            val candidate = todayAtHour.plus(daysToMonday, DateTimeUnit.DAY, tz)
            if (candidate > now) candidate
            else candidate.plus(7, DateTimeUnit.DAY, tz)
        }
        SummaryCadence.MONTHLY -> {
            val firstOfMonthAtHour = LocalDateTime(
                localNow.year, localNow.month, 1, hour, 0,
            ).toInstant(tz)
            if (firstOfMonthAtHour > now) firstOfMonthAtHour
            else firstOfMonthAtHour.plus(1, DateTimeUnit.MONTH, tz)
        }
    }
    return target.toEpochMilliseconds() - now.toEpochMilliseconds()
}
