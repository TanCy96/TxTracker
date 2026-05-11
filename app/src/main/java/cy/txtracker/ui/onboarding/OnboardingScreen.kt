package cy.txtracker.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

private enum class OnboardingStep { Listener, Cloud }

@Composable
fun OnboardingScreen(
    granted: Boolean,
    onGrantAccess: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var step by remember { mutableStateOf(OnboardingStep.Listener) }

    // If notification access is granted while we're still on the Listener step, advance.
    LaunchedEffect(granted) {
        if (granted && step == OnboardingStep.Listener) {
            step = OnboardingStep.Cloud
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Column(
                modifier = Modifier.fillMaxSize().widthIn(max = 480.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                when (step) {
                    OnboardingStep.Listener -> ListenerStep(
                        onGrantAccess = onGrantAccess,
                        onContinueWithout = { step = OnboardingStep.Cloud },
                    )
                    OnboardingStep.Cloud -> CloudRestoreStep(
                        viewModel = viewModel,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListenerStep(
    onGrantAccess: () -> Unit,
    onContinueWithout: () -> Unit,
) {
    Text(
        text = "Tally",
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
        body = "Tally needs to read notifications from Google Wallet, Touch 'n Go, " +
            "Grab, and your bank app to extract amounts and merchants. Tapping " +
            "“Grant access” opens Android’s system settings page where you " +
            "can enable it.",
    )
    Spacer(Modifier.height(16.dp))
    BulletParagraph(
        title = "Local first",
        body = "Captured data lives in this app’s local database by default. " +
            "Cloud sync is opt-in via Settings or the next step.",
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
        onClick = onContinueWithout,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue without notifications")
    }
}

@Composable
private fun CloudRestoreStep(
    viewModel: OnboardingViewModel,
    onDismiss: () -> Unit,
) {
    val status by viewModel.cloudSyncStatus.collectAsState()

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            viewModel.completeSignIn(email = account.email)
        } catch (e: ApiException) {
            viewModel.signInFailed("Sign-in failed (code ${e.statusCode})")
        }
    }

    Text(
        text = "Coming from another device?",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "If you’ve used Tally before, sign in to Google Drive to restore your " +
            "categories, learned mappings, merchant notes, and transactions.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))

    BulletParagraph(
        title = "Local stays the source of truth",
        body = "Cloud sync is a copy. Your data still lives on this device.",
    )
    Spacer(Modifier.height(16.dp))
    BulletParagraph(
        title = "App-private folder",
        body = "Backups go to your Google Drive’s app-private space — invisible in your " +
            "My Drive UI, accessible only to Tally.",
    )

    Spacer(Modifier.height(40.dp))
    Button(
        onClick = { signInLauncher.launch(viewModel.signInIntent()) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Sign in to Google Drive")
    }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Skip for now")
    }

    // Surface the post-sign-in flow as dialogs / progress.
    when (val s = status) {
        is OnboardingViewModel.CloudSyncStatus.CheckingForBackup -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Checking…") },
                text = { CircularProgressIndicator() },
                confirmButton = {},
            )
        }
        is OnboardingViewModel.CloudSyncStatus.RestorePrompt -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissRestorePrompt() },
                title = { Text("Restore from cloud?") },
                text = { Text("A backup was found in your Google Drive. Restore it now?") },
                confirmButton = {
                    TextButton(onClick = { viewModel.restoreFromCloud() }) { Text("Restore") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissRestorePrompt() }) { Text("Skip") }
                },
            )
        }
        is OnboardingViewModel.CloudSyncStatus.Restoring -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Restoring…") },
                text = { CircularProgressIndicator() },
                confirmButton = {},
            )
        }
        is OnboardingViewModel.CloudSyncStatus.RestoreSuccess -> {
            LaunchedEffect(s) {
                viewModel.consumeStatus()
                onDismiss()
            }
        }
        is OnboardingViewModel.CloudSyncStatus.Done -> {
            LaunchedEffect(s) {
                viewModel.consumeStatus()
                onDismiss()
            }
        }
        is OnboardingViewModel.CloudSyncStatus.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.consumeStatus() },
                title = { Text("Sign-in failed") },
                text = { Text(s.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.consumeStatus() }) { Text("OK") }
                },
            )
        }
        else -> Unit
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
