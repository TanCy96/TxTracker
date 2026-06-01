package cy.txtracker.notify

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.InsightsPeriod
import cy.txtracker.domain.YearMonth
import cy.txtracker.domain.resolveInsightsPeriod
import cy.txtracker.service.NotificationPrefs
import cy.txtracker.ui.insights.InsightsPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock

/**
 * Daily periodic worker that fires a notification when an overall or per-category budget crosses
 * 80% / 100% of the current calendar month's MYR spend. Spend comes from the same netted queries
 * the Insights budget view uses (`observeTotalBetween` / `observeCategoryTotalsBetween`, both
 * MYR + OUT). Fired thresholds are remembered in [NotificationPrefs] so each fires once per month;
 * the worker prunes them to the current month so they reset automatically.
 */
@HiltWorker
class BudgetAlertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: TransactionRepository,
    private val notificationPrefs: NotificationPrefs,
    private val insightsPrefs: InsightsPrefs,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!notificationPrefs.budgetAlertsEnabled.value) return Result.success()

        val overallBudget = insightsPrefs.overallBudgetMinor.value
        val categoryBudgets = insightsPrefs.categoryBudgetsMinor.value
        if (overallBudget == null && categoryBudgets.isEmpty()) return Result.success()

        val month = resolveInsightsPeriod(InsightsPeriod.THIS_MONTH)
        val ym = YearMonth.current()
        val monthSpend = repository.observeTotalBetween(month.startInclusive, month.endExclusive).first()
        val categorySpend = repository.observeCategoryTotalsBetween(month.startInclusive, month.endExclusive)
            .first()
            .mapNotNull { total -> total.categoryId?.let { it to total.totalMinor } }
            .toMap()
        val categoryNames = repository.getAllCategoriesOnce().associate { it.id to it.name }

        val alreadyFired = notificationPrefs.firedBudgetAlertKeys.value
        val alerts = budgetAlertsToFire(
            yearMonth = ym,
            monthSpendMinor = monthSpend,
            overallBudgetMinor = overallBudget,
            categorySpendMinor = categorySpend,
            categoryBudgetsMinor = categoryBudgets,
            categoryNames = categoryNames,
            alreadyFired = alreadyFired,
        )
        if (alerts.isEmpty()) return Result.success()

        val mgr = NotificationManagerCompat.from(applicationContext)
        if (mgr.areNotificationsEnabled()) {
            mgr.notify(NotificationIds.BUDGET, buildBudgetAlertNotification(applicationContext, alerts))
        }

        // Persist fired keys, pruned to the current month so they reset when the month rolls over.
        val monthPrefix = "${ym.year}-${ym.month}:"
        val updated = (alreadyFired + alerts.map { it.key }).filterTo(mutableSetOf()) { it.startsWith(monthPrefix) }
        notificationPrefs.setFiredBudgetAlertKeys(updated)
        return Result.success()
    }
}
