package cy.txtracker.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Coarse time-of-day buckets used by the description-learning system.
 *
 * Half-open hour intervals (interpreted in the active time zone):
 *   MORNING     [05:00, 11:00)
 *   MIDDAY      [11:00, 15:00)
 *   AFTERNOON   [15:00, 18:00)
 *   EVENING     [18:00, 22:00)
 *   LATE_NIGHT  [22:00, 05:00)  -- wraps midnight
 */
enum class TimeBucket {
    MORNING,
    MIDDAY,
    AFTERNOON,
    EVENING,
    LATE_NIGHT,
}

/** Default zone for bucket interpretation. The user is in Malaysia (UTC+8, no DST). */
val MalaysiaTimeZone: TimeZone = TimeZone.of("Asia/Kuala_Lumpur")

/** Maps an [Instant] to its [TimeBucket] in the supplied [zone]. */
fun bucketOf(instant: Instant, zone: TimeZone = MalaysiaTimeZone): TimeBucket {
    val hour = instant.toLocalDateTime(zone).hour
    return when (hour) {
        in 5..10 -> TimeBucket.MORNING
        in 11..14 -> TimeBucket.MIDDAY
        in 15..17 -> TimeBucket.AFTERNOON
        in 18..21 -> TimeBucket.EVENING
        else -> TimeBucket.LATE_NIGHT
    }
}
