package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import kotlinx.datetime.Instant
import org.junit.Test

class PermissiveExtractorTest {

    private val extractor = PermissiveExtractor()
    private val now = Instant.parse("2026-05-09T12:30:00Z")

    @Test
    fun captures_gwallet_text_with_amount_only_no_verb_or_recipient_needed() {
        // The exact GWallet shape that doesn't have an out-verb in it. The strict parser
        // and the heuristic both reject this; the permissive layer is the safety net.
        val text = "CHONG TYRE AUTO SVC RM530.00 with CIMB Cash Rebate Plat MasterCard **1868"

        val r = extractor.extract(text, GoogleWalletParser.GOOGLE_WALLET_PACKAGE, now)!!
        assertThat(r.amountMinor).isEqualTo(53000L)
        assertThat(r.merchantRaw).isEqualTo("GWallet (review)")
        assertThat(r.rawText).isEqualTo(text)
        assertThat(r.direction).isEqualTo(Direction.OUT)
    }

    @Test
    fun captures_cimb_text_with_an_amount() {
        val text = "Anything goes here MYR25.00 something something"
        val r = extractor.extract(text, "com.cimb.octo", now)!!
        assertThat(r.amountMinor).isEqualTo(2500L)
        assertThat(r.merchantRaw).isEqualTo("CIMB (review)")
    }

    @Test
    fun derives_label_for_known_bank_packages() {
        val cases = mapOf(
            "com.maybank2u.life" to "Maybank",
            "com.publicbank.pbe" to "Public Bank",
            "com.rhb.mobile" to "RHB",
            "com.hsbc.hsbcclassic" to "HSBC",
            "com.hongleong.hlb" to "Hong Leong",
            "com.ambank.amonline" to "AmBank",
            "com.bsn.cms" to "BSN",
        )
        for ((pkg, label) in cases) {
            val r = extractor.extract("RM 5.00 charged today", pkg, now)
            assertThat(r?.merchantRaw).isEqualTo("$label (review)")
        }
    }

    @Test
    fun returns_null_when_package_is_not_in_allowlist() {
        // Random IM app's notification mentioning a price — must not become a transaction.
        val text = "Friend: hey can you transfer me RM 50.00 for dinner"
        assertThat(extractor.extract(text, "com.whatsapp", now)).isNull()
    }

    @Test
    fun returns_null_when_text_has_no_amount() {
        // Allowlisted package but the notification carries no amount (e.g., promo, balance
        // alert without value, sign-in confirmation). No phantom row.
        assertThat(extractor.extract("Welcome to Google Wallet", GoogleWalletParser.GOOGLE_WALLET_PACKAGE, now)).isNull()
        assertThat(extractor.extract("CIMB: Your password was changed.", "com.cimb.octo", now)).isNull()
    }

    @Test
    fun returns_null_for_blank_text() {
        assertThat(extractor.extract("", GoogleWalletParser.GOOGLE_WALLET_PACKAGE, now)).isNull()
        assertThat(extractor.extract("   ", GoogleWalletParser.GOOGLE_WALLET_PACKAGE, now)).isNull()
    }

    @Test
    fun handles_thousands_separator() {
        val r = extractor.extract(
            "Charge of RM 1,234.56 confirmed",
            GoogleWalletParser.GOOGLE_WALLET_PACKAGE,
            now,
        )!!
        assertThat(r.amountMinor).isEqualTo(123456L)
    }
}
