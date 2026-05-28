package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import cy.txtracker.data.Direction
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.Transaction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import org.junit.Test

class BuildCsvTest {

    private val food = Category(id = 1, name = "Food", color = 0, isCustom = false, sortOrder = 0)
    private val transport = Category(id = 2, name = "Transport", color = 0, isCustom = false, sortOrder = 1)
    private val categories = listOf(food, transport)

    private fun tx(
        amountMinor: Long,
        merchant: String,
        description: String? = null,
        categoryId: Long? = null,
        occurredAt: Instant = Instant.parse("2026-05-09T04:30:00Z"),  // 12:30 KL
        fundingSourceId: Long? = null,
    ) = Transaction(
        id = 0,
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
    )

    @Test
    fun header_lists_source_then_categories_in_sortOrder_then_unverified() {
        val csv = buildCsv(transactions = emptyList(), categories = categories, fundingSourcesById = emptyMap())
        assertThat(csv.lines().first()).isEqualTo("date,description,Source,Food,Transport,Unverified")
    }

    @Test
    fun single_tx_lands_in_matching_category_column_only() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1250, merchant = "PJ Cafe", description = "lunch", categoryId = food.id)),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,lunch,,12.50,,")
    }

    @Test
    fun uncategorized_amount_lands_in_unverified_column() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1500, merchant = "Mystery", categoryId = null)),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,,,,,15.00")
    }

    @Test
    fun multiple_days_produce_multiple_rows() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1000, merchant = "A", description = "lunch", categoryId = food.id),
                tx(
                    amountMinor = 2500,
                    merchant = "GRAB",
                    description = "ride",
                    categoryId = transport.id,
                    occurredAt = Instant.parse("2026-05-10T04:30:00Z"),
                ),
                tx(
                    amountMinor = 700,
                    merchant = "Mystery",
                    occurredAt = Instant.parse("2026-05-11T04:30:00Z"),
                ),
            ),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows).hasSize(4)  // header + 3 days
        assertThat(rows[1]).isEqualTo("2026-05-09,lunch,,10.00,,")
        assertThat(rows[2]).isEqualTo("2026-05-10,ride,,,25.00,")
        assertThat(rows[3]).isEqualTo("2026-05-11,,,,,7.00")
    }

    // ─── Same-day grouping ──────────────────────────────────────────────────────────────

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
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows).hasSize(2)  // header + 1 day
        // Description joins; Food column is a formula in chronological order.
        assertThat(rows[1]).isEqualTo("2026-05-09,\"lunch, coffee\",,=12.50+4.00,,")
    }

    @Test
    fun multiple_txs_same_day_different_categories_each_get_their_own_cell() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1250, merchant = "Cafe", description = "lunch", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T04:00:00Z")),
                tx(amountMinor = 2500, merchant = "Grab", description = "ride", categoryId = transport.id,
                    occurredAt = Instant.parse("2026-05-09T08:00:00Z")),
                tx(amountMinor = 700, merchant = "?", description = "snack", categoryId = null,
                    occurredAt = Instant.parse("2026-05-09T10:00:00Z")),
            ),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,\"lunch, ride, snack\",,12.50,25.00,7.00")
    }

    @Test
    fun same_category_three_txs_same_day_chains_three_terms() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 100, merchant = "A", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T01:00:00Z")),
                tx(amountMinor = 200, merchant = "B", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T05:00:00Z")),
                tx(amountMinor = 300, merchant = "C", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T09:00:00Z")),
            ),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,,,=1.00+2.00+3.00,,")
    }

    @Test
    fun blank_descriptions_skipped_in_join_keep_present_ones() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 100, merchant = "A", description = null, categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T01:00:00Z")),
                tx(amountMinor = 200, merchant = "B", description = "lunch", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T05:00:00Z")),
                tx(amountMinor = 300, merchant = "C", description = "  ", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T09:00:00Z")),
            ),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,lunch,,=1.00+2.00+3.00,,")
    }

    @Test
    fun all_blank_descriptions_produce_empty_description_cell() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 100, merchant = "A", categoryId = food.id),
                tx(amountMinor = 200, merchant = "B", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T05:00:00Z")),
            ),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,,,=1.00+2.00,,")
    }

    @Test
    fun unverified_column_also_uses_formula_for_multiple_uncategorized_amounts() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 500, merchant = "A", categoryId = null,
                    occurredAt = Instant.parse("2026-05-09T01:00:00Z")),
                tx(amountMinor = 1500, merchant = "B", categoryId = null,
                    occurredAt = Instant.parse("2026-05-09T05:00:00Z")),
            ),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,,,,,=5.00+15.00")
    }

    // ─── Existing behaviors that still hold ─────────────────────────────────────────────

    @Test
    fun amount_format_pads_cents_inside_formula_and_alone() {
        // Two cents + one ringgit on the same day → "=0.05+1.00".
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 5, merchant = "tiny", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T01:00:00Z")),
                tx(amountMinor = 100, merchant = "round", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T05:00:00Z")),
            ),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,,,=0.05+1.00,,")
    }

    @Test
    fun description_with_comma_is_quoted_and_escaped() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1000, merchant = "A", description = "coffee, fast", categoryId = food.id)),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,\"coffee, fast\",,10.00,,")
    }

    @Test
    fun description_with_quote_is_doubled_and_quoted() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1000, merchant = "A", description = "she said \"hi\"", categoryId = food.id)),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,\"she said \"\"hi\"\"\",,10.00,,")
    }

    @Test
    fun deleted_category_falls_through_to_unverified() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 500, merchant = "A", categoryId = 99L)),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,,,,,5.00")
    }

    @Test
    fun custom_category_added_appears_in_header() {
        val custom = Category(id = 3, name = "Pets", color = 0, isCustom = true, sortOrder = 2)
        val csv = buildCsv(transactions = emptyList(), categories = categories + custom, fundingSourcesById = emptyMap())
        assertThat(csv.lines().first()).isEqualTo("date,description,Source,Food,Transport,Pets,Unverified")
    }

    // ─── Funding source bucket column ───────────────────────────────────────────────────

    @Test
    fun csv_includes_funding_source_bucket_column() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1000, merchant = "STARBUCKS", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T04:30:00Z"), fundingSourceId = 10L),
                tx(amountMinor = 500, merchant = "TNG_TOPUP", categoryId = null,
                    occurredAt = Instant.parse("2026-05-10T04:30:00Z"), fundingSourceId = 20L),
                tx(amountMinor = 300, merchant = "UNLINKED", categoryId = null,
                    occurredAt = Instant.parse("2026-05-11T04:30:00Z"), fundingSourceId = null),
            ),
            categories = categories,
            fundingSourcesById = mapOf(
                10L to fixtureFundingSource(id = 10L, kind = FundingSourceKind.CREDIT_CARD),
                20L to fixtureFundingSource(id = 20L, kind = FundingSourceKind.E_WALLET),
            ),
        )
        val lines = csv.lines()
        assertThat(lines[0]).contains(",Source,")          // header
        assertThat(lines[1]).contains(",Credit Card,")     // STARBUCKS row
        assertThat(lines[2]).contains(",E-Wallet,")        // TNG_TOPUP row
        // UNLINKED row: Source cell is empty (between two commas)
        assertThat(lines[3]).contains(",,,")
    }

    @Test
    fun source_cell_shows_single_bucket_when_all_same_day_txs_share_one_kind() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1000, merchant = "A", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T04:00:00Z"), fundingSourceId = 10L),
                tx(amountMinor = 500, merchant = "B", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T08:00:00Z"), fundingSourceId = 10L),
            ),
            categories = categories,
            fundingSourcesById = mapOf(
                10L to fixtureFundingSource(id = 10L, kind = FundingSourceKind.CREDIT_CARD),
            ),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,,Credit Card,=10.00+5.00,,")
    }

    @Test
    fun source_cell_shows_all_unique_buckets_when_same_day_txs_have_different_kinds() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1000, merchant = "A", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T04:00:00Z"), fundingSourceId = 10L),
                tx(amountMinor = 500, merchant = "B", categoryId = null,
                    occurredAt = Instant.parse("2026-05-09T08:00:00Z"), fundingSourceId = 20L),
            ),
            categories = categories,
            fundingSourcesById = mapOf(
                10L to fixtureFundingSource(id = 10L, kind = FundingSourceKind.CREDIT_CARD),
                20L to fixtureFundingSource(id = 20L, kind = FundingSourceKind.E_WALLET),
            ),
        )
        val rows = csv.trimEnd().lines()
        // Both buckets appear, joined with "/"
        assertThat(rows[1]).contains("Credit Card/E-Wallet")
    }
}

private fun fixtureFundingSource(
    id: Long,
    kind: FundingSourceKind,
    displayName: String = "Source $id",
) = FundingSource(
    id = id,
    kind = kind,
    displayName = displayName,
    last4 = null,
    sourceAppHint = null,
    isUserNamed = false,
    createdAt = kotlinx.datetime.Instant.parse("2026-05-28T00:00:00Z"),
    updatedAt = kotlinx.datetime.Instant.parse("2026-05-28T00:00:00Z"),
)
