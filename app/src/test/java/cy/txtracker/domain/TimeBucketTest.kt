package cy.txtracker.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Test

class TimeBucketTest {

    /** Constructs an Instant that *means* the given wall-clock time in Asia/Kuala_Lumpur. */
    private fun klTime(hour: Int, minute: Int = 0, second: Int = 0): kotlinx.datetime.Instant =
        LocalDateTime(2026, 5, 9, hour, minute, second)
            .toInstant(MalaysiaTimeZone)

    // Each bucket has at least one canonical hour.

    @Test fun morning_canonical() = assertThat(bucketOf(klTime(8))).isEqualTo(TimeBucket.MORNING)
    @Test fun midday_canonical() = assertThat(bucketOf(klTime(12, 30))).isEqualTo(TimeBucket.MIDDAY)
    @Test fun afternoon_canonical() = assertThat(bucketOf(klTime(16))).isEqualTo(TimeBucket.AFTERNOON)
    @Test fun evening_canonical() = assertThat(bucketOf(klTime(20))).isEqualTo(TimeBucket.EVENING)
    @Test fun late_night_canonical() = assertThat(bucketOf(klTime(23))).isEqualTo(TimeBucket.LATE_NIGHT)

    // Boundaries: each interval is half-open [start, end).

    @Test
    fun boundary_at_5_starts_morning() {
        assertThat(bucketOf(klTime(4, 59, 59))).isEqualTo(TimeBucket.LATE_NIGHT)
        assertThat(bucketOf(klTime(5, 0, 0))).isEqualTo(TimeBucket.MORNING)
    }

    @Test
    fun boundary_at_11_starts_midday() {
        assertThat(bucketOf(klTime(10, 59, 59))).isEqualTo(TimeBucket.MORNING)
        assertThat(bucketOf(klTime(11, 0, 0))).isEqualTo(TimeBucket.MIDDAY)
    }

    @Test
    fun boundary_at_15_starts_afternoon() {
        assertThat(bucketOf(klTime(14, 59, 59))).isEqualTo(TimeBucket.MIDDAY)
        assertThat(bucketOf(klTime(15, 0, 0))).isEqualTo(TimeBucket.AFTERNOON)
    }

    @Test
    fun boundary_at_18_starts_evening() {
        assertThat(bucketOf(klTime(17, 59, 59))).isEqualTo(TimeBucket.AFTERNOON)
        assertThat(bucketOf(klTime(18, 0, 0))).isEqualTo(TimeBucket.EVENING)
    }

    @Test
    fun boundary_at_22_starts_late_night() {
        assertThat(bucketOf(klTime(21, 59, 59))).isEqualTo(TimeBucket.EVENING)
        assertThat(bucketOf(klTime(22, 0, 0))).isEqualTo(TimeBucket.LATE_NIGHT)
    }

    @Test
    fun late_night_wraps_through_midnight() {
        // 22:00 → midnight → 04:59 should all be LATE_NIGHT.
        assertThat(bucketOf(klTime(23, 30))).isEqualTo(TimeBucket.LATE_NIGHT)
        assertThat(bucketOf(klTime(0, 0))).isEqualTo(TimeBucket.LATE_NIGHT)
        assertThat(bucketOf(klTime(2, 30))).isEqualTo(TimeBucket.LATE_NIGHT)
        assertThat(bucketOf(klTime(4, 59, 59))).isEqualTo(TimeBucket.LATE_NIGHT)
    }

    @Test
    fun zone_parameter_changes_interpretation() {
        // 11:00 UTC == 19:00 in Kuala Lumpur (UTC+8). Same Instant, different bucket per zone.
        val instant = LocalDateTime(2026, 5, 9, 11, 0).toInstant(TimeZone.UTC)
        assertThat(bucketOf(instant, TimeZone.UTC)).isEqualTo(TimeBucket.MIDDAY)
        assertThat(bucketOf(instant, MalaysiaTimeZone)).isEqualTo(TimeBucket.EVENING)
    }

    @Test
    fun default_zone_is_malaysia() {
        // Sanity: 12:30 KL hits MIDDAY without supplying a zone explicitly.
        val noon = LocalDateTime(2026, 5, 9, 12, 30).toInstant(MalaysiaTimeZone)
        assertThat(bucketOf(noon)).isEqualTo(TimeBucket.MIDDAY)
    }
}
