package cy.txtracker.ui.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import cy.txtracker.ui.insights.BreakdownSlice
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hand-rolled pie of [slices], with a colour/amount/percentage legend below. Slices use their
 * resolved [BreakdownSlice.colorArgb]. When [onSliceTap] is set, tapping a slice (or a legend row)
 * reports its [BreakdownSlice.key] for drill-down.
 */
@Composable
fun CategoryPieChart(
    slices: List<BreakdownSlice>,
    modifier: Modifier = Modifier,
    onSliceTap: ((String) -> Unit)? = null,
) {
    val total = slices.sumOf { it.totalMinor }
    if (slices.isEmpty() || total <= 0L) {
        EmptyChart("No spending to chart in this range", modifier)
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .padding(8.dp)
                .size(200.dp)
                .then(
                    if (onSliceTap != null) {
                        Modifier.pointerInput(slices) {
                            detectTapGestures { offset ->
                                sliceKeyAt(offset, size.width.toFloat(), size.height.toFloat(), slices, total)
                                    ?.let(onSliceTap)
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
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
        BreakdownLegend(slices, onKeyTap = onSliceTap)
    }
}

/** Maps a tap [offset] on a [width]x[height] canvas to the key of the slice under it, or null. */
private fun sliceKeyAt(
    offset: Offset,
    width: Float,
    height: Float,
    slices: List<BreakdownSlice>,
    total: Long,
): String? {
    val cx = width / 2f
    val cy = height / 2f
    val radius = min(width, height) / 2f
    val dx = offset.x - cx
    val dy = offset.y - cy
    if (sqrt(dx * dx + dy * dy) > radius) return null
    // drawArc uses 0° = 3 o'clock, clockwise positive, start at -90° (12 o'clock).
    // atan2(dy, dx) (y down) matches that convention; normalise to [-90, 270).
    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    while (angle < -90f) angle += 360f
    while (angle >= 270f) angle -= 360f
    var acc = -90f
    for (slice in slices) {
        val sweep = 360f * (slice.totalMinor.toFloat() / total.toFloat())
        if (angle >= acc && angle < acc + sweep) return slice.key
        acc += sweep
    }
    return null
}
