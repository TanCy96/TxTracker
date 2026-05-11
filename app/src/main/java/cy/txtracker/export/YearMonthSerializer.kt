package cy.txtracker.export

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A plain year + month, January = 1. Stable across kotlinx-datetime versions (the library's
 * own YearMonth type is experimental as of 0.6.x). Used as the upload-cutoff floor for
 * cloud-sync transaction inclusion.
 */
data class YearMonth(val year: Int, val month: Int) {
    init {
        require(month in 1..12) { "month must be 1..12, was $month" }
    }

    /** "YYYY-MM" form used in the JSON wire format. */
    fun format(): String = "%04d-%02d".format(year, month)

    companion object {
        /** Parses "YYYY-MM". Throws on malformed input. */
        fun parse(text: String): YearMonth {
            val parts = text.split('-')
            require(parts.size == 2) { "expected YYYY-MM, was $text" }
            return YearMonth(parts[0].toInt(), parts[1].toInt())
        }
    }
}

/**
 * kotlinx-serialization wrapper for [YearMonth] as a "YYYY-MM" string.
 */
object YearMonthSerializer : KSerializer<YearMonth> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("YearMonth", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: YearMonth) {
        encoder.encodeString(value.format())
    }

    override fun deserialize(decoder: Decoder): YearMonth =
        YearMonth.parse(decoder.decodeString())
}
