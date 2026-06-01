package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test

class ReimbursedBackupTest {
    private val json = BackupExporter.JSON

    private fun tx(reimbursedMinor: Long?) = BackupTransaction(
        amountMinor = 10000,
        currency = "MYR",
        merchantRaw = "M",
        merchantNormalized = "M",
        categoryName = null,
        description = null,
        occurredAt = Instant.parse("2026-05-09T04:30:00Z"),
        timeBucket = TimeBucket.MIDDAY,
        sourceApp = "manual",
        rawText = null,
        direction = Direction.OUT,
        createdAt = Instant.parse("2026-05-09T04:30:00Z"),
        notificationDedupeKey = "k",
        needsVerification = false,
        reimbursedMinor = reimbursedMinor,
    )

    @Test
    fun reimbursedMinor_survives_round_trip() {
        val encoded = json.encodeToString(tx(4000))
        val decoded = json.decodeFromString<BackupTransaction>(encoded)
        assertThat(decoded.reimbursedMinor).isEqualTo(4000)
    }

    @Test
    fun legacy_json_without_field_defaults_to_null() {
        val legacy = """
            {"amountMinor":10000,"currency":"MYR","merchantRaw":"M","merchantNormalized":"M",
             "categoryName":null,"description":null,"occurredAt":"2026-05-09T04:30:00Z",
             "timeBucket":"MIDDAY","sourceApp":"manual","rawText":null,"direction":"OUT",
             "createdAt":"2026-05-09T04:30:00Z","notificationDedupeKey":"k","needsVerification":false}
        """.trimIndent()
        val decoded = json.decodeFromString<BackupTransaction>(legacy)
        assertThat(decoded.reimbursedMinor).isNull()
    }
}
