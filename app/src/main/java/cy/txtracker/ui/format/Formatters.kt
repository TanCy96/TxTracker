package cy.txtracker.ui.format

import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.domain.YearMonth
import kotlin.math.absoluteValue
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/** "RM 12.50", "RM 1,234.56", "RM 0.00" — handles thousands separators and 2-decimal padding. */
fun formatMyr(amountMinor: Long): String {
    val sign = if (amountMinor < 0) "-" else ""
    val abs = amountMinor.absoluteValue
    val ringgit = abs / 100
    val cents = abs % 100
    val ringgitStr = ringgit.toString().reversed().chunked(3).joinToString(",").reversed()
    return "${sign}RM $ringgitStr.${cents.toString().padStart(2, '0')}"
}

/** "May 2026". */
fun formatYearMonth(ym: YearMonth): String = "${MONTH_NAMES[ym.month - 1]} ${ym.year}"

/**
 * Day header used in transaction lists. Always includes the date so the user can place a
 * row without mental math; prefixes "Today" / "Yesterday" for the two most-recent days as a
 * quick visual marker.
 *
 *   today           -> "Today, 9 May"
 *   yesterday       -> "Yesterday, 8 May"
 *   older same year -> "Mon, 4 May"
 *   different year  -> "Thu, 25 December 2025"
 */
fun formatDayHeader(
    date: LocalDate,
    today: LocalDate = Clock.System.now().toLocalDateTime(MalaysiaTimeZone).date,
): String {
    val month = MONTH_NAMES[date.monthNumber - 1]
    val day = date.dayOfMonth
    return when (today.daysUntil(date)) {
        0 -> "Today, $day $month"
        -1 -> "Yesterday, $day $month"
        else -> {
            val dow = DAY_NAMES[date.dayOfWeek.ordinal]
            if (date.year == today.year) "$dow, $day $month"
            else "$dow, $day $month ${date.year}"
        }
    }
}

/** "12:30" 24-hour wall-clock time in the given zone. */
fun formatTimeOfDay(instant: Instant, zone: TimeZone = MalaysiaTimeZone): String {
    val ldt = instant.toLocalDateTime(zone)
    return "${ldt.hour.toString().padStart(2, '0')}:${ldt.minute.toString().padStart(2, '0')}"
}

private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

// kotlinx.datetime DayOfWeek.ordinal: MONDAY=0 .. SUNDAY=6
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
