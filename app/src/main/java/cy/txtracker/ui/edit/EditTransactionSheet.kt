package cy.txtracker.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.Category
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.ReimbursementEntry
import cy.txtracker.data.Transaction
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.domain.isValidShareMinor
import cy.txtracker.domain.slDebitDefaultShareMinor
import cy.txtracker.domain.isValidReimbursementTotal
import cy.txtracker.ui.format.formatAmount
import cy.txtracker.ui.common.FundingSourcePickerSheet
import cy.txtracker.ui.common.KIND_ORDER
import cy.txtracker.ui.common.fundingBucketLabel
import cy.txtracker.ui.currency.AddCurrencyDialog
import cy.txtracker.ui.currency.CurrencyPickerSheet
import cy.txtracker.ui.currency.TripCreationDialog
import cy.txtracker.ui.format.formatDayHeader
import cy.txtracker.ui.format.formatMyr
import cy.txtracker.ui.format.formatTimeOfDay
import cy.txtracker.ui.manual.parseAmountMinor
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionSheet(
    transactionId: Long,
    onDismiss: () -> Unit,
    onDeleted: (DeletedTransaction) -> Unit = {},
    viewModel: EditTransactionViewModel = hiltViewModel(),
) {
    LaunchedEffect(transactionId) { viewModel.load(transactionId) }
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )

    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
    ) {
        // Back button still dismisses the sheet (swipe + scrim are blocked above).
        BackHandler { onDismiss() }
        when (val s = state) {
            EditUiState.Loading -> LoadingContent()
            EditUiState.Missing -> MissingContent(onClose = onDismiss)
            is EditUiState.Editing -> EditingContent(
                state = s,
                transactionId = transactionId,
                viewModel = viewModel,
                onCategoryChange = { categoryId ->
                    viewModel.setCategory(transactionId, categoryId, learn = true)
                },
                onFundingSourceChange = { fundingSourceId ->
                    viewModel.setFundingSource(transactionId, fundingSourceId)
                },
                onShareChange = { shareMinor ->
                    viewModel.setShare(transactionId, shareMinor)
                },
                onAddReimbursement = { amt, kind, person ->
                    viewModel.addReimbursement(transactionId, amt, kind, person)
                },
                onUpdateReimbursement = { entry -> viewModel.updateReimbursement(entry) },
                onRemoveReimbursement = { entry -> viewModel.removeReimbursement(entry) },
                onDescriptionChange = { description ->
                    viewModel.setDescription(transactionId, description, learn = true)
                },
                onMerchantNoteChange = { note ->
                    viewModel.setMerchantNote(transactionId, note)
                },
                onMerchantChange = { merchantRaw ->
                    viewModel.setMerchant(transactionId, merchantRaw)
                },
                onConfirmVerification = {
                    viewModel.confirmVerification(transactionId, onDone = onDismiss)
                },
                onDelete = {
                    viewModel.delete(transactionId) { snapshot ->
                        if (snapshot != null) onDeleted(snapshot)
                        onDismiss()
                    }
                },
                onClose = onDismiss,
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MissingContent(onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Transaction not found.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onClose) { Text("Close") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditingContent(
    state: EditUiState.Editing,
    transactionId: Long,
    viewModel: EditTransactionViewModel,
    onCategoryChange: (Long?) -> Unit,
    onFundingSourceChange: (Long?) -> Unit,
    onShareChange: (Long?) -> Unit,
    onAddReimbursement: (Long, FundingSourceKind, String?) -> Unit,
    onUpdateReimbursement: (ReimbursementEntry) -> Unit,
    onRemoveReimbursement: (ReimbursementEntry) -> Unit,
    onDescriptionChange: (String?) -> Unit,
    onMerchantNoteChange: (String?) -> Unit,
    onMerchantChange: (String) -> Unit,
    onConfirmVerification: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val tx = state.transaction

    // Local description state so the user can edit freely without thrashing the DB on every
    // keystroke. We flush a single save when the sheet leaves composition (swipe-dismiss or
    // Done button), comparing against the persisted value to avoid no-op writes.
    var description by remember(tx.id) { mutableStateOf(tx.description.orEmpty()) }
    val initialDescription = tx.description.orEmpty()

    // Same pattern for the merchant note — local edits, single save on dispose.
    var merchantNote by remember(tx.merchantNormalized) {
        mutableStateOf(state.merchantNote.orEmpty())
    }
    val initialMerchantNote = state.merchantNote.orEmpty()

    // Merchant rename — same flush-on-dispose pattern. Saving is intentionally first
    // in the dispose block so the note save (keyed on merchantNormalized) sees the
    // updated row if the user changed both.
    var merchant by remember(tx.id) { mutableStateOf(tx.merchantRaw) }
    val initialMerchant = tx.merchantRaw

    DisposableEffect(tx.id) {
        onDispose {
            val cleanedMerchant = merchant.trim()
            if (cleanedMerchant.isNotEmpty() && cleanedMerchant != initialMerchant.trim()) {
                onMerchantChange(cleanedMerchant)
            }
            val cleanedDescription = description.trim()
            if (cleanedDescription != initialDescription.trim()) {
                onDescriptionChange(cleanedDescription.ifEmpty { null })
            }
            val cleanedNote = merchantNote.trim()
            if (cleanedNote != initialMerchantNote.trim()) {
                onMerchantNoteChange(cleanedNote.ifEmpty { null })
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            // imePadding on the outer column keeps the pinned footer above the keyboard;
            // the inner scroll column (weight 1f) shrinks to make room.
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (tx.needsVerification) {
                VerificationBanner()
                Spacer(Modifier.height(12.dp))
            }

            // Header: amount + occurred-at. Merchant is editable below so the user can fix
            // a wrong/placeholder merchant name (e.g., "CIMB (review)" → "TAOBAO").
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val date = tx.occurredAt.toLocalDateTime(MalaysiaTimeZone).date
                Text(
                    text = "${formatDayHeader(date)} • ${formatTimeOfDay(tx.occurredAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatMyr(tx.amountMinor),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(text = "Merchant", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                placeholder = { Text("Merchant name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            Text(text = "Category", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            CategoryPicker(
                categories = state.categories,
                selectedCategoryId = tx.categoryId,
                onCategoryChange = onCategoryChange,
            )

            Spacer(Modifier.height(16.dp))
            Text(text = "Description", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = { Text("e.g. lunch, petrol, coffee") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text(text = "Note about this merchant", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = merchantNote,
                onValueChange = { merchantNote = it },
                placeholder = { Text("e.g. SS15 warung uncle, friend's TnG, …") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(text = "Currency", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            var showPicker by remember { mutableStateOf(false) }
            var pendingCurrency by remember { mutableStateOf<String?>(null) }
            var showAddDialog by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            AssistChip(
                onClick = { showPicker = true },
                label = { Text(tx.currency) },
            )

            if (showPicker) {
                CurrencyPickerSheet(
                    tracked = state.trackedCurrencies,
                    onPick = { picked ->
                        showPicker = false
                        if (picked == tx.currency) return@CurrencyPickerSheet
                        if (picked == "MYR") {
                            viewModel.setCurrency(transactionId, picked)
                        } else {
                            scope.launch {
                                val active = viewModel.findActiveTrip(picked, tx.occurredAt)
                                if (active != null) {
                                    viewModel.setCurrency(transactionId, picked)
                                } else {
                                    pendingCurrency = picked
                                }
                            }
                        }
                    },
                    onAddNew = {
                        showPicker = false
                        showAddDialog = true
                    },
                    onDismiss = { showPicker = false },
                )
            }

            if (showAddDialog) {
                AddCurrencyDialog(
                    alreadyTracked = state.trackedCurrencies.map { it.code }.toSet() + "MYR",
                    onPick = { code ->
                        showAddDialog = false
                        viewModel.addCurrency(code)
                        pendingCurrency = code
                    },
                    onDismiss = { showAddDialog = false },
                )
            }

            val pending = pendingCurrency
            if (pending != null) {
                TripCreationDialog(
                    currency = pending,
                    defaultStartAt = tx.occurredAt,
                    defaultEndAt = tx.occurredAt.plus(14, DateTimeUnit.DAY, MalaysiaTimeZone),
                    onConfirm = { startAt, endAt ->
                        pendingCurrency = null
                        scope.launch {
                            viewModel.setCurrency(transactionId, pending)
                            viewModel.openTrip(pending, startAt, endAt) { }
                        }
                    },
                    onDismiss = { pendingCurrency = null },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(text = "Funding source", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            var showFundingSourcePicker by remember { mutableStateOf(false) }
            AssistChip(
                onClick = { showFundingSourcePicker = true },
                label = { Text(state.fundingSource?.displayName ?: "None") },
            )
            if (showFundingSourcePicker) {
                FundingSourcePickerSheet(
                    sources = state.availableFundingSources,
                    selected = state.fundingSource,
                    onDismiss = { showFundingSourcePicker = false },
                    onPick = { picked ->
                        onFundingSourceChange(picked?.id)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            if (tx.currency == "MYR" && state.slDebitUnlocked) {
                Text(text = "Share with SL Debit", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))

                val shareEnabled = tx.slShareMinor != null
                val defaultPercent = state.slDebitAccount?.defaultSharePercent ?: 40
                var shareText by remember(tx.id) {
                    mutableStateOf(tx.slShareMinor?.let { formatMyr(it).removePrefix("RM ") } ?: "")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (shareEnabled) "Sharing ${formatMyr(tx.slShareMinor!!)} of ${formatMyr(tx.amountMinor)}"
                        else "Off",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = shareEnabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                val def = slDebitDefaultShareMinor(tx.amountMinor, defaultPercent)
                                shareText = formatMyr(def).removePrefix("RM ")
                                onShareChange(def)
                            } else {
                                shareText = ""
                                onShareChange(null)
                            }
                        },
                    )
                }
                if (shareEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = shareText,
                        onValueChange = { shareText = it },
                        label = { Text("Share amount (RM)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LaunchedEffect(shareText) {
                        val parsed = parseAmountMinor(shareText)
                        if (parsed != null && isValidShareMinor(parsed, tx.amountMinor) && parsed != tx.slShareMinor) {
                            onShareChange(parsed)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
            }

            Text(text = "Reimbursed by others", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            run {
                val entries = state.reimbursements
                val totalReimbursed = entries.sumOf { it.amountMinor }
                if (entries.isEmpty()) {
                    Text(
                        text = "No reimbursements",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Others returned ${formatAmount(totalReimbursed, "").trim()} of " +
                            "${formatAmount(tx.amountMinor, "").trim()} ${tx.currency}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    entries.forEach { entry ->
                        ReimbursementRow(
                            entry = entry,
                            currency = tx.currency,
                            onRemove = { onRemoveReimbursement(entry) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                var newAmount by remember(tx.id, entries.size) { mutableStateOf("") }
                var newKind by remember(tx.id, entries.size) { mutableStateOf(FundingSourceKind.DEBIT_BANK) }
                var newPerson by remember(tx.id, entries.size) { mutableStateOf("") }

                OutlinedTextField(
                    value = newAmount,
                    onValueChange = { newAmount = it },
                    label = { Text("Reimbursed amount (${tx.currency})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                KindDropdown(selected = newKind, onSelect = { newKind = it })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPerson,
                    onValueChange = { newPerson = it },
                    label = { Text("Who (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                val parsedNew = parseAmountMinor(newAmount)
                val prospective = entries.map { it.amountMinor } + (parsedNew ?: 0L)
                val canAdd = parsedNew != null && isValidReimbursementTotal(prospective, tx.amountMinor)
                Button(
                    onClick = {
                        onAddReimbursement(parsedNew!!, newKind, newPerson.takeIf { it.isNotBlank() })
                        newAmount = ""; newPerson = ""
                    },
                    enabled = canAdd,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add reimbursement") }
            }

            // "Improve parsing for this app" — only meaningful when we have both a package
            // and a captured rawText to anchor a rule against. Manual entries are excluded.
            var showRewriteDialog by remember { mutableStateOf(false) }
            if (tx.sourceApp != cy.txtracker.data.MANUAL_SOURCE_APP && !tx.rawText.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { showRewriteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Improve parsing for this app") }
            }
            if (showRewriteDialog && !tx.rawText.isNullOrBlank()) {
                ImproveParsingDialog(
                    packageName = tx.sourceApp,
                    rawText = tx.rawText,
                    onDismiss = { showRewriteDialog = false },
                    onSave = { pattern, replacement ->
                        viewModel.upsertRewrite(
                            packageName = tx.sourceApp,
                            pattern = pattern,
                            replacement = replacement,
                            onDone = { showRewriteDialog = false },
                        )
                    },
                )
            }
        }

        // Pinned footer — never scrolls.
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        if (tx.needsVerification) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDelete) {
                    Text("Not a transaction", color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = onConfirmVerification) { Text("Confirm") }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onClose) { Text("Done") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Lets the user define a regex rewrite applied to every future notification from this
 * package before the parser runs. Shows the raw notification text and a live preview
 * of what the parser would actually see after the rule is applied — invaluable for
 * verifying the regex picks up the intended junk without eating too much.
 */
@Composable
private fun ImproveParsingDialog(
    packageName: String,
    rawText: String,
    onDismiss: () -> Unit,
    onSave: (pattern: String, replacement: String) -> Unit,
) {
    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }

    val preview = remember(pattern, replacement, rawText) {
        if (pattern.isBlank()) rawText
        else runCatching {
            Regex(pattern, RegexOption.IGNORE_CASE).replace(rawText, replacement)
        }.getOrElse { "<invalid regex: ${it.message}>" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Improve parsing") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Package: $packageName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Original notification text", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    rawText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Text("Regex to strip / replace", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    placeholder = { Text("e.g. Tap to see this transaction") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Replacement (leave blank to strip)", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    placeholder = { Text("") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Preview", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(pattern.trim(), replacement) },
                enabled = pattern.isNotBlank() && preview != rawText && !preview.startsWith("<invalid regex"),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun VerificationBanner() {
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    val fg = MaterialTheme.colorScheme.onTertiaryContainer
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Pending verification",
            style = MaterialTheme.typography.labelLarge,
            color = fg,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Captured from a notification that didn't match a known format. " +
                "Confirm if this was a real transaction, or mark it as not.",
            style = MaterialTheme.typography.bodySmall,
            color = fg,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    categories: List<Category>,
    selectedCategoryId: Long?,
    onCategoryChange: (Long?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "unverified") {
            FilterChip(
                selected = selectedCategoryId == null,
                onClick = { onCategoryChange(null) },
                label = { Text("Unverified") },
            )
        }
        items(categories, key = { it.id }) { c ->
            FilterChip(
                selected = selectedCategoryId == c.id,
                onClick = { onCategoryChange(c.id) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(Color(c.color), CircleShape))
                        Spacer(Modifier.size(6.dp))
                        Text(c.name)
                    }
                },
            )
        }
    }
}

@Composable
private fun ReimbursementRow(
    entry: ReimbursementEntry,
    currency: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                append("−").append(formatAmount(entry.amountMinor, "").trim()).append(" ")
                append(currency).append(" → ").append(fundingBucketLabel(entry.destinationKind))
                entry.personLabel?.let { append("  (").append(it).append(")") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = cy.txtracker.ui.theme.ReimbursedAccent,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindDropdown(
    selected: FundingSourceKind,
    onSelect: (FundingSourceKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = fundingBucketLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text("Landed in") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            KIND_ORDER.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(fundingBucketLabel(kind)) },
                    onClick = { onSelect(kind); expanded = false },
                )
            }
        }
    }
}
