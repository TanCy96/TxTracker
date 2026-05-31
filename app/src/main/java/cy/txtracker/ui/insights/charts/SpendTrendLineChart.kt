package cy.txtracker.ui.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import cy.txtracker.ui.format.formatMyr
import cy.txtracker.ui.insights.MonthBucket

/**
 * Simple month-over-month line of [points] in [lineColor], with month labels beneath. The points
 * span the full width (first at the left edge, last at the right), so an evenly-spaced label row
 * lines up under them.
 */
@Composable
fun SpendTrendLineChart(
    points: List<MonthBucket>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    val maxVal = points.maxOfOrNull { it.totalMinor } ?: 0L
    if (points.isEmpty() || maxVal <= 0L) {
        EmptyChart("No data for this period", modifier)
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Peak month ${formatMyr(maxVal)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
            val n = points.size
            fun xFor(i: Int): Float = if (n == 1) size.width / 2f else i.toFloat() / (n - 1) * size.width
            fun yFor(value: Long): Float = size.height - (value.toFloat() / maxVal.toFloat()) * size.height

            for (i in 0 until n - 1) {
                drawLine(
                    color = lineColor,
                    start = Offset(xFor(i), yFor(points[i].totalMinor)),
                    end = Offset(xFor(i + 1), yFor(points[i + 1].totalMinor)),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            points.forEachIndexed { i, p ->
                drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(xFor(i), yFor(p.totalMinor)))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (points.size == 1) Arrangement.Center else Arrangement.SpaceBetween,
        ) {
            points.forEach { p ->
                Text(
                    text = shortMonthLabel(p.yearMonth),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
