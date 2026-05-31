package cy.txtracker.ui.insights.charts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cy.txtracker.ui.format.formatMyr
import cy.txtracker.ui.insights.BudgetProgress
import cy.txtracker.ui.insights.CategoryBudgetProgress

/**
 * Spend-vs-budget view for the current calendar month: an overall budget (or a "Set budget"
 * prompt) followed by per-category budget rows and an "Add category budget" affordance. Editing
 * is delegated to the host via the callbacks (dialogs live in InsightsRoute).
 */
@Composable
fun BudgetProgressCard(
    overall: BudgetProgress?,
    categoryBudgets: List<CategoryBudgetProgress>,
    onEditOverall: () -> Unit,
    onEditCategory: (Long) -> Unit,
    onAddCategoryBudget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Overall monthly budget", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "This month",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (overall == null) {
            OutlinedButton(onClick = onEditOverall) { Text("Set budget") }
        } else {
            BudgetRow(
                label = "Budget",
                progress = overall,
                modifier = Modifier.clickable(onClick = onEditOverall),
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("By category", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (categoryBudgets.isEmpty()) {
            Text(
                text = "No category budgets yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            categoryBudgets.forEach { cbp ->
                BudgetRow(
                    label = cbp.category.name,
                    progress = cbp.progress,
                    dotArgb = cbp.category.color,
                    modifier = Modifier.clickable { onEditCategory(cbp.category.id) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onAddCategoryBudget) { Text("+ Add category budget") }
    }
}

@Composable
private fun BudgetRow(
    label: String,
    progress: BudgetProgress,
    modifier: Modifier = Modifier,
    dotArgb: Int? = null,
) {
    val barColor = if (progress.overBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dotArgb != null) {
                    ColorDot(dotArgb)
                    Spacer(Modifier.width(8.dp))
                }
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "${formatMyr(progress.spentMinor)} / ${formatMyr(progress.budgetMinor)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (progress.overBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = barColor,
        )
    }
}
