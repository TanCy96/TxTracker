package cy.txtracker.ui.insights.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import cy.txtracker.ui.insights.MonthBucket

/**
 * Vico line chart of month-over-month spend in [lineColor]. Values are pushed in ringgit (RM Y
 * axis); the X axis maps each point index to its month label. Reused for the total trend (primary
 * colour) and the per-category trend (the category's colour).
 */
@Composable
fun SpendTrendLineChart(
    points: List<MonthBucket>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    axisFormatter: CartesianValueFormatter = amountAxisFormatter("RM"),
) {
    if (points.isEmpty() || points.all { it.totalMinor == 0L }) {
        EmptyChart("No data for this period", modifier)
        return
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        producer.runTransaction {
            lineSeries { series(points.map { it.totalMinor / 100.0 }) }
        }
    }
    val monthFormatter = CartesianValueFormatter { _, value, _ ->
        points.getOrNull(value.toInt())?.yearMonth?.let(::shortMonthLabel) ?: ""
    }
    val line = LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(lineColor)))
    Column(modifier = modifier.fillMaxWidth()) {
        CartesianChartHost(
            rememberCartesianChart(
                rememberLineCartesianLayer(lineProvider = LineCartesianLayer.LineProvider.series(line)),
                startAxis = VerticalAxis.rememberStart(valueFormatter = axisFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = monthFormatter),
            ),
            producer,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
}
