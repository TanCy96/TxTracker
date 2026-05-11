package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test

class YearMonthSerializerTest {

    @Serializable
    private data class Wrapper(
        @kotlinx.serialization.Serializable(with = YearMonthSerializer::class)
        val ym: YearMonth?,
    )

    private val json = Json { encodeDefaults = true }

    @Test
    fun roundtrips_year_month() {
        val original = Wrapper(YearMonth(2024, 1))
        val encoded = json.encodeToString(Wrapper.serializer(), original)
        val decoded = json.decodeFromString(Wrapper.serializer(), encoded)
        assertThat(decoded.ym).isEqualTo(YearMonth(2024, 1))
        assertThat(encoded).contains(""""ym":"2024-01"""")
    }

    @Test
    fun encodes_null_as_null() {
        val original = Wrapper(null)
        val encoded = json.encodeToString(Wrapper.serializer(), original)
        assertThat(encoded).contains(""""ym":null""")
        val decoded = json.decodeFromString(Wrapper.serializer(), encoded)
        assertThat(decoded.ym).isNull()
    }

    @Test
    fun pads_single_digit_months() {
        val original = Wrapper(YearMonth(2024, 3))
        val encoded = json.encodeToString(Wrapper.serializer(), original)
        assertThat(encoded).contains(""""ym":"2024-03"""")
    }
}
