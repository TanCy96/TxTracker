package cy.txtracker.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import cy.txtracker.data.Category
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.MalaysiaTimeZone
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/**
 * Wide-format CSV export. Columns:
 *
 *     date, description, Source, <each category in sortOrder>, Unverified
 *
 * Each transaction row places its amount in exactly one category column (or `Unverified`
 * when categoryId is null); the other category columns are blank. This makes summing each
 * column in a spreadsheet give per-category totals directly.
 *
 * The `Source` column shows the funding-source bucket label(s) for all transactions in that
 * day. When all transactions share the same bucket, the single label is shown (e.g.
 * `Credit Card`). When the day has transactions from multiple buckets, the distinct labels
 * are joined with `" / "` in canonical kind order. Unlinked transactions contribute nothing to
 * the Source cell.
 */
@Singleton
class CsvExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TransactionRepository,
) {
    /**
     * Exports transactions for [currency] as a CSV, writes to cacheDir, and returns a
     * content URI sharable via Intent.
     */
    suspend fun exportCsv(currency: String): Uri {
        val transactions = repository.getAllTransactionsOnceForCurrency(currency)
        val categories = repository.getAllCategoriesOnce()
        val fundingSourcesById = repository.observeFundingSources().first().associateBy { it.id }
        val dir = exportDir()
        val file = File(dir, "transactions-$currency-${System.currentTimeMillis()}.csv")
        file.outputStream().use { writeCsv(transactions, categories, fundingSourcesById, it) }
        return uriFor(file)
    }

    /**
     * Exports one CSV per currency (MYR + every tracked currency that has rows) into a
     * single zip file. Returns a content URI sharable via Intent.
     * Currencies with zero rows are skipped so the zip contains no empty CSVs.
     */
    suspend fun exportAllCurrenciesZip(): Uri {
        val categories = repository.getAllCategoriesOnce()
        val fundingSourcesById = repository.observeFundingSources().first().associateBy { it.id }
        val trackedCodes = repository.observeTrackedCurrencies().first().map { it.code }
        val codes = (listOf("MYR") + trackedCodes).distinct()

        val dir = exportDir()
        val zipFile = File(dir, "transactions-all-${System.currentTimeMillis()}.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            for (code in codes) {
                val rows = repository.getAllTransactionsOnceForCurrency(code)
                if (rows.isEmpty()) continue
                zip.putNextEntry(java.util.zip.ZipEntry("transactions-$code.csv"))
                writeCsv(rows, categories, fundingSourcesById, zip)
                zip.closeEntry()
            }
        }
        return uriFor(zipFile)
    }

    /** Legacy single-file export (all transactions, no currency filter). Kept for call-site
     *  compatibility during the transition. */
    suspend fun export(): Uri = exportCsv("MYR")

    private fun exportDir(): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

// ---------------------------------------------------------------------------
// Pure helpers — separated from file I/O for unit-testability
// ---------------------------------------------------------------------------

/**
 * Writes CSV bytes for [transactions] / [categories] to [output].
 * The stream is NOT closed here — callers own the lifecycle.
 */
fun writeCsv(
    transactions: List<Transaction>,
    categories: List<Category>,
    fundingSourcesById: Map<Long, FundingSource> = emptyMap(),
    output: OutputStream,
) {
    val csv = buildCsv(transactions, categories, fundingSourcesById)
    output.write(csv.toByteArray(Charsets.UTF_8))
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
 *   - The `Source` column shows the distinct funding-source bucket label(s) for all
 *     transactions in that day, joined with `" / "` in canonical kind order. Unlinked
 *     transactions (null fundingSourceId or unresolved id) are excluded from the cell.
 *   - Each category column is empty if the day had no tx in that category; the literal
 *     amount if exactly one; a spreadsheet formula `=A+B+C` if more than one. The formula
 *     evaluates to the sum but a single click on the cell reveals the individual values,
 *     which matches the user's mental model of "see the total but keep the details".
 */
fun buildCsv(
    transactions: List<Transaction>,
    categories: List<Category>,
    fundingSourcesById: Map<Long, FundingSource> = emptyMap(),
): String {
    val orderedCategories = categories.sortedWith(
        compareBy<Category> { it.sortOrder }.thenBy { it.name },
    )
    val categoryById = orderedCategories.associateBy { it.id }

    val sb = StringBuilder()

    // Header.
    sb.append("date,description,Source")
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

        // Source column: distinct bucket labels for the day, in canonical kind order.
        val dayBuckets = txs
            .mapNotNull { tx -> tx.fundingSourceId?.let { fundingSourcesById[it]?.kind } }
            .distinct()
            .sortedBy { CANONICAL_KIND_ORDER.indexOf(it) }
        val sourceCell = dayBuckets.joinToString(" / ") { bucketLabel(it) }
        sb.append(',').append(csvEscape(sourceCell))

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
 * Returns the human-readable bucket label for a [FundingSourceKind].
 * Duplicated from `cy.txtracker.ui.common.fundingBucketLabel` to keep the export package
 * free of UI dependencies. The label values are part of the CSV spec contract.
 */
private fun bucketLabel(kind: FundingSourceKind): String = when (kind) {
    FundingSourceKind.CREDIT_CARD -> "Credit Card"
    FundingSourceKind.E_WALLET -> "E-Wallet"
    FundingSourceKind.DEBIT_BANK -> "Debit/Transfer"
    FundingSourceKind.CASH -> "Cash"
}

/** Canonical order for bucket labels within the Source cell of a multi-bucket day. */
private val CANONICAL_KIND_ORDER = listOf(
    FundingSourceKind.CREDIT_CARD,
    FundingSourceKind.E_WALLET,
    FundingSourceKind.DEBIT_BANK,
    FundingSourceKind.CASH,
)

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
