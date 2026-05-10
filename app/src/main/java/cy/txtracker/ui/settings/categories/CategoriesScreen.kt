package cy.txtracker.ui.settings.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Category?>(null) }
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
                        onRename = { renameTarget = category },
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
            onAdd = { name, color ->
                viewModel.add(name, color)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    renameTarget?.let { target ->
        RenameCategoryDialog(
            category = target,
            existingNames = categories.map { it.name }.toSet() - target.name,
            onRename = { newName ->
                viewModel.rename(target, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
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
    onRename: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    ListItem(
        leadingContent = {
            Box(modifier = Modifier.size(20.dp).background(Color(category.color), CircleShape))
        },
        headlineContent = { Text(category.name) },
        trailingContent = {
            Row {
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename ${category.name}")
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
    onAdd: (name: String, color: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(DefaultCategoryColors.first()) }
    val nameClean = name.trim()
    val nameValid = nameClean.isNotEmpty() && nameClean !in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = nameClean.isNotEmpty() && nameClean in existingNames,
                    supportingText = {
                        if (nameClean.isNotEmpty() && nameClean in existingNames) {
                            Text("A category with that name already exists.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(12.dp))
                Text("Color", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.size(8.dp))
                ColorPickerRow(
                    selected = selectedColor,
                    onSelect = { selectedColor = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(nameClean, selectedColor) },
                enabled = nameValid,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameCategoryDialog(
    category: Category,
    existingNames: Set<String>,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(category.name) }
    val nameClean = name.trim()
    val nameValid = nameClean.isNotEmpty() && nameClean !in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename category") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = nameClean.isNotEmpty() && nameClean in existingNames,
                supportingText = {
                    if (nameClean.isNotEmpty() && nameClean in existingNames) {
                        Text("A category with that name already exists.")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(nameClean) }, enabled = nameValid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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

@Composable
private fun ColorPickerRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
