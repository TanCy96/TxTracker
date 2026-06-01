package cy.txtracker.ui.insights.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import cy.txtracker.domain.YearMonth
import cy.txtracker.ui.format.formatMyr
import cy.txtracker.ui.insights.BreakdownSlice
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/** Y-axis formatter for the Vico charts. Series values are pushed in major units, so prefix [symbol]. */
internal fun amountAxisFormatter(symbol: String): CartesianValueFormatter =
    CartesianValueFormatter { _, value, _ -> "$symbol ${value.roundToInt()}" }

private val SHORT_MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

internal fun shortMonthLabel(ym: YearMonth): String = SHORT_MONTHS[ym.month - 1]

internal fun dayMonthLabel(date: LocalDate): String = "${date.dayOfMonth} ${SHORT_MONTHS[date.monthNumber - 1]}"

/** A small filled colour dot used in chart legends. */
@Composable
internal fun ColorDot(argb: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(12.dp).clip(CircleShape).background(Color(argb)))
}

/** Centered placeholder for charts with no data in the selected range. */
@Composable
internal fun EmptyChart(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Legend rows shared by the pie and the stacked bar: colour dot, label, amount, and percentage. */
@Composable
internal fun BreakdownLegend(
    slices: List<BreakdownSlice>,
    modifier: Modifier = Modifier,
    onKeyTap: ((String) -> Unit)? = null,
    amountFormatter: (Long) -> String = { formatMyr(it) },
) {
    val total = slices.sumOf { it.totalMinor }
    Column(modifier = modifier.fillMaxWidth()) {
        slices.forEach { slice ->
            val pct = if (total > 0L) (100.0 * slice.totalMinor / total).roundToInt() else 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onKeyTap != null) Modifier.clickable { onKeyTap(slice.key) } else Modifier)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorDot(slice.colorArgb)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${amountFormatter(slice.totalMinor)}  ·  $pct%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
