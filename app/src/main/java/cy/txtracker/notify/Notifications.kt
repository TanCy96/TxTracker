package cy.txtracker.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cy.txtracker.R
import cy.txtracker.ui.MainActivity

private const val REQ_PENDING_TAP = 100
private const val REQ_PENDING_DISMISS = 101
private const val REQ_SUMMARY_TAP = 102
private const val REQ_FOREIGN_TAP = 103
private const val REQ_FOREIGN_DISMISS = 104
private const val REQ_BUDGET_TAP = 105

/**
 * Builds the aggregated pending-row notification. [count] is the number of
 * unverified rows older than the threshold. Title pluralizes; body is a static
 * call-to-action. Tap deep-links to Home with the Pending filter selected.
 */
fun buildPendingNotification(context: Context, count: Int): Notification {
    val tapIntent = PendingIntent.getActivity(
        context, REQ_PENDING_TAP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DEEPLINK, MainActivity.Deeplink.PendingFilter.tag)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val dismissIntent = PendingIntent.getBroadcast(
        context, REQ_PENDING_DISMISS,
        Intent(context, PendingDismissReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val title = if (count == 1) "1 transaction needs verification"
                else "$count transactions need verification"
    return NotificationCompat.Builder(context, NotificationChannels.PENDING)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText("Tap to review.")
        .setContentIntent(tapIntent)
        .setDeleteIntent(dismissIntent)
        .setAutoCancel(true)
        .build()
}

/**
 * Builds the summary notification. Uses [NotificationCompat.BigTextStyle] so
 * the body wraps with the top-categories breakdown. [topCategories] is a list
 * of (name, totalMinor) pairs already truncated to the top 2.
 */
fun buildSummaryNotification(
    context: Context,
    rangeLabel: String,
    txCount: Int,
    totalMinor: Long,
    topCategories: List<Pair<String, Long>>,
    budgetLine: String? = null,
): Notification {
    val tapIntent = PendingIntent.getActivity(
        context, REQ_SUMMARY_TAP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val topSummary = topCategories.joinToString(", ") { (name, minor) ->
        "$name RM ${formatMinor(minor)}"
    }
    val title = "$rangeLabel: RM ${formatMinor(totalMinor)} across $txCount " +
        (if (txCount == 1) "transaction" else "transactions")
    val baseBody = if (topCategories.isEmpty()) title else "$title.\nTop: $topSummary"
    val body = if (budgetLine != null) "$baseBody\n$budgetLine" else baseBody
    return NotificationCompat.Builder(context, NotificationChannels.SUMMARY)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(tapIntent)
        .setAutoCancel(true)
        .build()
}

/**
 * Builds the foreign-currency notification. Surfaces a single currency at a
 * time (the worker picks one if multiple have parked rows). Tap deep-links to
 * Home with the Currency-review filter active — the in-app banner is visible
 * there with a "Start" button that opens the trip-creation dialog.
 */
fun buildForeignNotification(context: Context, currency: String, count: Int): Notification {
    val tapIntent = PendingIntent.getActivity(
        context, REQ_FOREIGN_TAP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DEEPLINK, MainActivity.Deeplink.CurrencyReview.tag)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val dismissIntent = PendingIntent.getBroadcast(
        context, REQ_FOREIGN_DISMISS,
        Intent(context, ForeignDismissReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val title = if (count == 1) "$currency transaction detected outside a trip"
                else "$count $currency transactions detected outside a trip"
    return NotificationCompat.Builder(context, NotificationChannels.FOREIGN)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText("Tap to review and start a trip.")
        .setContentIntent(tapIntent)
        .setDeleteIntent(dismissIntent)
        .setAutoCancel(true)
        .build()
}

/**
 * Builds the budget-alert notification: one line per crossed budget (overall + per-category),
 * `BigTextStyle` so they all show. Tap deep-links to Insights → Budget.
 */
fun buildBudgetAlertNotification(context: Context, alerts: List<BudgetAlert>): Notification {
    val tapIntent = PendingIntent.getActivity(
        context, REQ_BUDGET_TAP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DEEPLINK, MainActivity.Deeplink.InsightsBudget.tag)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val lines = alerts.map(::budgetAlertLine)
    val title = if (alerts.size == 1) lines.first() else "${alerts.size} budget alerts"
    val body = lines.joinToString("\n")
    return NotificationCompat.Builder(context, NotificationChannels.BUDGET)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(tapIntent)
        .setAutoCancel(true)
        .build()
}

private fun budgetAlertLine(alert: BudgetAlert): String {
    val amounts = "RM ${formatMinor(alert.spentMinor)} / RM ${formatMinor(alert.budgetMinor)}"
    return if (alert.threshold >= 100) "${alert.label}: over budget ($amounts)"
    else "${alert.label}: ${alert.threshold}% used ($amounts)"
}

/** "180.50" for 18050 minor units. Two-decimal MYR-friendly. */
internal fun formatMinor(minor: Long): String {
    val whole = minor / 100
    val cents = (minor % 100).toString().padStart(2, '0')
    return "$whole.$cents"
}
