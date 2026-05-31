package cy.txtracker.ui.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cy.txtracker.ui.insights.BreakdownSlice
import kotlin.math.min

/**
 * Hand-rolled pie of [slices], with a colour/amount/percentage legend below. Slices use their
 * resolved [BreakdownSlice.colorArgb] (category colours or the funding/unverified palette).
 */
@Composable
fun CategoryPieChart(slices: List<BreakdownSlice>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.totalMinor }
    if (slices.isEmpty() || total <= 0L) {
        EmptyChart("No spending to chart in this range", modifier)
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.padding(8.dp).size(200.dp)) {
            val diameter = min(size.width, size.height)
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            var startAngle = -90f
            slices.forEach { slice ->
                val sweep = 360f * (slice.totalMinor.toFloat() / total.toFloat())
                drawArc(
                    color = Color(slice.colorArgb),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                )
                startAngle += sweep
            }
        }
        Spacer(Modifier.height(12.dp))
        BreakdownLegend(slices)
    }
}
