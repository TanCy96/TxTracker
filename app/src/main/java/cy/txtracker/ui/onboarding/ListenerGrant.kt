package cy.txtracker.ui.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cy.txtracker.service.TxNotificationListener

/**
 * True iff the user has granted notification-listener access to *our* listener component
 * (precise to the class, not just the package).
 */
fun Context.isListenerGranted(): Boolean {
    val component = ComponentName(this, TxNotificationListener::class.java)
    val flat = Settings.Secure.getString(contentResolver, ENABLED_LISTENERS) ?: return false
    return flat.split(":").any { entry ->
        val cn = ComponentName.unflattenFromString(entry) ?: return@any false
        cn.packageName == component.packageName && cn.className == component.className
    }
}

/**
 * Reactive grant state — recomputes on every ON_RESUME so the UI flips back to home
 * automatically when the user returns from the system Settings page after toggling access.
 */
@Composable
fun rememberListenerGrantState(): State<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(context.isListenerGranted()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = context.isListenerGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Deep-link to the system "Notification access" page. */
fun Context.openListenerSettings() {
    startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

private const val ENABLED_LISTENERS = "enabled_notification_listeners"
