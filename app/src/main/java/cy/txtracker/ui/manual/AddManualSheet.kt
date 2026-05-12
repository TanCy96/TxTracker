package cy.txtracker.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.Category
import cy.txtracker.ui.currency.AddCurrencyDialog
import cy.txtracker.ui.currency.CurrencyPickerSheet
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddManualSheet(
    onDismiss: () -> Unit,
    viewModel: AddManualViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Content(
            state = state,
            onAmountChange = viewModel::setAmount,
            onMerchantChange = viewModel::setMerchant,
            onCategoryChange = viewModel::setCategoryId,
            onDescriptionChange = viewModel::setDescription,
            onDateChange = viewModel::setDate,
            onTimeChange = viewModel::setTime,
            onCurrencyChange = viewModel::setCurrency,
            onAddCurrency = viewModel::addCurrency,
            onSave = { viewModel.save(onSaved = onDismiss) },
            onCancel = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    state: AddManualUiState,
    onAmountChange: (String) -> Unit,
    onMerchantChange: (String) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onCurrencyChange: (String) -> Unit,
    onAddCurrency: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showCurrencyPicker by remember { mutableStateOf(false) }
    var showAddCurrencyDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Add transaction",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.amountText,
            onValueChange = onAmountChange,
            label = { Text("Amount (${state.currency})") },
            placeholder = { Text("0.00") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.merchantText,
            onValueChange = onMerchantChange,
            label = { Text("Merchant") },
            placeholder = { Text("e.g. Pasar Malam, Coffee Bean") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Text("Currency", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        AssistChip(
            onClick = { showCurrencyPicker = true },
            label = { Text(state.currency) },
        )
        Spacer(Modifier.height(8.dp))

        Text("Category", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        CategoryPicker(
            categories = state.categories,
            selectedCategoryId = state.categoryId,
            onCategoryChange = onCategoryChange,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.descriptionText,
            onValueChange = onDescriptionChange,
            label = { Text("Description") },
            placeholder = { Text("e.g. lunch, parking, snacks") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClickableField(
                value = "${state.date.year}-${state.date.monthNumber.pad()}-${state.date.dayOfMonth.pad()}",
                label = "Date",
                onClick = { showDatePicker = true },
                modifier = Modifier.weight(1f),
            )
            ClickableField(
                value = "${state.time.hour.pad()}:${state.time.minute.pad()}",
                label = "Time",
                onClick = { showTimePicker = true },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onSave, enabled = state.canSave) { Text("Save") }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showDatePicker) {
        DatePickerSheet(
            initial = state.date,
            onDismiss = { showDatePicker = false },
            onAccept = {
                onDateChange(it)
                showDatePicker = false
            },
        )
    }
    if (showTimePicker) {
        TimePickerSheet(
            initial = state.time,
            onDismiss = { showTimePicker = false },
            onAccept = {
                onTimeChange(it)
                showTimePicker = false
            },
        )
    }
    if (showCurrencyPicker) {
        CurrencyPickerSheet(
            tracked = state.trackedCurrencies,
            onPick = { picked ->
                showCurrencyPicker = false
                onCurrencyChange(picked)
            },
            onAddNew = {
                showCurrencyPicker = false
                showAddCurrencyDialog = true
            },
            onDismiss = { showCurrencyPicker = false },
        )
    }
    if (showAddCurrencyDialog) {
        AddCurrencyDialog(
            alreadyTracked = state.trackedCurrencies.map { it.code }.toSet() + "MYR",
            onPick = { code ->
                showAddCurrencyDialog = false
                onAddCurrency(code)
                onCurrencyChange(code)
            },
            onDismiss = { showAddCurrencyDialog = false },
        )
    }
}

/**
 * A read-only field that looks like an [OutlinedTextField] but acts like a button.
 * Necessary because `OutlinedTextField(readOnly = true)` still captures touch events
 * before a `.clickable` modifier on the field can fire — the trick is to disable the
 * field (so it doesn't intercept pointer input) and overlay an invisible clickable Box.
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onAccept: (LocalTime) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onAccept(LocalTime(pickerState.hour, pickerState.minute))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = pickerState) },
    )
}

private fun Int.pad(): String = toString().padStart(2, '0')
