package cy.txtracker.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import cy.txtracker.data.Category
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.ReimbursementEntry
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.MalaysiaTimeZone
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Wide-format CSV export. Columns:
 *
 *     date, description, <each category in sortOrder>, Unverified,
 *     Credit Card, E-Wallet, Debit/Transfer, Cash
 *
 * Each transaction row places its NET amount in exactly one category column (or `Unverified`
 * when categoryId is null); the other category columns are blank. This makes summing each
 * column in a spreadsheet give per-category totals directly.
 *
 * The four funding-bucket columns carry the GROSS amount of each transaction funded from that
 * bucket (positive), plus each reimbursement entry as a negative in its destination bucket.
 * Unlinked transactions contribute to no funding column.
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
    suspend fun exportCsv(currency: String, range: ExportDateRange? = null): Uri {
        val transactions = filterByRange(
            repository.getAllTransactionsOnceForCurrency(currency),
            range,
        )
        val categories = repository.getAllCategoriesOnce()
        val fundingSourcesById = repository.observeFundingSources().first().associateBy { it.id }
        val reimbursementsByTxId = repository.getReimbursementEntriesByTransaction()
        val dir = exportDir()
        val file = File(dir, csvFileName(currency, range))
        file.outputStream().use { writeCsv(transactions, categories, fundingSourcesById, reimbursementsByTxId, it) }
        return uriFor(file)
    }

    /**
     * Exports one CSV per currency (MYR + every tracked currency that has rows) into a
     * single zip file. Returns a content URI sharable via Intent.
     * Currencies with zero rows are skipped so the zip contains no empty CSVs.
     */
    suspend fun exportAllCurrenciesZip(range: ExportDateRange? = null): Uri {
        val categories = repository.getAllCategoriesOnce()
        val fundingSourcesById = repository.observeFundingSources().first().associateBy { it.id }
        val reimbursementsByTxId = repository.getReimbursementEntriesByTransaction()
        val trackedCodes = repository.observeTrackedCurrencies().first().map { it.code }
        val codes = (listOf("MYR") + trackedCodes).distinct()

        val dir = exportDir()
        val zipFile = File(dir, zipFileName(range))
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            for (code in codes) {
                val rows = filterByRange(repository.getAllTransactionsOnceForCurrency(code), range)
                if (rows.isEmpty()) continue
                zip.putNextEntry(java.util.zip.ZipEntry("transactions-$code.csv"))
                writeCsv(rows, categories, fundingSourcesById, reimbursementsByTxId, zip)
                zip.closeEntry()
            }
        }
        return uriFor(zipFile)
    }

    /** Legacy single-file export (all transactions, no currency filter). Kept for call-site
     *  compatibility during the transition. */
    suspend fun export(): Uri = exportCsv("MYR")

    private fun csvFileName(currency: String, range: ExportDateRange?): String {
        val suffix = if (range == null) "" else "-${range.start}_to_${range.end}"
        return "transactions-$currency$suffix-${System.currentTimeMillis()}.csv"
    }

    private fun zipFileName(range: ExportDateRange?): String {
        val suffix = if (range == null) "" else "-${range.start}_to_${range.end}"
        return "transactions-all$suffix-${System.currentTimeMillis()}.zip"
    }

    private fun exportDir(): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

// ---------------------------------------------------------------------------
// Pure helpers — separated from file I/O for unit-testability
// ---------------------------------------------------------------------------

/**
 * Optional CSV export date filter. [start] and [end] are inclusive and interpreted as
 * Malaysia-local calendar days, matching how [buildCsv] groups rows by day.
 */
data class ExportDateRange(val start: LocalDate, val end: LocalDate) {
    init { require(start <= end) { "start ($start) must be <= end ($end)" } }
}

/**
 * Converts an [ExportDateRange] to its instant bounds: `[start-of-start-day,
 * start-of-(end+1)-day)` in [MalaysiaTimeZone]. The upper bound is exclusive so the entire
 * [end] day is included. Pure — no I/O. MYT has no DST, so the bounds are unambiguous.
 */
fun malaysiaDateRangeBounds(range: ExportDateRange): Pair<Instant, Instant> {
    val start = range.start.atStartOfDayIn(MalaysiaTimeZone)
    val endExclusive = range.end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(MalaysiaTimeZone)
    return start to endExclusive
}

/**
 * Returns [transactions] filtered to [range]; a null range returns the list unchanged
 * (the all-time export path). Keeps rows whose `occurredAt` is in `[start, endExclusive)`.
 */
fun filterByRange(
    transactions: List<Transaction>,
    range: ExportDateRange?,
): List<Transaction> {
    if (range == null) return transactions
    val (start, endExclusive) = malaysiaDateRangeBounds(range)
    return transactions.filter { it.occurredAt >= start && it.occurredAt < endExclusive }
}

/**
 * Writes CSV bytes for [transactions] / [categories] to [output].
 * The stream is NOT closed here — callers own the lifecycle.
 */
fun writeCsv(
    transactions: List<Transaction>,
    categories: List<Category>,
    fundingSourcesById: Map<Long, FundingSource> = emptyMap(),
    reimbursementsByTxId: Map<Long, List<ReimbursementEntry>> = emptyMap(),
    output: OutputStream,
) {
    val csv = buildCsv(transactions, categories, fundingSourcesById, reimbursementsByTxId)
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
 *   - Each category column is empty if the day had no tx in that category; the literal
 *     amount if exactly one; a spreadsheet formula `=A+B+C` if more than one. The formula
 *     evaluates to the net spend but a single click on the cell reveals the individual
 *     values, which matches the user's mental model of "see the total but keep the details".
 *     Reimbursement entries are subtracted inline (e.g. `=100.00-10.00-12.00`).
 *   - The four funding-bucket columns (Credit Card, E-Wallet, Debit/Transfer, Cash) carry
 *     the GROSS amount of each transaction funded from that bucket (positive), plus each
 *     reimbursement entry as a negative in its destination bucket. A cell with a single term
 *     is a bare literal; multiple terms form a "=" formula. Unlinked transactions contribute
 *     to no funding column.
 */
fun buildCsv(
    transactions: List<Transaction>,
    categories: List<Category>,
    fundingSourcesById: Map<Long, FundingSource> = emptyMap(),
    reimbursementsByTxId: Map<Long, List<ReimbursementEntry>> = emptyMap(),
): String {
    val orderedCategories = categories.sortedWith(
        compareBy<Category> { it.sortOrder }.thenBy { it.name },
    )
    val categoryById = orderedCategories.associateBy { it.id }

    val sb = StringBuilder()

    // Header: date, description, <categories>, Unverified, then the four funding buckets.
    sb.append("date,description")
    for (c in orderedCategories) sb.append(',').append(csvEscape(c.name))
    sb.append(",Unverified")
    for (kind in CANONICAL_KIND_ORDER) sb.append(',').append(csvEscape(bucketLabel(kind)))
    sb.append('\n')

    val byDate: Map<LocalDate, List<Transaction>> = transactions
        .groupBy { it.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
        .toSortedMap()

    for ((date, daysTransactions) in byDate) {
        val txs = daysTransactions.sortedBy { it.occurredAt }

        sb.append(formatDate(date))

        // Description column.
        val descriptions = txs.mapNotNull { it.description?.takeIf { d -> d.isNotBlank() } }
        sb.append(',').append(csvEscape(descriptions.joinToString(", ")))

        // Per-category columns — net (gross minus each reimbursement entry inline).
        for (c in orderedCategories) {
            sb.append(',')
            sb.append(buildAmountCell(txs.filter { it.categoryId == c.id }.map { categoryTerm(it, reimbursementsByTxId) }))
        }

        // Unverified column.
        sb.append(',')
        val unverified = txs.filter { it.categoryId == null || categoryById[it.categoryId] == null }
        sb.append(buildAmountCell(unverified.map { categoryTerm(it, reimbursementsByTxId) }))

        // Funding-bucket columns: gross positives (by the tx's source kind) + reimbursement
        // negatives (by each entry's destinationKind), in canonical order. Positives first.
        for (kind in CANONICAL_KIND_ORDER) {
            sb.append(',')
            val positives = txs
                .filter { tx -> tx.fundingSourceId?.let { fundingSourcesById[it]?.kind } == kind }
                .map { "+${formatAmount(it.amountMinor)}" }
            val negatives = txs
                .flatMap { reimbursementsByTxId[it.id] ?: emptyList() }
                .filter { it.destinationKind == kind }
                .map { "-${formatAmount(it.amountMinor)}" }
            sb.append(csvEscape(buildFundingCell(positives + negatives)))
        }

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
 * A category term is `(grossAmountMinor, reimbursementAmountsMinor)`. The cell subtracts
 * each reimbursement inline so it evaluates to net spend.
 */
private fun categoryTerm(
    tx: Transaction,
    reimbursementsByTxId: Map<Long, List<ReimbursementEntry>>,
): Pair<Long, List<Long>> =
    tx.amountMinor to (reimbursementsByTxId[tx.id]?.map { it.amountMinor } ?: emptyList())

/**
 * Builds one category column for a day:
 *   - empty                                  -> ""
 *   - one plain term (no reimbursement)      -> "12.50"
 *   - anything else                          -> "=t1+t2+..."  where a reimbursed term is
 *                                              "amount-r1-r2"  (e.g. "=100.00-10.00-12.00+30.00")
 */
private fun buildAmountCell(terms: List<Pair<Long, List<Long>>>): String = when {
    terms.isEmpty() -> ""
    terms.size == 1 && terms[0].second.isEmpty() -> formatAmount(terms[0].first)
    else -> terms.joinToString(prefix = "=", separator = "+") { (amount, reimbs) ->
        buildString {
            append(formatAmount(amount))
            reimbs.forEach { append('-').append(formatAmount(it)) }
        }
    }
}

/**
 * Builds one funding-bucket column from signed terms ("+100.00", "-10.00"):
 *   - empty      -> ""
 *   - one term   -> the term with any leading '+' stripped ("100.00" or "-10.00")
 *   - many terms -> "=" + concatenation with the leading '+' stripped ("=100.00-10.00")
 */
private fun buildFundingCell(signedTerms: List<String>): String = when {
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
