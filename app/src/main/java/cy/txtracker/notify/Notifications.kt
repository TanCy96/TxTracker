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
    val body = if (topCategories.isEmpty()) title else "$title.\nTop: $topSummary"
    return NotificationCompat.Builder(context, NotificationChannels.SUMMARY)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(tapIntent)
        .setAutoCancel(true)
        .build()
}

/** "180.50" for 18050 minor units. Two-decimal MYR-friendly. */
internal fun formatMinor(minor: Long): String {
    val whole = minor / 100
    val cents = (minor % 100).toString().padStart(2, '0')
    return "$whole.$cents"
}
