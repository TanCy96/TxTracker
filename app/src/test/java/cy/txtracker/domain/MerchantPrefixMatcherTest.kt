package cy.txtracker.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MerchantPrefixMatcherTest {

    @Test
    fun returns_exact_match_when_present() {
        val stored = listOf("STARBUCKS", "TEALIVE")
        assertThat(MerchantPrefixMatcher.longestPrefix("STARBUCKS", stored)).isEqualTo("STARBUCKS")
    }

    @Test
    fun returns_longest_prefix_when_captured_has_extra_tokens() {
        val stored = listOf("STARBUCKS")
        assertThat(
            MerchantPrefixMatcher.longestPrefix("STARBUCKS KLCC LEVEL 3", stored),
        ).isEqualTo("STARBUCKS")
    }

    @Test
    fun prefers_longer_stored_when_multiple_prefixes_match() {
        val stored = listOf("STARBUCKS", "STARBUCKS RESERVE")
        assertThat(
            MerchantPrefixMatcher.longestPrefix("STARBUCKS RESERVE BANGSAR", stored),
        ).isEqualTo("STARBUCKS RESERVE")
    }

    @Test
    fun does_not_match_byte_prefix_across_token_boundary() {
        // "STARB" is a byte-prefix of "STARBUCKS" but they share no whole-token boundary.
        // Match must NOT succeed.
        val stored = listOf("STARB")
        assertThat(MerchantPrefixMatcher.longestPrefix("STARBUCKS KLCC", stored)).isNull()
    }

    @Test
    fun does_not_match_when_captured_is_shorter_than_stored() {
        // Stored is more specific than captured — must NOT match.
        val stored = listOf("STARBUCKS KLCC")
        assertThat(MerchantPrefixMatcher.longestPrefix("STARBUCKS", stored)).isNull()
    }

    @Test
    fun returns_null_when_no_stored_is_a_prefix() {
        val stored = listOf("TEALIVE", "MIXUE")
        assertThat(MerchantPrefixMatcher.longestPrefix("STARBUCKS KLCC", stored)).isNull()
    }

    @Test
    fun returns_null_for_blank_captured() {
        assertThat(MerchantPrefixMatcher.longestPrefix("", listOf("STARBUCKS"))).isNull()
        assertThat(MerchantPrefixMatcher.longestPrefix("   ", listOf("STARBUCKS"))).isNull()
    }

    @Test
    fun returns_null_for_empty_stored_list() {
        assertThat(MerchantPrefixMatcher.longestPrefix("STARBUCKS KLCC", emptyList())).isNull()
    }
}
