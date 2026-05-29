package cy.txtracker.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import cy.txtracker.data.Category
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.SlDebitDeposit
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
        val deposits = if (currency == "MYR") repository.getSlDebitDepositsOnce() else emptyList()
        val dir = exportDir()
        val file = File(dir, "transactions-$currency-${System.currentTimeMillis()}.csv")
        file.outputStream().use { writeCsv(transactions, categories, fundingSourcesById, deposits, it) }
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
        val allDeposits = repository.getSlDebitDepositsOnce()

        val dir = exportDir()
        val zipFile = File(dir, "transactions-all-${System.currentTimeMillis()}.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            for (code in codes) {
                val rows = repository.getAllTransactionsOnceForCurrency(code)
                if (rows.isEmpty()) continue
                zip.putNextEntry(java.util.zip.ZipEntry("transactions-$code.csv"))
                val deposits = if (code == "MYR") allDeposits else emptyList()
                writeCsv(rows, categories, fundingSourcesById, deposits, zip)
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
    deposits: List<SlDebitDeposit> = emptyList(),
    output: OutputStream,
) {
    val csv = buildCsv(transactions, categories, fundingSourcesById, deposits)
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
    deposits: List<SlDebitDeposit> = emptyList(),
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
    sb.append(",Unverified,SL Debit\n")

    // Group by day (Asia/Kuala_Lumpur). The export iterates the union of transaction days and
    // deposit days so a deposit-only day still emits a row. Sorted so it reads chronologically.
    val txByDate: Map<LocalDate, List<Transaction>> = transactions
        .groupBy { it.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
    val depositsByDate: Map<LocalDate, List<SlDebitDeposit>> = deposits
        .groupBy { it.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
    val allDates = (txByDate.keys + depositsByDate.keys).toSortedSet()

    for (date in allDates) {
        val txs = (txByDate[date] ?: emptyList()).sortedBy { it.occurredAt }

        sb.append(formatDate(date))

        // Description column.
        val descriptions = txs.mapNotNull { it.description?.takeIf { d -> d.isNotBlank() } }
        sb.append(',').append(csvEscape(descriptions.joinToString(", ")))

        // Source column: distinct bucket labels for the day, in canonical kind order. A day with
        // any SL Debit-shared transaction synthesizes a DEBIT_BANK bucket (the Debit/Transfer
        // inflow that funds the share), even though there is no real funding source row for it.
        val dayKinds = txs
            .mapNotNull { tx -> tx.fundingSourceId?.let { fundingSourcesById[it]?.kind } }
            .toMutableList()
        if (txs.any { it.slShareMinor != null }) {
            dayKinds.add(FundingSourceKind.DEBIT_BANK)
        }
        val dayBuckets = dayKinds
            .distinct()
            .sortedBy { CANONICAL_KIND_ORDER.indexOf(it) }
        val sourceCell = dayBuckets.joinToString(" / ") { bucketLabel(it) }
        sb.append(',').append(csvEscape(sourceCell))

        // Per-category columns.
        for (c in orderedCategories) {
            sb.append(',')
            val terms = txs.filter { it.categoryId == c.id }.map { categoryTerm(it) }
            sb.append(buildCategoryCell(terms))
        }

        // Unverified column: null categoryId OR a categoryId pointing at a deleted category.
        sb.append(',')
        val unverifiedTerms = txs
            .filter { it.categoryId == null || categoryById[it.categoryId] == null }
            .map { categoryTerm(it) }
        sb.append(buildCategoryCell(unverifiedTerms))

        // SL Debit column: deposits (positive) and shares (negative) for the day.
        sb.append(',')
        val depositTerms = (depositsByDate[date] ?: emptyList()).map { "+${formatAmount(it.amountMinor)}" }
        val shareTerms = txs.mapNotNull { it.slShareMinor?.let { s -> "-${formatAmount(s)}" } }
        sb.append(buildSlDebitCell(depositTerms + shareTerms))

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

/** Per-transaction term for a category cell: "amount" or "amount-share" for shared rows. */
private fun categoryTerm(tx: Transaction): String =
    tx.slShareMinor?.let { "${formatAmount(tx.amountMinor)}-${formatAmount(it)}" }
        ?: formatAmount(tx.amountMinor)

/**
 * Category cell from a day's terms:
 *   empty                       -> ""
 *   one plain term ("50.00")    -> "50.00"
 *   anything with subtraction
 *     or more than one term     -> "=t1+t2+..."  (a spreadsheet formula)
 */
private fun buildCategoryCell(terms: List<String>): String = when {
    terms.isEmpty() -> ""
    terms.size == 1 && !terms[0].contains('-') -> terms[0]
    else -> terms.joinToString(prefix = "=", separator = "+")
}

/**
 * SL Debit cell from signed terms ("+500.00", "-40.00"):
 *   empty      -> ""
 *   one term   -> the term without a leading '+'  ("500.00" or "-40.00")
 *   many terms -> "=" + concatenation with the leading '+' stripped  ("=500.00-40.00")
 */
private fun buildSlDebitCell(signedTerms: List<String>): String = when {
    signedTerms.isEmpty() -> ""
    signedTerms.size == 1 -> signedTerms[0].removePrefix("+")
    else -> "=" + signedTerms.joinToString(separator = "").removePrefix("+")
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
