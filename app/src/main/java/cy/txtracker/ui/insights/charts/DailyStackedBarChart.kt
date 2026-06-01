package cy.txtracker.ui.insights.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.stacked
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import cy.txtracker.ui.insights.BreakdownSlice
import cy.txtracker.ui.insights.DayBucket

/**
 * Vico stacked-column chart: one column per day in [days], stacked by [series] (same keys/colours
 * as the pie's breakdown). Series values are pushed in ringgit so the Y axis reads in RM; the X
 * axis maps each column index back to its day. A [BreakdownLegend] names the stacked series.
 */
@Composable
fun DailyStackedBarChart(
    days: List<DayBucket>,
    series: List<BreakdownSlice>,
    modifier: Modifier = Modifier,
    onKeyTap: ((String) -> Unit)? = null,
) {
    if (days.isEmpty() || series.isEmpty() || days.all { it.totalMinor == 0L }) {
        EmptyChart("No spending to chart in this range", modifier)
        return
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(days, series) {
        producer.runTransaction {
            columnSeries {
                series.forEach { slice ->
                    series(days.map { (it.totalsByKey[slice.key] ?: 0L) / 100.0 })
                }
            }
        }
    }
    val columns = series.map { slice -> rememberLineComponent(fill = fill(Color(slice.colorArgb)), thickness = 10.dp) }
    val dayFormatter = CartesianValueFormatter { _, value, _ ->
        days.getOrNull(value.toInt())?.date?.let(::dayMonthLabel) ?: ""
    }
    Column(modifier = modifier.fillMaxWidth()) {
        CartesianChartHost(
            rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = ColumnCartesianLayer.ColumnProvider.series(columns),
                    mergeMode = { ColumnCartesianLayer.MergeMode.stacked() },
                ),
                startAxis = VerticalAxis.rememberStart(valueFormatter = RinggitAxisFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = dayFormatter),
            ),
            producer,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
        Spacer(Modifier.height(12.dp))
        BreakdownLegend(series, onKeyTap = onKeyTap)
    }
}
