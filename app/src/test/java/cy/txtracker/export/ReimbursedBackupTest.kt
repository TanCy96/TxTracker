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

    @Test
    fun reimbursement_entry_survives_round_trip() {
        val e = BackupReimbursementEntry(
            transactionDedupeKey = "k",
            amountMinor = 1000,
            destinationKind = "DEBIT_BANK",
            personLabel = "Person A",
            createdAt = Instant.parse("2026-05-09T04:30:00Z"),
        )
        val decoded = json.decodeFromString<BackupReimbursementEntry>(json.encodeToString(e))
        assertThat(decoded.amountMinor).isEqualTo(1000)
        assertThat(decoded.destinationKind).isEqualTo("DEBIT_BANK")
        assertThat(decoded.personLabel).isEqualTo("Person A")
    }

    @Test
    fun backup_defaults_reimbursement_entries_to_empty_for_v9() {
        val v9 = """{"version":9,"exportedAt":"2026-05-09T04:30:00Z","categories":[],
            "merchantMappings":[],"merchantDescriptionMappings":[],"categoryDescriptionMappings":[]}""".trimIndent()
        val decoded = json.decodeFromString<Backup>(v9)
        assertThat(decoded.reimbursementEntries).isEmpty()
    }
}
