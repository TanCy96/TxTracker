package cy.txtracker.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import cy.txtracker.ui.theme.TxTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Extends [FragmentActivity] (rather than the bare androidx.activity.ComponentActivity)
 * because `androidx.biometric.BiometricPrompt` requires a FragmentActivity to host its
 * authentication dialog. FragmentActivity's lifecycle behavior is the same in practice;
 * Compose, Hilt, and edge-to-edge all work identically.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TxTrackerTheme {
                AppRoute()
            }
        }
    }
}
