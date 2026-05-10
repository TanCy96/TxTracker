package cy.txtracker.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import cy.txtracker.data.Category
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.MalaysiaTimeZone
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/**
 * Wide-format CSV export. Columns:
 *
 *     date, description, <each category in sortOrder>, Unverified
 *
 * Each transaction row places its amount in exactly one category column (or `Unverified`
 * when categoryId is null); the other category columns are blank. This makes summing each
 * column in a spreadsheet give per-category totals directly.
 */
@Singleton
class CsvExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TransactionRepository,
) {
    /** Builds the CSV, writes to cacheDir, and returns a content URI sharable via Intent. */
    suspend fun export(): Uri {
        val transactions = repository.getAllTransactionsOnce()
        val categories = repository.getAllCategoriesOnce()
        val csv = buildCsv(transactions, categories)
        val file = writeToCache(csv)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    private fun writeToCache(csv: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Fresh-stamped filename so the share sheet shows a recognizable export.
        val filename = "transactions-${System.currentTimeMillis()}.csv"
        val file = File(dir, filename)
        file.writeText(csv, Charsets.UTF_8)
        return file
    }
}

/**
 * Pure CSV builder. Separated from file IO so it's directly unit-testable.
 *
 * Categories are emitted in their declared `sortOrder`, then `name` for stable output.
 * If the user has added or renamed categories, the column header reflects the current set
 * at export time.
 *
 * **One row per day**, not per transaction. For each day:
 *   - The `description` column joins all non-blank descriptions with `", "`, in
 *     chronological order. Blanks are skipped.
 *   - Each category column is empty if the day had no tx in that category; the literal
 *     amount if exactly one; a spreadsheet formula `=A+B+C` if more than one. The formula
 *     evaluates to the sum but a single click on the cell reveals the individual values,
 *     which matches the user's mental model of "see the total but keep the details".
 */
fun buildCsv(transactions: List<Transaction>, categories: List<Category>): String {
    val orderedCategories = categories.sortedWith(
        compareBy<Category> { it.sortOrder }.thenBy { it.name },
    )
    val categoryById = orderedCategories.associateBy { it.id }

    val sb = StringBuilder()

    // Header.
    sb.append("date,description")
    for (c in orderedCategories) {
        sb.append(',').append(csvEscape(c.name))
    }
    sb.append(",Unverified\n")

    // Group by day (Asia/Kuala_Lumpur). Sorted ascending so the export reads chronologically.
    val byDate: Map<LocalDate, List<Transaction>> = transactions
        .groupBy { it.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
        .toSortedMap()

    for ((date, daysTransactions) in byDate) {
        val txs = daysTransactions.sortedBy { it.occurredAt }

        sb.append(formatDate(date))

        // Description column.
        val descriptions = txs.mapNotNull { it.description?.takeIf { d -> d.isNotBlank() } }
        sb.append(',').append(csvEscape(descriptions.joinToString(", ")))

        // Per-category columns.
        for (c in orderedCategories) {
            sb.append(',')
            val amounts = txs.filter { it.categoryId == c.id }.map { it.amountMinor }
            sb.append(buildAmountCell(amounts))
        }

        // Unverified column: null categoryId OR a categoryId pointing at a deleted category.
        sb.append(',')
        val unverifiedAmounts = txs
            .filter { it.categoryId == null || categoryById[it.categoryId] == null }
            .map { it.amountMinor }
        sb.append(buildAmountCell(unverifiedAmounts))

        sb.append('\n')
    }

    return sb.toString()
}

/**
 * Builds the contents of a single category column for a single day:
 *   - empty list  → blank
 *   - one amount  → "12.50"
 *   - many amounts → "=12.50+4.00+5.00"  (a spreadsheet formula)
 */
private fun buildAmountCell(amountsMinor: List<Long>): String = when {
    amountsMinor.isEmpty() -> ""
    amountsMinor.size == 1 -> formatAmount(amountsMinor[0])
    else -> amountsMinor.joinToString(prefix = "=", separator = "+") { formatAmount(it) }
}

/** ISO-8601 date. */
private fun formatDate(date: LocalDate): String {
    val month = date.monthNumber.toString().padStart(2, '0')
    val day = date.dayOfMonth.toString().padStart(2, '0')
    return "${date.year}-$month-$day"
}

/** "0.00" formatted from minor units. No currency symbol; the spec is single-currency MYR. */
private fun formatAmount(amountMinor: Long): String {
    val ringgit = amountMinor / 100
    val cents = (amountMinor % 100).toString().padStart(2, '0')
    return "$ringgit.$cents"
}

/** Standard CSV escape: wrap in quotes if the field contains comma, quote, or newline. */
private fun csvEscape(value: String): String {
    if (value.isEmpty()) return ""
    val needsQuoting = value.contains(',') || value.contains('"') || value.contains('\n') ||
        value.contains('\r')
    return if (needsQuoting) "\"${value.replace("\"", "\"\"")}\"" else value
}
