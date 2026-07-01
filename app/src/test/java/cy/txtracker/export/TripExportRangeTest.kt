package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test

class TripExportRangeTest {
    // 2026-07-01T02:00:00Z is 2026-07-01 10:00 in Malaysia (UTC+8).
    private val start = Instant.parse("2026-07-01T02:00:00Z")

    @Test
    fun closed_trip_uses_start_and_end_dates() {
        val end = Instant.parse("2026-07-10T02:00:00Z")
        val r = tripExportRange(start, end, today = LocalDate(2026, 12, 31))
        assertThat(r.start).isEqualTo(LocalDate(2026, 7, 1))
        assertThat(r.end).isEqualTo(LocalDate(2026, 7, 10))
    }

    @Test
    fun open_ended_trip_uses_today_as_end() {
        val r = tripExportRange(start, endAt = null, today = LocalDate(2026, 7, 5))
        assertThat(r.start).isEqualTo(LocalDate(2026, 7, 1))
        assertThat(r.end).isEqualTo(LocalDate(2026, 7, 5))
    }

    @Test
    fun single_day_trip_start_equals_end() {
        val end = Instant.parse("2026-07-01T14:00:00Z") // same MYT day as start
        val r = tripExportRange(start, end, today = LocalDate(2026, 12, 31))
        assertThat(r.start).isEqualTo(LocalDate(2026, 7, 1))
        assertThat(r.end).isEqualTo(LocalDate(2026, 7, 1))
    }

    @Test
    fun end_before_start_is_clamped_to_start() {
        val end = Instant.parse("2026-06-20T02:00:00Z")
        val r = tripExportRange(start, end, today = LocalDate(2026, 12, 31))
        assertThat(r.start).isEqualTo(LocalDate(2026, 7, 1))
        assertThat(r.end).isEqualTo(LocalDate(2026, 7, 1))
    }
}
