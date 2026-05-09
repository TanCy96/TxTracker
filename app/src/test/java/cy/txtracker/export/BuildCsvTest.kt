package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import cy.txtracker.data.Direction
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
    )

    @Test
    fun header_lists_categories_in_sortOrder_then_unverified() {
        val csv = buildCsv(transactions = emptyList(), categories = categories)
        assertThat(csv.lines().first()).isEqualTo("date,description,Food,Transport,Unverified")
    }

    @Test
    fun amount_lands_in_matching_category_column_only() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1250, merchant = "PJ Cafe", description = "lunch", categoryId = food.id)),
            categories = categories,
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,lunch,12.50,,")
    }

    @Test
    fun uncategorized_amount_lands_in_unverified_column() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1500, merchant = "Mystery", categoryId = null)),
            categories = categories,
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,,,,15.00")
    }

    @Test
    fun multiple_rows_each_in_correct_column() {
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
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows).hasSize(4)  // header + 3 rows
        assertThat(rows[1]).isEqualTo("2026-05-09,lunch,10.00,,")
        assertThat(rows[2]).isEqualTo("2026-05-10,ride,,25.00,")
        assertThat(rows[3]).isEqualTo("2026-05-11,,,,7.00")
    }

    @Test
    fun amount_format_pads_cents() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 5, merchant = "tiny", categoryId = food.id),  // 0.05
                tx(amountMinor = 100, merchant = "round", categoryId = food.id),  // 1.00
            ),
            categories = categories,
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).contains(",0.05,")
        assertThat(rows[2]).contains(",1.00,")
    }

    @Test
    fun description_with_comma_is_quoted_and_escaped() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1000, merchant = "A", description = "coffee, fast", categoryId = food.id)),
            categories = categories,
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,\"coffee, fast\",10.00,,")
    }

    @Test
    fun description_with_quote_is_doubled_and_quoted() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1000, merchant = "A", description = "she said \"hi\"", categoryId = food.id)),
            categories = categories,
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,\"she said \"\"hi\"\"\",10.00,,")
    }

    @Test
    fun deleted_category_falls_through_to_unverified() {
        // categoryId points at id=99 which doesn't exist in the categories list (e.g., the
        // category was deleted between ingestion and export). Should land in Unverified.
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 500, merchant = "A", categoryId = 99L)),
            categories = categories,
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,,,,5.00")
    }

    @Test
    fun custom_category_added_appears_in_header() {
        val custom = Category(id = 3, name = "Pets", color = 0, isCustom = true, sortOrder = 2)
        val csv = buildCsv(transactions = emptyList(), categories = categories + custom)
        assertThat(csv.lines().first()).isEqualTo("date,description,Food,Transport,Pets,Unverified")
    }
}
