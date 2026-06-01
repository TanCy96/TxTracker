package cy.txtracker.notify

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cy.txtracker.data.Direction
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.InsightsPeriod
import cy.txtracker.domain.resolveInsightsPeriod
import cy.txtracker.service.NotificationPrefs
import cy.txtracker.service.SummaryCadence
import cy.txtracker.ui.insights.InsightsPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock

@HiltWorker
class SummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: TransactionRepository,
    private val prefs: NotificationPrefs,
    private val insightsPrefs: InsightsPrefs,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cadence = prefs.summaryCadence.value
        if (cadence == SummaryCadence.OFF) return Result.success()

        val range = rangeFor(cadence, Clock.System.now())
        val rows = repository.getAllTransactionsBetween(range.start, range.endExclusive)
            .filter { it.currency == "MYR" && it.direction == Direction.OUT }
        if (rows.isEmpty()) return Result.success()

        val mgr = NotificationManagerCompat.from(applicationContext)
        if (!mgr.areNotificationsEnabled()) return Result.success()

        val total = rows.sumOf { it.amountMinor - (it.reimbursedMinor ?: 0L) }
        val topCategories = topByCategory(rows, take = 2)
        val budgetLine = insightsPrefs.overallBudgetMinor.value?.let { budget ->
            val month = resolveInsightsPeriod(InsightsPeriod.THIS_MONTH)
            val monthSpend = repository.observeTotalBetween(month.startInclusive, month.endExclusive).first()
            val pct = (100.0 * monthSpend / budget).roundToInt()
            val remaining = budget - monthSpend
            if (remaining >= 0) {
                "Monthly budget: RM ${formatMinor(remaining)} left of RM ${formatMinor(budget)} ($pct%)"
            } else {
                "Monthly budget: RM ${formatMinor(-remaining)} over RM ${formatMinor(budget)} ($pct%)"
            }
        }

        mgr.notify(
            NotificationIds.SUMMARY,
            buildSummaryNotification(
                context = applicationContext,
                rangeLabel = range.label,
                txCount = rows.size,
                totalMinor = total,
                topCategories = topCategories,
                budgetLine = budgetLine,
            ),
        )
        return Result.success()
    }

    /**
     * Groups [rows] by categoryId, resolves category names via the repository,
     * returns the top [take] by descending total. Uncategorized rows roll
     * into a single "Uncategorized" bucket.
     */
    private suspend fun topByCategory(
        rows: List<Transaction>,
        take: Int,
    ): List<Pair<String, Long>> {
        val sumByCategory: Map<Long?, Long> =
            rows.groupBy { it.categoryId }.mapValues { (_, r) -> r.sumOf { it.amountMinor - (it.reimbursedMinor ?: 0L) } }
        return sumByCategory.entries
            .sortedByDescending { it.value }
            .take(take)
            .map { (catId, sum) ->
                val name = catId?.let { repository.getCategory(it)?.name } ?: "Uncategorized"
                name to sum
            }
    }
}
