package cy.txtracker.ui.settings.sldebit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.SlDebitDeposit
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.ui.format.formatDayHeader
import cy.txtracker.ui.format.formatMyr
import cy.txtracker.ui.manual.parseAmountMinor
import cy.txtracker.ui.theme.SlShareGreen
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlDebitScreen(
    onBack: () -> Unit,
    viewModel: SlDebitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val account = state.account

    var showRenameDialog by remember { mutableStateOf(false) }
    var showPercentDialog by remember { mutableStateOf(false) }
    var showAddDeposit by remember { mutableStateOf(false) }
    var editingDeposit by remember { mutableStateOf<SlDebitDeposit?>(null) }
    var deletingDeposit by remember { mutableStateOf<SlDebitDeposit?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.displayName ?: "SL Debit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item(key = "balance") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                ) {
                    Text(
                        text = "Balance",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formatMyr(state.balanceMinor),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.balanceMinor >= 0) SlShareGreen else MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
            }

            item(key = "rename") {
                ListItem(
                    headlineContent = { Text("Account name") },
                    supportingContent = { Text(account?.displayName ?: "SL Debit") },
                    modifier = Modifier.fillMaxWidth().clickable { showRenameDialog = true },
                )
                HorizontalDivider()
            }

            item(key = "percent") {
                ListItem(
                    headlineContent = { Text("Default share %") },
                    supportingContent = { Text("${account?.defaultSharePercent ?: 0}% prefilled when sharing a transaction.") },
                    modifier = Modifier.fillMaxWidth().clickable { showPercentDialog = true },
                )
                HorizontalDivider()
            }

            item(key = "add-deposit") {
                ListItem(
                    headlineContent = { Text("Add deposit") },
                    supportingContent = { Text("Record a top-up to the pool.") },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { showAddDeposit = true },
                )
                HorizontalDivider()
            }

            item(key = "deposits-header") {
                Text(
                    text = "DEPOSITS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                )
            }

            if (state.deposits.isEmpty()) {
                item(key = "deposits-empty") {
                    Text(
                        text = "No deposits yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(state.deposits, key = { it.id }) { deposit ->
                    DepositRow(
                        deposit = deposit,
                        onEdit = { editingDeposit = deposit },
                        onDelete = { deletingDeposit = deposit },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            current = account?.displayName ?: "SL Debit",
            onConfirm = { name ->
                viewModel.rename(name)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showPercentDialog) {
        PercentDialog(
            current = account?.defaultSharePercent ?: 0,
            onConfirm = { percent ->
                viewModel.setDefaultPercent(percent)
                showPercentDialog = false
            },
            onDismiss = { showPercentDialog = false },
        )
    }

    if (showAddDeposit) {
        DepositEditorDialog(
            title = "Add deposit",
            initial = null,
            onConfirm = { amountMinor, occurredAt, note ->
                viewModel.addDeposit(amountMinor, occurredAt, note)
                showAddDeposit = false
            },
            onDismiss = { showAddDeposit = false },
        )
    }

    editingDeposit?.let { deposit ->
        DepositEditorDialog(
            title = "Edit deposit",
            initial = deposit,
            onConfirm = { amountMinor, occurredAt, note ->
                viewModel.updateDeposit(
                    deposit.copy(amountMinor = amountMinor, occurredAt = occurredAt, note = note),
                )
                editingDeposit = null
            },
            onDismiss = { editingDeposit = null },
        )
    }

    deletingDeposit?.let { deposit ->
        AlertDialog(
            onDismissRequest = { deletingDeposit = null },
            title = { Text("Delete deposit?") },
            text = { Text("Remove the ${formatMyr(deposit.amountMinor)} deposit. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDeposit(deposit.id)
                    deletingDeposit = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingDeposit = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DepositRow(
    deposit: SlDebitDeposit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val date = deposit.occurredAt.toLocalDateTime(MalaysiaTimeZone).date
    val supporting = buildString {
        append(formatDayHeader(date))
        deposit.note?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    }
    ListItem(
        headlineContent = { Text(formatMyr(deposit.amountMinor)) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    )
}

@Composable
private fun RenameDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename account") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PercentDialog(
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default share %") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Percent (0–100)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onConfirm(it.coerceIn(0, 100)) } },
                enabled = parsed != null && parsed in 0..100,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepositEditorDialog(
    title: String,
    initial: SlDebitDeposit?,
    onConfirm: (amountMinor: Long, occurredAt: Instant, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialDate = remember(initial) {
        initial?.occurredAt?.toLocalDateTime(MalaysiaTimeZone)?.date
            ?: Clock.System.now().toLocalDateTime(MalaysiaTimeZone).date
    }
    var amountText by remember(initial) { mutableStateOf(initial?.let { formatAmountInput(it.amountMinor) } ?: "") }
    var noteText by remember(initial) { mutableStateOf(initial?.note ?: "") }
    var date by remember(initial) { mutableStateOf(initialDate) }
    var showDatePicker by remember { mutableStateOf(false) }

    val amountMinor = parseAmountMinor(amountText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (MYR)") },
                    placeholder = { Text("0.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                ClickableField(
                    value = "${date.year}-${date.monthNumber.pad()}-${date.dayOfMonth.pad()}",
                    label = "Date",
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val minor = amountMinor ?: return@TextButton
                    onConfirm(
                        minor,
                        date.atStartOfDayIn(MalaysiaTimeZone),
                        noteText.trim().ifBlank { null },
                    )
                },
                enabled = amountMinor != null && amountMinor > 0,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showDatePicker) {
        DepositDatePicker(
            initial = date,
            onDismiss = { showDatePicker = false },
            onAccept = {
                date = it
                showDatePicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepositDatePicker(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onAccept: (LocalDate) -> Unit,
) {
    val initialMillis = remember(initial) {
        initial.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val ms = pickerState.selectedDateMillis ?: return@TextButton
                val ldt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC)
                onAccept(ldt.date)
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}

/**
 * Read-only field that looks like an [OutlinedTextField] but behaves like a button — mirrors
 * the idiom used in AddManualSheet (disable the field so it doesn't intercept touch, overlay
 * an invisible clickable Box).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClickableField(
    value: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onClick),
        )
    }
}

/** Minor units → plain editable decimal string ("1250" → "12.50"). */
private fun formatAmountInput(amountMinor: Long): String {
    val whole = amountMinor / 100
    val cents = (amountMinor % 100).toString().padStart(2, '0')
    return "$whole.$cents"
}

private fun Int.pad(): String = toString().padStart(2, '0')
