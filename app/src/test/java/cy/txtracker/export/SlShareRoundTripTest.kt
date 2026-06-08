package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import org.junit.Test

/**
 * Locks the per-transaction SL Debit share through the backup wire format. The pre-existing
 * `v9 round-trips slShareMinor, account and deposits` test only covered the account + deposits,
 * never a transaction carrying slShareMinor — this fills that gap.
 */
class SlShareRoundTripTest {

    private fun sharedTx(share: Long?) = BackupTransaction(
        amountMinor = 3900,
        currency = "MYR",
        merchantRaw = "Lunch",
        merchantNormalized = "LUNCH",
        categoryName = null,
        description = null,
        occurredAt = Instant.parse("2026-06-07T03:00:00Z"),
        timeBucket = TimeBucket.MIDDAY,
        sourceApp = "manual",
        rawText = null,
        direction = Direction.OUT,
        createdAt = Instant.parse("2026-06-07T03:00:00Z"),
        notificationDedupeKey = "dedupe-1",
        needsVerification = false,
        slShareMinor = share,
    )

    private fun backupWith(tx: BackupTransaction) = Backup(
        exportedAt = Instant.parse("2026-06-08T00:00:00Z"),
        categories = emptyList(),
        merchantMappings = emptyList(),
        merchantDescriptionMappings = emptyList(),
        categoryDescriptionMappings = emptyList(),
        transactions = listOf(tx),
    )

    @Test
    fun `slShareMinor is written to JSON and decoded by importer JSON`() {
        val text = BackupExporter.JSON.encodeToString(Backup.serializer(), backupWith(sharedTx(3900)))
        assertThat(text).contains("slShareMinor")
        val decoded = BackupImporter.JSON.decodeFromString(Backup.serializer(), text)
        assertThat(decoded.transactions.single().slShareMinor).isEqualTo(3900L)
    }

    @Test
    fun `slShareMinor survives decode with exporter JSON (cloud restore path)`() {
        val text = BackupExporter.JSON.encodeToString(Backup.serializer(), backupWith(sharedTx(3900)))
        val decoded = BackupExporter.JSON.decodeFromString(Backup.serializer(), text)
        assertThat(decoded.transactions.single().slShareMinor).isEqualTo(3900L)
    }
}
