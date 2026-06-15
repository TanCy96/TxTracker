package cy.txtracker.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import cy.txtracker.data.Category
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.ReimbursementEntry
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
 *     Credit Card, E-Wallet, Debit/Transfer, Cash, SL Debit
 *
 * Each transaction row places its NET amount in exactly one category column (or `Unverified`
 * when categoryId is null); the other category columns are blank. This makes summing each
 * column in a spreadsheet give per-category totals directly.
 *
 * The four funding-bucket columns carry the GROSS amount of each transaction funded from that
 * bucket (positive), plus each reimbursement entry as a negative in its destination bucket.
 * The Debit/Transfer bucket additionally carries each SL Debit share as a negative. Unlinked
 * transactions contribute to no funding column.
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
        val deposits = filterDepositsByRange(
            if (currency == "MYR") repository.getSlDebitDepositsOnce() else emptyList(),
            range,
        )
        val reimbursementsByTxId = repository.getReimbursementEntriesByTransaction()
        val dir = exportDir()
        val file = File(dir, csvFileName(currency, range))
        file.outputStream().use {
            writeCsv(transactions, categories, fundingSourcesById, deposits, reimbursementsByTxId, it)
        }
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
        val allDeposits = filterDepositsByRange(repository.getSlDebitDepositsOnce(), range)

        val dir = exportDir()
        val zipFile = File(dir, zipFileName(range))
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            for (code in codes) {
                val rows = filterByRange(repository.getAllTransactionsOnceForCurrency(code), range)
                val deposits = if (code == "MYR") allDeposits else emptyList()
                if (rows.isEmpty() && deposits.isEmpty()) continue
                zip.putNextEntry(java.util.zip.ZipEntry("transactions-$code.csv"))
                writeCsv(rows, categories, fundingSourcesById, deposits, reimbursementsByTxId, zip)
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
 * Returns [deposits] filtered to [range]; a null range returns the list unchanged. Keeps
 * deposits whose `occurredAt` is in `[start, endExclusive)` (Malaysia-local days), so a
 * date-range export windows SL Debit deposits the same way [filterByRange] windows transactions.
 */
fun filterDepositsByRange(
    deposits: List<SlDebitDeposit>,
    range: ExportDateRange?,
): List<SlDebitDeposit> {
    if (range == null) return deposits
    val (start, endExclusive) = malaysiaDateRangeBounds(range)
    return deposits.filter { it.occurredAt >= start && it.occurredAt < endExclusive }
}

/**
 * Writes CSV bytes for [transactions] / [categories] to [output].
 * The stream is NOT closed here — callers own the lifecycle.
 */
fun writeCsv(
    transactions: List<Transaction>,
    categories: List<Category>,
    fundingSourcesById: Map<Long, FundingSource> = emptyMap(),
    deposits: List<SlDebitDeposit> = emptyList(),
    reimbursementsByTxId: Map<Long, List<ReimbursementEntry>> = emptyMap(),
    output: OutputStream,
) {
    val csv = buildCsv(transactions, categories, fundingSourcesById, deposits, reimbursementsByTxId)
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
 *     Both the SL Debit share and reimbursement entries are subtracted inline (e.g.
 *     `=100.00-40.00-10.00`).
 *   - The four funding-bucket columns (Credit Card, E-Wallet, Debit/Transfer, Cash) carry
 *     the GROSS amount of each transaction funded from that bucket (positive), plus each
 *     reimbursement entry as a negative in its destination bucket. The Debit/Transfer bucket
 *     additionally carries each SL Debit share as a negative. A cell with a single term is a
 *     bare literal; multiple terms form a "=" formula. Unlinked transactions contribute to no
 *     funding column.
 *   - The `SL Debit` column accumulates deposits (negative) and SL shares (positive) for the
 *     day, same cell formatting as the funding columns. The SL deposit pool is tracked here
 *     rather than in a funding bucket.
 */
fun buildCsv(
    transactions: List<Transaction>,
    categories: List<Category>,
    fundingSourcesById: Map<Long, FundingSource> = emptyMap(),
    deposits: List<SlDebitDeposit> = emptyList(),
    reimbursementsByTxId: Map<Long, List<ReimbursementEntry>> = emptyMap(),
): String {
    val orderedCategories = categories.sortedWith(
        compareBy<Category> { it.sortOrder }.thenBy { it.name },
    )
    val categoryById = orderedCategories.associateBy { it.id }

    val sb = StringBuilder()

    // Header: date, description, <categories>, Unverified, the four funding buckets, SL Debit.
    sb.append("date,description")
    for (c in orderedCategories) sb.append(',').append(csvEscape(c.name))
    sb.append(",Unverified")
    for (kind in CANONICAL_KIND_ORDER) sb.append(',').append(csvEscape(bucketLabel(kind)))
    sb.append(",SL Debit")
    sb.append('\n')

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

        // Description column. Duplicate descriptions on the same day collapse to one occurrence
        // (e.g. two "grab car" rows -> a single "grab car"), keeping first-seen order.
        val descriptions = txs.mapNotNull { it.description?.takeIf { d -> d.isNotBlank() } }.distinct()
        sb.append(',').append(csvEscape(descriptions.joinToString(", ")))

        // Per-category columns — net (gross minus the SL share minus each reimbursement entry).
        for (c in orderedCategories) {
            sb.append(',')
            val terms = txs.filter { it.categoryId == c.id }.map { categoryTerm(it, reimbursementsByTxId) }
            sb.append(buildCategoryCell(terms))
        }

        // Unverified column.
        sb.append(',')
        val unverifiedTerms = txs
            .filter { it.categoryId == null || categoryById[it.categoryId] == null }
            .map { categoryTerm(it, reimbursementsByTxId) }
        sb.append(buildCategoryCell(unverifiedTerms))

        // Funding-bucket columns: gross positives (by the tx's source kind) + reimbursement
        // negatives (by each entry's destinationKind), in canonical order. Positives first.
        // The Debit/Transfer bucket additionally carries each SL Debit share as a negative,
        // since the SL Debit pool is funded from the debit bank.
        for (kind in CANONICAL_KIND_ORDER) {
            sb.append(',')
            val positives = txs
                .filter { tx -> tx.fundingSourceId?.let { fundingSourcesById[it]?.kind } == kind }
                .map { "+${formatAmount(it.amountMinor)}" }
            val negatives = txs
                .flatMap { reimbursementsByTxId[it.id] ?: emptyList() }
                .filter { it.destinationKind == kind }
                .map { "-${formatAmount(it.amountMinor)}" }
            val slShareNegatives = if (kind == FundingSourceKind.DEBIT_BANK) {
                txs.mapNotNull { it.slShareMinor?.let { s -> "-${formatAmount(s)}" } }
            } else {
                emptyList()
            }
            sb.append(csvEscape(buildSignedCell(positives + negatives + slShareNegatives)))
        }

        // SL Debit column: deposits (negative) and SL shares (positive) for the day.
        sb.append(',')
        val depositTerms = (depositsByDate[date] ?: emptyList()).map { "-${formatAmount(it.amountMinor)}" }
        val shareTerms = txs.mapNotNull { it.slShareMinor?.let { s -> "+${formatAmount(s)}" } }
        sb.append(csvEscape(buildSignedCell(depositTerms + shareTerms)))

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
 * Per-transaction term for a category cell. Each reduction is subtracted inline so the cell
 * evaluates to the net: the gross amount, minus the SL Debit share (if any), minus each
 * reimbursement entry (if any). E.g. "100.00", "100.00-40.00", "100.00-10.00-12.00", or
 * "100.00-40.00-10.00" when both an SL share and reimbursements apply.
 */
private fun categoryTerm(
    tx: Transaction,
    reimbursementsByTxId: Map<Long, List<ReimbursementEntry>>,
): String {
    val sb = StringBuilder(formatAmount(tx.amountMinor))
    tx.slShareMinor?.let { sb.append('-').append(formatAmount(it)) }
    reimbursementsByTxId[tx.id]?.forEach { sb.append('-').append(formatAmount(it.amountMinor)) }
    return sb.toString()
}

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
 * Signed-term cell shared by the funding-bucket columns and the SL Debit column. Terms are
 * "+500.00" / "-40.00":
 *   empty      -> ""
 *   one term   -> the term without a leading '+'  ("500.00" or "-40.00")
 *   many terms -> "=" + concatenation with the leading '+' stripped  ("=500.00-40.00")
 */
private fun buildSignedCell(signedTerms: List<String>): String = when {
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
