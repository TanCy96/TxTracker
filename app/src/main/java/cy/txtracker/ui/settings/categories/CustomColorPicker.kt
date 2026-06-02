package cy.txtracker.ui.settings.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Modal HSV color picker: a saturation/brightness square (drag the thumb), a hue rail, and
 * a two-way-synced `#RRGGBB` field, with a live preview. Produces an opaque ARGB int.
 *
 * Color math lives in [ColorMath] (pure, JVM-tested); this file is only the Compose surface.
 */
@Composable
fun CustomColorPickerDialog(
    initialColor: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialHsv = remember(initialColor) { colorIntToHsv(initialColor) }
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var sat by remember { mutableStateOf(initialHsv[1]) }
    var value by remember { mutableStateOf(initialHsv[2]) }
    var hexText by remember { mutableStateOf(formatHexColor(initialColor)) }
    var hexError by remember { mutableStateOf(false) }

    val color = hsvToColorInt(hue, sat, value)

    // Picker -> hex: keep the field showing the live color.
    fun syncHexFromPicker() {
        hexText = formatHexColor(hsvToColorInt(hue, sat, value))
        hexError = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom color") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SaturationValueSquare(
                    hue = hue,
                    saturation = sat,
                    value = value,
                    onChange = { s, v -> sat = s; value = v; syncHexFromPicker() },
                )
                Spacer(Modifier.height(16.dp))
                HueRail(
                    hue = hue,
                    onHueChange = { hue = it; syncHexFromPicker() },
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(color), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { input ->
                            hexText = input
                            val parsed = parseHexColor(input)
                            if (parsed != null) {
                                val hsv = colorIntToHsv(parsed)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                                hexError = false
                            } else {
                                hexError = true
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        isError = hexError,
                        supportingText = { if (hexError) Text("Use #RRGGBB or #RGB") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Saturation (x: 0→1, left→right) × brightness (y: 1→0, top→bottom) square at the given
 * [hue]. Tap or drag anywhere to pick; [onChange] reports the new (saturation, value).
 */
@Composable
private fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (saturation: Float, value: Float) -> Unit,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val pureHue = Color(hsvToColorInt(hue, 1f, 1f))

    fun report(offset: Offset) {
        val w = boxSize.width.toFloat().coerceAtLeast(1f)
        val h = boxSize.height.toFloat().coerceAtLeast(1f)
        val s = (offset.x / w).coerceIn(0f, 1f)
        val v = 1f - (offset.y / h).coerceIn(0f, 1f)
        onChange(s, v)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .onSizeChanged { boxSize = it }
            .background(Brush.horizontalGradient(listOf(Color.White, pureHue)))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .pointerInput(Unit) { detectTapGestures { report(it) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> report(change.position) }
            },
    ) {
        if (boxSize != IntSize.Zero) {
            val thumbX = saturation * boxSize.width
            val thumbY = (1f - value) * boxSize.height
            val px = with(LocalDensity.current) { 14.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset((thumbX - px / 2f).toInt(), (thumbY - px / 2f).toInt())
                    }
                    .size(14.dp)
                    .border(2.dp, Color.White, CircleShape)
                    .border(3.dp, Color.Black.copy(alpha = 0.4f), CircleShape),
            )
        }
    }
}

/** Horizontal hue rail (0→360). Tap or drag to set [hue]; reports via [onHueChange]. */
@Composable
private fun HueRail(
    hue: Float,
    onHueChange: (Float) -> Unit,
) {
    var railSize by remember { mutableStateOf(IntSize.Zero) }

    fun report(offset: Offset) {
        val w = railSize.width.toFloat().coerceAtLeast(1f)
        onHueChange((offset.x / w).coerceIn(0f, 1f) * 360f)
    }

    val hueColors = remember {
        listOf(0, 60, 120, 180, 240, 300, 360).map { Color(hsvToColorInt(it.toFloat(), 1f, 1f)) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .onSizeChanged { railSize = it }
            .background(Brush.horizontalGradient(hueColors))
            .pointerInput(Unit) { detectTapGestures { report(it) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> report(change.position) }
            },
    ) {
        if (railSize != IntSize.Zero) {
            val thumbX = (hue / 360f) * railSize.width
            val px = with(LocalDensity.current) { 20.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset { IntOffset((thumbX - px / 2f).toInt(), 0) }
                    .size(width = 4.dp, height = 24.dp)
                    .background(Color.White)
                    .border(1.dp, Color.Black.copy(alpha = 0.4f)),
            )
        }
    }
}
