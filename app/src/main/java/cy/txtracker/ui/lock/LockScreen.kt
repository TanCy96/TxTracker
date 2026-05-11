package cy.txtracker.ui.lock

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Full-screen lock UI rendered above all routes when [LockState.locked] is true.
 *
 * Behavior:
 *   - Auto-fires the biometric prompt on first composition.
 *   - If the user dismisses the prompt (ERROR_USER_CANCELED, ERROR_NEGATIVE_BUTTON, etc.),
 *     the screen stays visible with an "Unlock" button so they can retry.
 *   - On success, calls [onUnlocked].
 *   - Sets `WindowManager.LayoutParams.FLAG_SECURE` while visible so the recents thumbnail
 *     and screenshots are blocked. Cleared on dispose so the rest of the app remains
 *     normally screenshottable.
 *
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` means we use fingerprint/face when enrolled, and
 * fall back to the device's PIN/pattern/password when not — Android's secure ladder, no
 * app-specific PIN required.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }

    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Capture the latest `onUnlocked` reference without recreating the BiometricPrompt
    // every recomposition.
    val onUnlockedNow = rememberUpdatedState(onUnlocked)

    val executor = remember(activity) { ContextCompat.getMainExecutor(activity) }
    val prompt = remember(activity, executor) {
        BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlockedNow.value()
                }
                // Errors (user cancellation, lockout, negative button, …) leave the lock
                // screen visible. The user can retry via the on-screen button.
            },
        )
    }
    val promptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Tally")
            .setSubtitle("Use your fingerprint, face, or device PIN to continue.")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
    }

    LaunchedEffect(Unit) { prompt.authenticate(promptInfo) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    text = "Tally is locked",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Authenticate to access your transactions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
                Button(onClick = { prompt.authenticate(promptInfo) }) {
                    Text("Unlock")
                }
            }
        }
    }
}

/**
 * Walks the Context wrapper chain to find the hosting [FragmentActivity], which is what
 * `BiometricPrompt` requires. Compose's [LocalContext] gives an Activity-derived context;
 * the wrapper chain ends at our MainActivity which extends FragmentActivity.
 */
private fun Context.findFragmentActivity(): FragmentActivity {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    error("Compose context is not hosted in a FragmentActivity — biometric prompt requires one.")
}
