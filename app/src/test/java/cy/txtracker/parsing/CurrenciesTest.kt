package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CurrenciesTest {

    @Test
    fun resolve_explicit_code_wins() {
        // Suffix code form: "1 MYR", "100 GBP"
        assertThat(Currencies.resolve(prefixToken = null, suffixToken = "GBP", symbolDefaults = emptyMap()))
            .isEqualTo("GBP")
        // Prefix code form: "MYR 1", "USD 100"
        assertThat(Currencies.resolve(prefixToken = "MYR", suffixToken = null, symbolDefaults = emptyMap()))
            .isEqualTo("MYR")
    }

    @Test
    fun resolve_unambiguous_symbol() {
        assertThat(Currencies.resolve(prefixToken = "£", suffixToken = null, symbolDefaults = emptyMap()))
            .isEqualTo("GBP")
        assertThat(Currencies.resolve(prefixToken = "€", suffixToken = null, symbolDefaults = emptyMap()))
            .isEqualTo("EUR")
        assertThat(Currencies.resolve(prefixToken = "RM", suffixToken = null, symbolDefaults = emptyMap()))
            .isEqualTo("MYR")
    }

    @Test
    fun resolve_ambiguous_symbol_with_default() {
        val defaults = mapOf("$" to "USD", "¥" to "JPY")
        assertThat(Currencies.resolve(prefixToken = "$", suffixToken = null, symbolDefaults = defaults))
            .isEqualTo("USD")
        assertThat(Currencies.resolve(prefixToken = "¥", suffixToken = null, symbolDefaults = defaults))
            .isEqualTo("JPY")
    }

    @Test
    fun resolve_ambiguous_symbol_without_default_returns_unknown() {
        assertThat(Currencies.resolve(prefixToken = "$", suffixToken = null, symbolDefaults = emptyMap()))
            .isEqualTo("UNKNOWN")
        assertThat(Currencies.resolve(prefixToken = "¥", suffixToken = null, symbolDefaults = emptyMap()))
            .isEqualTo("UNKNOWN")
    }

    @Test
    fun resolve_unknown_three_letter_code_returns_unknown() {
        // XYZ is not in KNOWN_CODES. Treat as unknown — don't trust arbitrary TLAs.
        assertThat(Currencies.resolve(prefixToken = null, suffixToken = "XYZ", symbolDefaults = emptyMap()))
            .isEqualTo("UNKNOWN")
    }

    @Test
    fun resolve_both_tokens_null_returns_unknown() {
        assertThat(Currencies.resolve(prefixToken = null, suffixToken = null, symbolDefaults = emptyMap()))
            .isEqualTo("UNKNOWN")
    }

    @Test
    fun resolve_explicit_suffix_beats_ambiguous_prefix() {
        // Hypothetical odd notification: "$ 100 USD" — explicit code wins.
        assertThat(Currencies.resolve(prefixToken = "$", suffixToken = "USD", symbolDefaults = emptyMap()))
            .isEqualTo("USD")
    }
}
