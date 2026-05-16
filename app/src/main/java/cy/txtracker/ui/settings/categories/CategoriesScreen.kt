package cy.txtracker.ui.settings.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.Category
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val categories by viewModel.categories.collectAsState()
    val counts by viewModel.categoryCounts.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Category?>(null) }
    var deleteTarget by remember { mutableStateOf<Category?>(null) }

    // Local list for live drag preview. Re-keyed off the DB-derived `categories` so any
    // external change (add/rename/delete from elsewhere) replaces it cleanly.
    var localOrder by remember(categories) { mutableStateOf(categories) }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localOrder = localOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add category")
            }
        },
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(localOrder, key = { it.id }) { category ->
                ReorderableItem(reorderState, key = category.id) {
                    CategoryRow(
                        category = category,
                        counts = counts[category.id]
                            ?: CategoriesViewModel.CategoryCounts(learned = 0, auto = 0),
                        onEdit = { editTarget = category },
                        onDelete = { deleteTarget = category },
                        dragHandle = {
                            IconButton(
                                modifier = Modifier.draggableHandle(
                                    onDragStopped = {
                                        // Persist whatever ordering the user landed on.
                                        // The DB write triggers a categories flow emit which
                                        // re-keys `localOrder` back to the DB state.
                                        viewModel.reorder(localOrder)
                                    },
                                ),
                                onClick = {},
                            ) {
                                Icon(Icons.Filled.DragHandle, contentDescription = "Reorder")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        AddCategoryDialog(
            existingNames = categories.map { it.name }.toSet(),
            otherCategoryPatterns = categories.map { it.name to it.keywordPattern },
            onAdd = { name, color, pattern ->
                viewModel.add(name, color, pattern)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    editTarget?.let { target ->
        EditCategoryDialog(
            category = target,
            existingNames = categories.map { it.name }.toSet() - target.name,
            otherCategoryPatterns = categories.filter { it.id != target.id }
                .map { it.name to it.keywordPattern },
            onSave = { name, color, pattern ->
                viewModel.editCategory(target, name, color, pattern)
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }

    deleteTarget?.let { target ->
        DeleteCategoryDialog(
            category = target,
            onConfirm = {
                viewModel.delete(target)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    counts: CategoriesViewModel.CategoryCounts,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    ListItem(
        leadingContent = {
            Box(modifier = Modifier.size(20.dp).background(Color(category.color), CircleShape))
        },
        headlineContent = { Text(category.name) },
        supportingContent = {
            Text(
                "learned: ${counts.learned} · auto: ${counts.auto}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${category.name}")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete ${category.name}",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                dragHandle()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AddCategoryDialog(
    existingNames: Set<String>,
    otherCategoryPatterns: List<Pair<String, String?>>,
    onAdd: (name: String, color: Int, keywordPattern: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(DefaultCategoryColors.first()) }
    var chips by remember { mutableStateOf<List<String>>(emptyList()) }
    var showOverlapWarning by remember { mutableStateOf<OverlapInfo?>(null) }

    val nameClean = name.trim()
    val nameValid = nameClean.isNotEmpty() && nameClean !in existingNames
    val joinedPattern = chips.joinToString("|")
    val patternError: String? = patternCompileError(joinedPattern)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            CategoryFormFields(
                name = name,
                onNameChange = { name = it },
                nameDuplicate = nameClean.isNotEmpty() && nameClean in existingNames,
                color = selectedColor,
                onColorChange = { selectedColor = it },
                chips = chips,
                onChipsChange = { chips = it },
                patternError = patternError,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val patternToSave = joinedPattern.takeIf { it.isNotEmpty() }
                    val overlap = detectOverlap(chips, otherCategoryPatterns)
                    if (overlap != null) {
                        showOverlapWarning = overlap
                    } else {
                        onAdd(nameClean, selectedColor, patternToSave)
                    }
                },
                enabled = nameValid && patternError == null,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    showOverlapWarning?.let { info ->
        OverlapWarningDialog(
            info = info,
            onSaveAnyway = {
                showOverlapWarning = null
                onAdd(nameClean, selectedColor, joinedPattern.takeIf { it.isNotEmpty() })
            },
            onCancel = { showOverlapWarning = null },
        )
    }
}

@Composable
private fun EditCategoryDialog(
    category: Category,
    existingNames: Set<String>,
    otherCategoryPatterns: List<Pair<String, String?>>,
    onSave: (name: String, color: Int, keywordPattern: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    var color by remember(category.id) { mutableStateOf(category.color) }
    var chips by remember(category.id) {
        mutableStateOf(splitPatternToChips(category.keywordPattern))
    }
    var showOverlapWarning by remember { mutableStateOf<OverlapInfo?>(null) }

    val nameClean = name.trim()
    val nameValid = nameClean.isNotEmpty() && nameClean !in existingNames
    val joinedPattern = chips.joinToString("|")
    val patternError: String? = patternCompileError(joinedPattern)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit category") },
        text = {
            CategoryFormFields(
                name = name,
                onNameChange = { name = it },
                nameDuplicate = nameClean.isNotEmpty() && nameClean in existingNames,
                color = color,
                onColorChange = { color = it },
                chips = chips,
                onChipsChange = { chips = it },
                patternError = patternError,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val patternToSave = joinedPattern.takeIf { it.isNotEmpty() }
                    val overlap = detectOverlap(chips, otherCategoryPatterns)
                    if (overlap != null) {
                        showOverlapWarning = overlap
                    } else {
                        onSave(nameClean, color, patternToSave)
                    }
                },
                enabled = nameValid && patternError == null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    showOverlapWarning?.let { info ->
        OverlapWarningDialog(
            info = info,
            onSaveAnyway = {
                showOverlapWarning = null
                onSave(nameClean, color, joinedPattern.takeIf { it.isNotEmpty() })
            },
            onCancel = { showOverlapWarning = null },
        )
    }
}

@Composable
private fun CategoryFormFields(
    name: String,
    onNameChange: (String) -> Unit,
    nameDuplicate: Boolean,
    color: Int,
    onColorChange: (Int) -> Unit,
    chips: List<String>,
    onChipsChange: (List<String>) -> Unit,
    patternError: String?,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Name") },
            singleLine = true,
            isError = nameDuplicate,
            supportingText = {
                if (nameDuplicate) Text("A category with that name already exists.")
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(12.dp))
        Text("Color", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.size(8.dp))
        ColorPickerRow(selected = color, onSelect = onColorChange)
        Spacer(Modifier.size(16.dp))
        KeywordChipInput(
            chips = chips,
            onChipsChange = onChipsChange,
            patternError = patternError,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordChipInput(
    chips: List<String>,
    onChipsChange: (List<String>) -> Unit,
    patternError: String?,
) {
    var buffer by remember { mutableStateOf("") }

    fun commitBuffer() {
        val trimmed = buffer.trim()
        if (trimmed.isNotEmpty()) {
            onChipsChange(chips + trimmed)
            buffer = ""
        }
    }

    val chipsScrollState = rememberScrollState()
    Column {
        if (chips.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .verticalScroll(chipsScrollState)
                    .padding(bottom = 8.dp),
            ) {
                chips.forEachIndexed { index, chip ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(chip) },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    onChipsChange(chips.toMutableList().apply { removeAt(index) })
                                },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove $chip",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = buffer,
            onValueChange = { new ->
                // Comma is the commit gesture. Anything before a comma flushes as chip(s);
                // text after the last comma stays in the buffer for the user to keep typing.
                if (new.contains(',')) {
                    val parts = new.split(',')
                    val toAdd = parts.dropLast(1)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (toAdd.isNotEmpty()) onChipsChange(chips + toAdd)
                    buffer = parts.last()
                } else {
                    buffer = new
                }
            },
            label = { Text("Auto-match keywords") },
            placeholder = {
                if (chips.isEmpty()) Text("e.g. STARBUCKS, TEALIVE")
            },
            isError = patternError != null,
            singleLine = true,
            supportingText = {
                Text(
                    patternError
                        ?: "Type a keyword then press , or +. Case-insensitive, " +
                            "matches anywhere in the captured merchant.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailingIcon = {
                IconButton(onClick = { commitBuffer() }, enabled = buffer.isNotBlank()) {
                    Icon(Icons.Filled.Add, contentDescription = "Add keyword")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitBuffer() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OverlapWarningDialog(
    info: OverlapInfo,
    onSaveAnyway: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Keyword overlap") },
        text = {
            Text(
                "\"${info.token}\" also auto-matches \"${info.otherCategoryName}\". " +
                    "Captures will go to whichever category sorts first. Save anyway?",
            )
        },
        confirmButton = { TextButton(onClick = onSaveAnyway) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteCategoryDialog(
    category: Category,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${category.name}\"?") },
        text = {
            Text(
                "Linked transactions move to Unverified. Learned merchant and description " +
                    "mappings for this category are removed.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerRow(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        DefaultCategoryColors.forEach { color ->
            val isSelected = color == selected
            val borderColor = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(color), CircleShape)
                    .border(width = 2.dp, color = borderColor, shape = CircleShape)
                    .clickable { onSelect(color) },
            )
        }
    }
}

private data class OverlapInfo(val token: String, val otherCategoryName: String)

private fun patternCompileError(pattern: String): String? {
    val trimmed = pattern.trim()
    if (trimmed.isEmpty()) return null
    return runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }
        .fold(onSuccess = { null }, onFailure = { "Invalid regex: ${it.message?.take(80)}" })
}

/**
 * Splits a stored keywordPattern back into chip values for the editor. Existing patterns
 * (including the seeded `DefaultKeywordPatterns`) are all flat `|`-joined tokens, so a
 * simple split is lossless. Empty fragments and trim-only fragments are dropped.
 */
private fun splitPatternToChips(stored: String?): List<String> =
    stored?.split('|')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

private fun detectOverlap(
    chips: List<String>,
    otherCategoryPatterns: List<Pair<String, String?>>,
): OverlapInfo? {
    if (chips.isEmpty()) return null
    val newTokens = chips.map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }.toSet()
    if (newTokens.isEmpty()) return null
    for ((otherName, otherPattern) in otherCategoryPatterns) {
        if (otherPattern.isNullOrBlank()) continue
        val otherTokens = otherPattern.split("|").map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
        val shared = otherTokens.firstOrNull { it in newTokens }
        if (shared != null) return OverlapInfo(token = shared, otherCategoryName = otherName)
    }
    return null
}

// Material-style palette used for both seeded categories and the new-category color picker.
private val DefaultCategoryColors = listOf(
    0xFFEF5350.toInt(), // red
    0xFF66BB6A.toInt(), // green
    0xFF42A5F5.toInt(), // blue
    0xFFFF7043.toInt(), // deep orange
    0xFF8D6E63.toInt(), // brown
    0xFFAB47BC.toInt(), // purple
    0xFFFFCA28.toInt(), // amber
    0xFF26A69A.toInt(), // teal
    0xFFEC407A.toInt(), // pink
    0xFF78909C.toInt(), // blue grey
)
