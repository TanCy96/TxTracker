package cy.txtracker.ui.settings.categories

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure color math for the category color picker. Deliberately free of Android framework
 * types ([android.graphics.Color]) so it is unit-testable on the JVM. Every produced color
 * is an opaque ARGB int (alpha = 0xFF) — category swatches never use transparency.
 */

private const val OPAQUE_ALPHA = 0xFF shl 24

/** Opaque ARGB int from HSV. hueDegrees in [0,360], saturation/value in [0,1] (clamped). */
fun hsvToColorInt(hueDegrees: Float, saturation: Float, value: Float): Int {
    val h = ((hueDegrees % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)

    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val r = ((r1 + m) * 255f).roundToInt()
    val g = ((g1 + m) * 255f).roundToInt()
    val b = ((b1 + m) * 255f).roundToInt()
    return OPAQUE_ALPHA or (r shl 16) or (g shl 8) or b
}

/** `[hue (0..360), saturation (0..1), value (0..1)]` from an ARGB int (alpha ignored). */
fun colorIntToHsv(color: Int): FloatArray {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    val saturation = if (max == 0f) 0f else delta / max
    return floatArrayOf(hue, saturation, max)
}

private val HEX6 = Regex("[0-9a-fA-F]{6}")
private val HEX3 = Regex("[0-9a-fA-F]{3}")

/** Parse `#RRGGBB` / `RRGGBB` / `#RGB` / `RGB` to an opaque ARGB int, or null when invalid. */
fun parseHexColor(input: String): Int? {
    val raw = input.trim().removePrefix("#")
    val rgb = when {
        HEX6.matches(raw) -> raw
        HEX3.matches(raw) -> raw.map { "$it$it" }.joinToString("")
        else -> return null
    }
    val value = rgb.toLong(16).toInt()
    return OPAQUE_ALPHA or (value and 0x00FFFFFF)
}

/** Format an ARGB int as uppercase `#RRGGBB` (alpha dropped). */
fun formatHexColor(color: Int): String = "#%06X".format(color and 0x00FFFFFF)
