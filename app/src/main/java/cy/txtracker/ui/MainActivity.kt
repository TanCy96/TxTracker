package cy.txtracker.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import cy.txtracker.notify.NotificationPermissionBridge
import cy.txtracker.ui.theme.TxTrackerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Extends [FragmentActivity] (rather than the bare androidx.activity.ComponentActivity)
 * because `androidx.biometric.BiometricPrompt` requires a FragmentActivity to host its
 * authentication dialog. FragmentActivity's lifecycle behavior is the same in practice;
 * Compose, Hilt, and edge-to-edge all work identically.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var notificationPermissionBridge: NotificationPermissionBridge

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionBridge.onResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            notificationPermissionBridge.requests.collect { launchPermissionRequest() }
        }

        setContent {
            TxTrackerTheme {
                AppRoute()
            }
        }
    }

    private fun launchPermissionRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPermissionBridge.onResult(true)
        }
    }

    enum class Deeplink(val tag: String) {
        PendingFilter("pending");

        companion object {
            fun fromTag(tag: String?): Deeplink? = entries.firstOrNull { it.tag == tag }
        }
    }

    companion object {
        const val EXTRA_DEEPLINK = "deeplink"
    }
}
