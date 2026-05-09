package cy.txtracker.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(
    onGrantAccess: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().widthIn(max = 480.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "TxTracker",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Capture spending automatically by reading payment notifications.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            BulletParagraph(
                title = "Notification access",
                body = "TxTracker needs to read notifications from Google Wallet, Touch ‘n Go, " +
                    "Grab, and your bank app to extract amounts and merchants. Tapping " +
                    "“Grant access” opens Android’s system settings page where you " +
                    "can enable it.",
            )
            Spacer(Modifier.height(16.dp))
            BulletParagraph(
                title = "Stays on your phone",
                body = "Captured data lives in the app’s local database. Nothing is sent over " +
                    "the network.",
            )
            Spacer(Modifier.height(16.dp))
            BulletParagraph(
                title = "You’re always in control",
                body = "Edit any captured row, change categories, delete entries, or revoke " +
                    "notification access from system settings at any time.",
            )

            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onGrantAccess,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant access")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue without notifications")
            }
        }
    }
}

@Composable
private fun BulletParagraph(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
