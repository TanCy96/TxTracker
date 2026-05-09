package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MerchantNormalizerTest {

    @Test fun blank_returns_empty() = assertThat(normalizeMerchant("   ")).isEqualTo("")

    @Test fun uppercases_and_trims() {
        assertThat(normalizeMerchant("  chong tyre  ")).isEqualTo("CHONG TYRE")
    }

    @Test fun collapses_internal_whitespace() {
        assertThat(normalizeMerchant("CHONG    TYRE   AUTO")).isEqualTo("CHONG TYRE AUTO")
    }

    @Test fun strips_trailing_card_tail() {
        assertThat(normalizeMerchant("ABC RESTAURANT **1234")).isEqualTo("ABC RESTAURANT")
        assertThat(normalizeMerchant("ABC ***12345")).isEqualTo("ABC")
    }

    @Test fun strips_svc_suffix_so_real_world_dup_collapses() {
        // Exactly the case the user reported: GWallet says "CHONG TYRE AUTO SVC", bank app might
        // say "CHONG TYRE AUTO" — both must normalize identically for cross-source dedupe.
        assertThat(normalizeMerchant("CHONG TYRE AUTO SVC"))
            .isEqualTo(normalizeMerchant("CHONG TYRE AUTO"))
    }

    @Test fun strips_sdn_bhd_variants() {
        assertThat(normalizeMerchant("ACME SDN BHD")).isEqualTo("ACME")
        assertThat(normalizeMerchant("ACME SDN. BHD.")).isEqualTo("ACME")
        assertThat(normalizeMerchant("ACME SDN BHD.")).isEqualTo("ACME")
    }

    @Test fun strips_chained_suffixes_iteratively() {
        assertThat(normalizeMerchant("ACME TRADING SDN BHD")).isEqualTo("ACME")
        assertThat(normalizeMerchant("FOO SERVICES SDN BHD")).isEqualTo("FOO")
        assertThat(normalizeMerchant("FOO ENT (M) SDN BHD")).isEqualTo("FOO")
    }

    @Test fun strips_country_marker_variants() {
        assertThat(normalizeMerchant("ACME (M)")).isEqualTo("ACME")
        assertThat(normalizeMerchant("ACME (MALAYSIA)")).isEqualTo("ACME")
        assertThat(normalizeMerchant("ACME MALAYSIA")).isEqualTo("ACME")
        assertThat(normalizeMerchant("ACME MSIA")).isEqualTo("ACME")
    }

    @Test fun does_not_strip_internal_words_resembling_suffixes() {
        // "SVC" appearing internally must not be stripped — only trailing.
        assertThat(normalizeMerchant("SVC PLUS CAFE")).isEqualTo("SVC PLUS CAFE")
        assertThat(normalizeMerchant("ENT GROUP CAFE")).isEqualTo("ENT GROUP CAFE")
    }

    @Test fun preserves_short_names() {
        assertThat(normalizeMerchant("ABC")).isEqualTo("ABC")
        assertThat(normalizeMerchant("711")).isEqualTo("711")
    }
}
