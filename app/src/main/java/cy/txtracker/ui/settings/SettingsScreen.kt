package cy.txtracker.ui.settings

import android.content.Intent
import android.net.Uri
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
import cy.txtracker.ui.onboarding.OnboardingPrefs
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
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                    Text("The onboarding screen will reappear on the next app launch.")
                },
                modifier = Modifier.fillMaxWidth().clickableRow(
                    onClick = {
                        OnboardingPrefs.clearDismissed(context)
                        scope.launch { snackbar.showSnackbar("Onboarding will show on next launch.") }
                    },
                ),
            )
        }
    }
}

private fun Modifier.clickableRow(
    onClick: () -> Unit,
    enabled: Boolean = true,
): Modifier = this.clickable(enabled = enabled, onClick = onClick)
