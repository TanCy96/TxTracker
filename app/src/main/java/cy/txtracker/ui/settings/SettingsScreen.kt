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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.BuildConfig
import cy.txtracker.data.TrackedCurrency
import cy.txtracker.export.ExportDateRange
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCategoriesClick: () -> Unit,
    onMerchantMappingsClick: () -> Unit,
    onDescriptionMappingsClick: () -> Unit,
    onNotificationPriorityClick: () -> Unit,
    onForeignCurrenciesClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onRewritesClick: () -> Unit,
    onNotificationPoolClick: () -> Unit,
    onTrackedAppsClick: () -> Unit,
    onFundingSourcesClick: () -> Unit,
    onSlDebitClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val exportStatus by viewModel.exportStatus.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    val backfillResult by viewModel.backfillResult.collectAsState()
    val lockEnabled by viewModel.lockEnabled.collectAsState()
    val poolPendingCount by viewModel.poolPendingCount.collectAsState()
    val cloudSyncEnabled by viewModel.cloudSyncEnabled.collectAsState()
    val cloudSyncPaused by viewModel.cloudSyncPaused.collectAsState()
    val cloudAccountEmail by viewModel.cloudAccountEmail.collectAsState()
    val cloudLastSyncAt by viewModel.cloudLastSyncAt.collectAsState()
    val cloudLastSyncError by viewModel.cloudLastSyncError.collectAsState()
    val cloudTransactionCutoff by viewModel.cloudTransactionCutoff.collectAsState()
    val cloudSyncStatus by viewModel.cloudSyncStatus.collectAsState()
    val cloudSyncInFlight by viewModel.cloudSyncInFlight.collectAsState()
    val cloudSyncBlockedReason by viewModel.cloudSyncBlockedReason.collectAsState()
    val cloudRestorePickerState by viewModel.cloudRestorePickerState.collectAsState()
    val trackedCurrencies by viewModel.trackedCurrencies.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCutoffDialog by remember { mutableStateOf(false) }
    var showExportChooser by remember { mutableStateOf(false) }

    val pickBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importBackup(uri)
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val task = com.google.android.gms.auth.api.signin.GoogleSignIn
            .getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            viewModel.completeSignIn(email = account.email)
        } catch (e: com.google.android.gms.common.api.ApiException) {
            viewModel.signInFailed("Sign-in failed (code ${e.statusCode})")
        }
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
            is SettingsViewModel.ExportStatus.ZipReady -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Learning")
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
            ListItem(
                headlineContent = { Text("Re-categorize using learnings") },
                supportingContent = {
                    Text(
                        "Run the categorizer over uncategorized rows and apply " +
                            "description suggestions.",
                    )
                },
                modifier = Modifier.fillMaxWidth().clickableRow(viewModel::runBackfill),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Notification rewrites") },
                supportingContent = {
                    Text("Per-app regex rules applied to notification text before parsing.")
                },
                modifier = Modifier.fillMaxWidth().clickableRow(onRewritesClick),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Notification pool ($poolPendingCount)") },
                supportingContent = {
                    Text("Review captured notifications that were not auto-tracked.")
                },
                modifier = Modifier.fillMaxWidth().clickableRow(onNotificationPoolClick),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Tracked apps") },
                supportingContent = {
                    Text("Manage which apps create transactions automatically.")
                },
                modifier = Modifier.fillMaxWidth().clickableRow(onTrackedAppsClick),
            )
            HorizontalDivider()
            // "Re-parse merchants from raw text" action is intentionally hidden from
            // the UI right now. The repo/VM plumbing (TransactionRepository
            // .reparseMerchantsFromRawText, SettingsViewModel.runReparseMerchants /
            // reparseResult / consumeReparseResult, the dialog below) stays in place
            // so a future Settings entry can re-expose it without re-wiring.
            ListItem(
                headlineContent = { Text("Notification priority") },
                supportingContent = {
                    Text("Pick which apps win when two notify about the same payment.")
                },
                modifier = Modifier.fillMaxWidth().clickableRow(onNotificationPriorityClick),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Foreign currencies") },
                supportingContent = {
                    Text("Manage tracked currencies and trip windows.")
                },
                modifier = Modifier.fillMaxWidth().clickableRow(onForeignCurrenciesClick),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Funding sources") },
                supportingContent = {
                    Text("Cards, wallets, and accounts that funded your transactions.")
                },
                modifier = Modifier.fillMaxWidth().clickableRow(onFundingSourcesClick),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("SL Debit") },
                supportingContent = { Text("Prepaid pool — deposits, balance, and default share %.") },
                modifier = Modifier.fillMaxWidth().clickableRow(onSlDebitClick),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Classify existing transactions") },
                supportingContent = {
                    Text("Auto-link unlinked rows to a funding source.")
                },
                modifier = Modifier.fillMaxWidth().clickableRow(viewModel::classifyExistingTransactions),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Push notifications") },
                supportingContent = {
                    Text("Pending reminders, foreign-currency alerts, spending summaries.")
                },
                modifier = Modifier.fillMaxWidth().clickableRow(onNotificationsClick),
            )

            SectionHeader("Backup & export")
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
                    onClick = { showExportChooser = true },
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

            SectionHeader("App")
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
            HorizontalDivider()
            cy.txtracker.ui.settings.cloud.CloudSyncSection(
                enabled = cloudSyncEnabled,
                paused = cloudSyncPaused,
                accountEmail = cloudAccountEmail,
                lastSyncAt = cloudLastSyncAt,
                lastSyncError = cloudLastSyncError,
                transactionCutoff = cloudTransactionCutoff,
                syncInFlight = cloudSyncInFlight,
                syncBlockedReason = cloudSyncBlockedReason,
                onSignInClick = { signInLauncher.launch(viewModel.signInIntent()) },
                onSignOutClick = { deleteCloud -> viewModel.cloudSignOut(deleteCloud) },
                onSyncNowClick = { viewModel.cloudSyncNow() },
                onPausedChange = { viewModel.setCloudPaused(it) },
                onCutoffClick = { showCutoffDialog = true },
                onRestoreClick = { viewModel.restoreFromCloud() },
                onResumeSyncClick = { viewModel.resumeBlockedSync() },
                pickerState = cloudRestorePickerState,
                onChooseBackupClick = { viewModel.openCloudRestorePicker() },
                onPickerDismiss = { viewModel.dismissCloudRestorePicker() },
                onPickerFileSelected = { id -> viewModel.restoreFromCloudById(id) },
            )

            SectionHeader("About")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Tally ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (showCutoffDialog) {
            cy.txtracker.ui.settings.cloud.CutoffPickerDialog(
                currentValue = cloudTransactionCutoff,
                onDismiss = { showCutoffDialog = false },
                onSave = { value ->
                    viewModel.setTransactionCutoff(value)
                    showCutoffDialog = false
                },
            )
        }

        if (showExportChooser) {
            ExportCsvChooserSheet(
                trackedCurrencies = trackedCurrencies,
                onExportCurrency = { currency, range ->
                    showExportChooser = false
                    viewModel.exportCsv(currency, range)
                },
                onExportAllZip = { range ->
                    showExportChooser = false
                    viewModel.exportAllZip(range)
                },
                onDismiss = { showExportChooser = false },
            )
        }

        when (val s = cloudSyncStatus) {
            is SettingsViewModel.CloudSyncStatus.RestorePrompt -> {
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
            is SettingsViewModel.CloudSyncStatus.Restoring -> {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Restoring…") },
                    text = { CircularProgressIndicator() },
                    confirmButton = {},
                )
            }
            is SettingsViewModel.CloudSyncStatus.RestoreSuccess -> {
                LaunchedEffect(s) {
                    val r = s.result
                    snackbar.showSnackbar(
                        "Restored: ${r.transactionsAdded} transactions, " +
                            "${r.categoriesCreated} categories, " +
                            "${r.merchantMappingsAdded + r.merchantMappingsUpdated} merchants.",
                    )
                    viewModel.consumeCloudStatus()
                }
            }
            is SettingsViewModel.CloudSyncStatus.Error -> {
                LaunchedEffect(s) {
                    snackbar.showSnackbar(s.message)
                    viewModel.consumeCloudStatus()
                }
            }
            else -> Unit
        }

        backfillResult?.let { result ->
            AlertDialog(
                onDismissRequest = { viewModel.consumeBackfillResult() },
                title = { Text("Backfill complete") },
                text = {
                    Text(
                        "Updated category on ${result.categoryRows} row(s) and " +
                            "description on ${result.descriptionRows} row(s).",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.consumeBackfillResult() }) {
                        Text("OK")
                    }
                },
            )
        }

        val classifyResult by viewModel.classifyResult.collectAsState()
        classifyResult?.let { r ->
            LaunchedEffect(r) {
                snackbar.showSnackbar("${r.count} rows linked")
                viewModel.consumeClassifyResult()
            }
        }

        val reparseResult by viewModel.reparseResult.collectAsState()
        reparseResult?.let { r ->
            AlertDialog(
                onDismissRequest = { viewModel.consumeReparseResult() },
                title = { Text("Re-parse complete") },
                text = {
                    Text(
                        "Scanned ${r.scanned} row(s).\n" +
                            "Updated: ${r.updated}\n" +
                            "Unchanged: ${r.unchanged}\n" +
                            "Parser miss: ${r.parserMiss}\n" +
                            "Skipped (dedupe collision): ${r.skippedCollision}",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.consumeReparseResult() }) { Text("OK") }
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

private fun Modifier.clickableRow(
    onClick: () -> Unit,
    enabled: Boolean = true,
): Modifier = this.clickable(enabled = enabled, onClick = onClick)

/**
 * Bottom-sheet chooser for the CSV export action. Shows:
 *  - An optional "Date range" filter (blank = all time) applied to whichever export is tapped
 *  - "Export MYR" (always)
 *  - "Export <code>" for each tracked currency
 *  - "Export all currencies (zip)" when more than one currency is available
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportCsvChooserSheet(
    trackedCurrencies: List<TrackedCurrency>,
    onExportCurrency: (String, ExportDateRange?) -> Unit,
    onExportAllZip: (ExportDateRange?) -> Unit,
    onDismiss: () -> Unit,
) {
    var range by remember { mutableStateOf<ExportDateRange?>(null) }
    var showRangePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = "Export to CSV",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // Optional date-range filter. Blank = all time (today's default behavior).
            ListItem(
                headlineContent = { Text("Date range") },
                supportingContent = {
                    Text(range?.let { "${it.start} → ${it.end}" } ?: "All time")
                },
                trailingContent = {
                    if (range != null) {
                        TextButton(onClick = { range = null }) { Text("Clear") }
                    }
                },
                modifier = Modifier.fillMaxWidth().clickable { showRangePicker = true },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ListItem(
                headlineContent = { Text("Export MYR") },
                modifier = Modifier.fillMaxWidth().clickable { onExportCurrency("MYR", range) },
            )
            for (tc in trackedCurrencies) {
                ListItem(
                    headlineContent = { Text("Export ${tc.code}") },
                    modifier = Modifier.fillMaxWidth().clickable { onExportCurrency(tc.code, range) },
                )
            }
            if (trackedCurrencies.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ListItem(
                    headlineContent = { Text("Export all currencies (zip)") },
                    supportingContent = { Text("One CSV per currency in a single zip file.") },
                    modifier = Modifier.fillMaxWidth().clickable { onExportAllZip(range) },
                )
            }
        }
    }

    if (showRangePicker) {
        ExportRangePickerDialog(
            initial = range,
            onConfirm = {
                range = it
                showRangePicker = false
            },
            onDismiss = { showRangePicker = false },
        )
    }
}

/**
 * Material3 date-range picker dialog. Material reports selections as UTC-midnight epoch millis,
 * so we read the tapped calendar date via [TimeZone.UTC]; the exporter re-interprets those dates
 * in Malaysia time. If only a start is picked, the end defaults to the start (single-day export).
 * Confirming with no selection clears the filter (null = all time).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportRangePickerDialog(
    initial: ExportDateRange?,
    onConfirm: (ExportDateRange?) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.toUtcMidnightMillis(),
        initialSelectedEndDateMillis = initial?.end?.toUtcMidnightMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val start = state.selectedStartDateMillis?.toUtcLocalDate()
                if (start == null) {
                    onConfirm(null)
                } else {
                    val end = state.selectedEndDateMillis?.toUtcLocalDate() ?: start
                    onConfirm(ExportDateRange(start, end))
                }
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DateRangePicker(state = state)
    }
}

/** Epoch millis from Material's UTC-based picker → the calendar date the user tapped. */
private fun Long.toUtcLocalDate(): LocalDate =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date

/** A calendar date → UTC-midnight epoch millis, to seed the picker's initial selection. */
private fun LocalDate.toUtcMidnightMillis(): Long =
    this.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
