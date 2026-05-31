package cy.txtracker.ui.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cy.txtracker.ui.format.formatMyr
import cy.txtracker.ui.insights.BreakdownSlice
import cy.txtracker.ui.insights.DayBucket
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/**
 * One column per day in [days], stacked by [series] (same keys/colours as the pie's breakdown).
 * Heights are scaled to the peak day. X axis is summarised by the first/last day labels (per-bar
 * labels don't fit a month of bars); a [BreakdownLegend] names the stacked series.
 */
@Composable
fun DailyStackedBarChart(
    days: List<DayBucket>,
    series: List<BreakdownSlice>,
    modifier: Modifier = Modifier,
) {
    val maxTotal = days.maxOfOrNull { it.totalMinor } ?: 0L
    if (days.isEmpty() || maxTotal <= 0L) {
        EmptyChart("No spending to chart in this range", modifier)
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Peak day ${formatMyr(maxTotal)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
            val slot = size.width / days.size
            val gap = slot * 0.15f
            val barWidth = slot - gap * 2f
            days.forEachIndexed { i, day ->
                val x = i * slot + gap
                var yBottom = size.height
                series.forEach { s ->
                    val value = day.totalsByKey[s.key] ?: 0L
                    if (value > 0L) {
                        val h = (value.toFloat() / maxTotal.toFloat()) * size.height
                        drawRect(
                            color = Color(s.colorArgb),
                            topLeft = Offset(x, yBottom - h),
                            size = Size(barWidth, h),
                        )
                        yBottom -= h
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = dayMonthLabel(days.first().date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = dayMonthLabel(days.last().date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        BreakdownLegend(series)
    }
}
