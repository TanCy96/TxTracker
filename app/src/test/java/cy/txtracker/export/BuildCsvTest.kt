package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import cy.txtracker.data.Direction
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.ReimbursementEntry
import cy.txtracker.data.SlDebitDeposit
import cy.txtracker.data.Transaction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import org.junit.Test

class BuildCsvTest {

    private val food = Category(id = 1, name = "Food", color = 0, isCustom = false, sortOrder = 0)
    private val transport = Category(id = 2, name = "Transport", color = 0, isCustom = false, sortOrder = 1)
    private val categories = listOf(food, transport)

    // Funding-bucket columns then the SL Debit column close out every row.
    private val header =
        "date,description,Food,Transport,Unverified,Credit Card,E-Wallet,Debit/Transfer,Cash,SL Debit"

    private fun tx(
        id: Long = 0,
        amountMinor: Long,
        merchant: String,
        description: String? = null,
        categoryId: Long? = null,
        occurredAt: Instant = Instant.parse("2026-05-09T04:30:00Z"),  // 12:30 KL
        fundingSourceId: Long? = null,
        slShareMinor: Long? = null,
    ) = Transaction(
        id = id,
        amountMinor = amountMinor,
        currency = "MYR",
        merchantRaw = merchant,
        merchantNormalized = merchant.uppercase(),
        categoryId = categoryId,
        description = description,
        occurredAt = occurredAt,
        timeBucket = TimeBucket.MIDDAY,
        sourceApp = "manual",
        rawText = null,
        direction = Direction.OUT,
        createdAt = occurredAt,
        notificationDedupeKey = "k-$merchant-$amountMinor",
        fundingSourceId = fundingSourceId,
        slShareMinor = slShareMinor,
    )

    private fun entry(txId: Long, amountMinor: Long, kind: FundingSourceKind) =
        ReimbursementEntry(
            transactionId = txId,
            amountMinor = amountMinor,
            destinationKind = kind,
            createdAt = Instant.parse("2026-05-09T04:30:00Z"),
        )

    private fun deposit(amountMinor: Long, occurredAt: Instant = Instant.parse("2026-05-09T04:30:00Z")) =
        SlDebitDeposit(amountMinor = amountMinor, occurredAt = occurredAt, createdAt = occurredAt)

    // ─── Header & basic placement ───────────────────────────────────────────────────────

    @Test
    fun header_has_funding_buckets_and_sl_debit_no_source() {
        val csv = buildCsv(transactions = emptyList(), categories = categories)
        assertThat(csv.lines().first()).isEqualTo(header)
    }

    @Test
    fun custom_category_added_appears_before_unverified() {
        val custom = Category(id = 3, name = "Pets", color = 0, isCustom = true, sortOrder = 2)
        val csv = buildCsv(transactions = emptyList(), categories = categories + custom)
        assertThat(csv.lines().first()).isEqualTo(
            "date,description,Food,Transport,Pets,Unverified,Credit Card,E-Wallet,Debit/Transfer,Cash,SL Debit",
        )
    }

    @Test
    fun single_tx_lands_in_category_column_only_no_funding_when_unlinked() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1250, merchant = "PJ Cafe", description = "lunch", categoryId = food.id)),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,lunch,12.50,,,,,,,")
    }

    @Test
    fun uncategorized_amount_lands_in_unverified_column() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1500, merchant = "Mystery", categoryId = null)),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,,,15.00,,,,,")
    }

    @Test
    fun multiple_txs_same_day_same_category_collapse_into_a_formula() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1250, merchant = "Cafe A", description = "lunch", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T04:00:00Z")),
                tx(amountMinor = 400, merchant = "Cafe B", description = "coffee", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T08:00:00Z")),
            ),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,\"lunch, coffee\",=12.50+4.00,,,,,,,")
    }

    @Test
    fun description_with_comma_is_quoted_and_escaped() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1000, merchant = "A", description = "coffee, fast", categoryId = food.id)),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,\"coffee, fast\",10.00,,,,,,,")
    }

    @Test
    fun deleted_category_falls_through_to_unverified() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 500, merchant = "A", categoryId = 99L)),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,,,5.00,,,,,")
    }

    // ─── Funding-source columns (positives) ─────────────────────────────────────────────

    @Test
    fun gross_amount_lands_in_its_bucket_column() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1000, merchant = "STARBUCKS", categoryId = food.id, fundingSourceId = 10L),
            ),
            categories = categories,
            fundingSourcesById = mapOf(10L to fs(10L, FundingSourceKind.CREDIT_CARD)),
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,10.00,,,10.00,,,,")
    }

    @Test
    fun two_txs_same_bucket_same_day_form_a_funding_formula() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1000, merchant = "A", categoryId = food.id, fundingSourceId = 10L,
                    occurredAt = Instant.parse("2026-05-09T04:00:00Z")),
                tx(amountMinor = 500, merchant = "B", categoryId = food.id, fundingSourceId = 10L,
                    occurredAt = Instant.parse("2026-05-09T08:00:00Z")),
            ),
            categories = categories,
            fundingSourcesById = mapOf(10L to fs(10L, FundingSourceKind.CREDIT_CARD)),
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,=10.00+5.00,,,=10.00+5.00,,,,")
    }

    @Test
    fun unlinked_tx_contributes_to_no_funding_column() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 300, merchant = "U", categoryId = food.id, fundingSourceId = null)),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,3.00,,,,,,,")
    }

    // ─── Reimbursement: category net + funding negatives ────────────────────────────────

    @Test
    fun reimbursement_negatives_land_in_destination_buckets_and_category_nets() {
        val dinner = tx(id = 1L, amountMinor = 10000, merchant = "Dinner", categoryId = food.id, fundingSourceId = 10L)
        val csv = buildCsv(
            transactions = listOf(dinner),
            categories = categories,
            fundingSourcesById = mapOf(10L to fs(10L, FundingSourceKind.CREDIT_CARD)),
            reimbursementsByTxId = mapOf(
                1L to listOf(
                    entry(1L, 1000, FundingSourceKind.DEBIT_BANK),
                    entry(1L, 1200, FundingSourceKind.E_WALLET),
                ),
            ),
        )
        assertThat(csv.trimEnd().lines()[1])
            .isEqualTo("2026-05-09,,=100.00-10.00-12.00,,,100.00,-12.00,-10.00,,")
    }

    @Test
    fun single_reimbursement_to_a_bucket_renders_bare_negative_literal() {
        val t = tx(id = 5L, amountMinor = 10000, merchant = "A", categoryId = food.id, fundingSourceId = 10L)
        val csv = buildCsv(
            transactions = listOf(t),
            categories = categories,
            fundingSourcesById = mapOf(10L to fs(10L, FundingSourceKind.CREDIT_CARD)),
            reimbursementsByTxId = mapOf(5L to listOf(entry(5L, 5000, FundingSourceKind.DEBIT_BANK))),
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,=100.00-50.00,,,100.00,,-50.00,,")
    }

    @Test
    fun reimbursement_into_same_bucket_that_funded_nets_within_the_cell() {
        val t = tx(id = 7L, amountMinor = 10000, merchant = "A", categoryId = food.id, fundingSourceId = 20L)
        val csv = buildCsv(
            transactions = listOf(t),
            categories = categories,
            fundingSourcesById = mapOf(20L to fs(20L, FundingSourceKind.DEBIT_BANK)),
            reimbursementsByTxId = mapOf(7L to listOf(entry(7L, 4000, FundingSourceKind.DEBIT_BANK))),
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,=100.00-40.00,,,,,=100.00-40.00,,")
    }

    // ─── SL Debit column ────────────────────────────────────────────────────────────────

    @Test
    fun sl_share_subtracts_in_category_and_shows_negative_in_sl_debit_column() {
        val csv = buildCsv(
            transactions = listOf(tx(id = 1L, amountMinor = 10000, merchant = "A", categoryId = food.id, slShareMinor = 4000)),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,=100.00-40.00,,,,,,,-40.00")
    }

    @Test
    fun deposit_only_day_emits_row_with_positive_sl_debit() {
        val csv = buildCsv(
            transactions = emptyList(),
            categories = categories,
            deposits = listOf(deposit(amountMinor = 50000)),
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,,,,,,,,500.00")
    }

    @Test
    fun sl_share_and_reimbursement_both_net_in_category() {
        val t = tx(id = 3L, amountMinor = 10000, merchant = "A", categoryId = food.id,
            fundingSourceId = 10L, slShareMinor = 2000)
        val csv = buildCsv(
            transactions = listOf(t),
            categories = categories,
            fundingSourcesById = mapOf(10L to fs(10L, FundingSourceKind.CREDIT_CARD)),
            reimbursementsByTxId = mapOf(3L to listOf(entry(3L, 1000, FundingSourceKind.DEBIT_BANK))),
        )
        // Category nets gross-share-reimbursement; CC carries the gross; DT the reimbursement;
        // SL Debit the share.
        assertThat(csv.trimEnd().lines()[1])
            .isEqualTo("2026-05-09,,=100.00-20.00-10.00,,,100.00,,-10.00,,-20.00")
    }
}

private fun fs(id: Long, kind: FundingSourceKind) = FundingSource(
    id = id,
    kind = kind,
    displayName = "Source $id",
    last4 = null,
    sourceAppHint = null,
    isUserNamed = false,
    createdAt = Instant.parse("2026-05-28T00:00:00Z"),
    updatedAt = Instant.parse("2026-05-28T00:00:00Z"),
)
