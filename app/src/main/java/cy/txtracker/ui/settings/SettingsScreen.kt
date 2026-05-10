package cy.txtracker.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.BuildConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCategoriesClick: () -> Unit,
    onMerchantMappingsClick: () -> Unit,
    onDescriptionMappingsClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val exportStatus by viewModel.exportStatus.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    val lockEnabled by viewModel.lockEnabled.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val pickBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importBackup(uri)
    }

    LaunchedEffect(exportStatus) {
        when (val s = exportStatus) {
            is SettingsViewModel.ExportStatus.Ready -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, Uri.parse(s.uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Export transactions"))
                viewModel.consumeStatus()
            }
            is SettingsViewModel.ExportStatus.Error -> {
                snackbar.showSnackbar(s.message)
                viewModel.consumeStatus()
            }
            else -> Unit
        }
    }

    LaunchedEffect(backupStatus) {
        when (val s = backupStatus) {
            is SettingsViewModel.BackupStatus.ExportReady -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, Uri.parse(s.uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Save backup"))
                viewModel.consumeBackupStatus()
            }
            is SettingsViewModel.BackupStatus.Imported -> {
                val r = s.result
                snackbar.showSnackbar(
                    "Restored: " +
                        "${r.categoriesCreated} categories, " +
                        "${r.merchantMappingsAdded + r.merchantMappingsUpdated} merchants, " +
                        "${r.merchantDescriptionsAdded + r.merchantDescriptionsUpdated +
                            r.categoryDescriptionsAdded + r.categoryDescriptionsUpdated} descriptions.",
                )
                viewModel.consumeBackupStatus()
            }
            is SettingsViewModel.BackupStatus.Error -> {
                snackbar.showSnackbar(s.message)
                viewModel.consumeBackupStatus()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            ListItem(
                headlineContent = { Text("Categories") },
                supportingContent = { Text("Add, rename, or delete categories.") },
                modifier = Modifier.fillMaxWidth().clickableRow(onCategoriesClick),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Learned merchants") },
                supportingContent = { Text("Review or unlink merchant → category mappings.") },
                modifier = Modifier.fillMaxWidth().clickableRow(onMerchantMappingsClick),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Learned descriptions") },
                supportingContent = { Text("Review or unlink description suggestions.") },
                modifier = Modifier.fillMaxWidth().clickableRow(onDescriptionMappingsClick),
            )
            HorizontalDivider()

            val isExporting = exportStatus is SettingsViewModel.ExportStatus.Running
            ListItem(
                headlineContent = { Text("Export to CSV") },
                supportingContent = {
                    Text(
                        if (isExporting) "Building file…"
                        else "Wide format with one column per category, plus Unverified.",
                    )
                },
                trailingContent = {
                    if (isExporting) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                },
                modifier = Modifier.fillMaxWidth().clickableRow(
                    enabled = !isExporting,
                    onClick = viewModel::export,
                ),
            )
            HorizontalDivider()

            val isBackupBusy = backupStatus is SettingsViewModel.BackupStatus.Running
            ListItem(
                headlineContent = { Text("Backup categories and learning") },
                supportingContent = {
                    Text(
                        when (val s = backupStatus) {
                            is SettingsViewModel.BackupStatus.Running -> s.message
                            else -> "Save categories and learned mappings to a JSON file."
                        },
                    )
                },
                trailingContent = {
                    if (isBackupBusy) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                },
                modifier = Modifier.fillMaxWidth().clickableRow(
                    enabled = !isBackupBusy,
                    onClick = viewModel::exportBackup,
                ),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Restore from backup") },
                supportingContent = {
                    Text(
                        "Pick a previously saved backup. Newer local mappings are kept; " +
                            "older ones are refreshed from the file.",
                    )
                },
                modifier = Modifier.fillMaxWidth().clickableRow(
                    enabled = !isBackupBusy,
                    onClick = { pickBackup.launch(arrayOf("application/json", "*/*")) },
                ),
            )
            HorizontalDivider()

            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("About", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "TxTracker ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ListItem(
                headlineContent = { Text("Reset onboarding") },
                supportingContent = {
                    Text("The onboarding screen reappears immediately.")
                },
                modifier = Modifier.fillMaxWidth().clickableRow(
                    onClick = {
                        viewModel.resetOnboarding()
                        scope.launch { snackbar.showSnackbar("Onboarding reset.") }
                    },
                ),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Require unlock") },
                supportingContent = {
                    Text(
                        "Use fingerprint, face, or your device PIN to open the app. " +
                            "Re-locks after 30s in the background.",
                    )
                },
                trailingContent = {
                    Switch(
                        checked = lockEnabled,
                        onCheckedChange = viewModel::setLockEnabled,
                    )
                },
                modifier = Modifier.fillMaxWidth().clickableRow(
                    onClick = { viewModel.setLockEnabled(!lockEnabled) },
                ),
            )
        }
    }
}

private fun Modifier.clickableRow(
    onClick: () -> Unit,
    enabled: Boolean = true,
): Modifier = this.clickable(enabled = enabled, onClick = onClick)
