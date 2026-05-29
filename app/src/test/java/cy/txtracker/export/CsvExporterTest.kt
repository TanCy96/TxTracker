package cy.txtracker.export

import cy.txtracker.data.Category
import cy.txtracker.data.Direction
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.SlDebitDeposit
import cy.txtracker.data.Transaction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExporterTest {
    private val cat = Category(id = 1, name = "Food", color = 0, isCustom = false, sortOrder = 0)

    private fun tx(
        amount: Long,
        share: Long? = null,
        categoryId: Long? = 1,
        fundingSourceId: Long? = null,
        at: String = "2026-01-05T02:00:00Z",
    ) = Transaction(
        amountMinor = amount, currency = "MYR", merchantRaw = "M", merchantNormalized = "M",
        categoryId = categoryId, description = null, occurredAt = Instant.parse(at),
        timeBucket = TimeBucket.MORNING, sourceApp = "manual", rawText = null,
        direction = Direction.OUT, createdAt = Instant.parse(at), notificationDedupeKey = "k$amount$at",
        slShareMinor = share, fundingSourceId = fundingSourceId,
    )

    @Test fun `header gains SL Debit column after Unverified`() {
        val csv = buildCsv(listOf(tx(amount = 5000)), listOf(cat))
        assertEquals("date,description,Source,Food,Unverified,SL Debit", csv.lineSequence().first())
    }

    @Test fun `shared tx category cell shows the subtraction`() {
        val csv = buildCsv(listOf(tx(amount = 10_000, share = 4000)), listOf(cat))
        assertTrue(csv, csv.contains("=100.00-40.00"))
    }

    @Test fun `SL Debit column combines deposits and shares`() {
        val deposits = listOf(
            SlDebitDeposit(id = 1, amountMinor = 50_000, occurredAt = Instant.parse("2026-01-05T02:00:00Z"), note = null, createdAt = Instant.parse("2026-01-05T02:00:00Z")),
        )
        val csv = buildCsv(listOf(tx(amount = 10_000, share = 4000)), listOf(cat), deposits = deposits)
        val dayLine = csv.lineSequence().first { it.startsWith("2026-01-05") }
        assertTrue(dayLine, dayLine.endsWith("=500.00-40.00"))
    }

    @Test fun `shared day adds Debit Transfer to the Source cell alongside the card`() {
        val card = FundingSource(
            id = 9, kind = FundingSourceKind.CREDIT_CARD, displayName = "Visa", last4 = "1234",
            sourceAppHint = "bank", createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        val csv = buildCsv(
            listOf(tx(amount = 10_000, share = 4000, fundingSourceId = 9)),
            listOf(cat),
            fundingSourcesById = mapOf(9L to card),
        )
        val dayLine = csv.lineSequence().first { it.startsWith("2026-01-05") }
        assertTrue(dayLine, dayLine.contains("Credit Card / Debit/Transfer"))
    }

    @Test fun `share-only day with no deposit emits bare negative literal in SL Debit`() {
        val csv = buildCsv(listOf(tx(amount = 10_000, share = 4000)), listOf(cat))
        val dayLine = csv.lineSequence().first { it.startsWith("2026-01-05") }
        assertTrue(dayLine, dayLine.endsWith(",-40.00"))
    }

    @Test fun `deposit-only day with no transactions still emits a row`() {
        val deposits = listOf(
            SlDebitDeposit(id = 1, amountMinor = 50_000, occurredAt = Instant.parse("2026-02-10T02:00:00Z"), note = null, createdAt = Instant.parse("2026-02-10T02:00:00Z")),
        )
        val csv = buildCsv(emptyList(), listOf(cat), deposits = deposits)
        assertTrue(csv, csv.lineSequence().any { it.startsWith("2026-02-10") && it.endsWith("500.00") })
    }
}
