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

        val r = extractor.extract(text, SourcePackages.GOOGLE_WALLET, now)!!
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
            "my.com.gxsbank" to "GX Bank",
            "com.gxsbank.my" to "GX Bank",
            "com.transferwise.android" to "Wise",
            "com.wise.android" to "Wise",
        )
        for ((pkg, label) in cases) {
            val r = extractor.extract("RM 5.00 charged today", pkg, now)
            assertThat(r?.merchantRaw).isEqualTo("$label (review)")
        }
    }

    @Test
    fun grab_car_booking_id_resolves_to_Grab_Car_merchant() {
        // Grab Car bookings use the `A-<alphanumeric>` booking ID format.
        val text = "Your Mastercard 1868 has been charged RM 25.00 for booking A-9AK6JSBWXF8SAV"
        val r = extractor.extract(text, SourcePackages.GRAB, now)!!
        assertThat(r.merchantRaw).isEqualTo("Grab Car")
        assertThat(r.amountMinor).isEqualTo(2500L)
    }

    @Test
    fun grab_food_booking_id_resolves_to_Grab_Food_merchant() {
        // Grab Food bookings use the `<digits>-<alphanumeric>` booking ID format. The
        // trailing period in the notification body must not contaminate the prefix check.
        val text = "Your MasterCard 1868 has been charged MYR 9.45 for booking 00193115115-C76ERE3UTTKHTA."
        val r = extractor.extract(text, SourcePackages.GRAB, now)!!
        assertThat(r.merchantRaw).isEqualTo("Grab Food")
        assertThat(r.amountMinor).isEqualTo(945L)
    }

    @Test
    fun grab_unknown_booking_id_format_falls_back_to_GRAB_merchant() {
        // Any other Grab product (Grab Mart, Grab Express, etc.) uses an unknown booking
        // ID format we haven't catalogued — fall back to "GRAB" rather than guess wrong.
        // Future enhancement: learn the mapping from user edits (FUTURE.md item 7c).
        val text = "Your MasterCard 1868 has been charged MYR 15.00 for booking X-99-FOOTHING"
        val r = extractor.extract(text, SourcePackages.GRAB, now)!!
        assertThat(r.merchantRaw).isEqualTo("GRAB")
    }

    @Test
    fun grab_text_without_booking_phrase_falls_back_to_GRAB_merchant() {
        // Defensive: if Grab ever changes the wording, we still get a "GRAB" capture
        // rather than an empty merchant or a crash.
        val text = "Your MasterCard 1868 was charged MYR 5.00 today"
        val r = extractor.extract(text, SourcePackages.GRAB, now)!!
        assertThat(r.merchantRaw).isEqualTo("GRAB")
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
        assertThat(extractor.extract("Welcome to Google Wallet", SourcePackages.GOOGLE_WALLET, now)).isNull()
        assertThat(extractor.extract("CIMB: Your password was changed.", "com.cimb.octo", now)).isNull()
    }

    @Test
    fun returns_null_for_blank_text() {
        assertThat(extractor.extract("", SourcePackages.GOOGLE_WALLET, now)).isNull()
        assertThat(extractor.extract("   ", SourcePackages.GOOGLE_WALLET, now)).isNull()
    }

    @Test
    fun handles_thousands_separator() {
        val r = extractor.extract(
            "Charge of RM 1,234.56 confirmed",
            SourcePackages.GOOGLE_WALLET,
            now,
        )!!
        assertThat(r.amountMinor).isEqualTo(123456L)
    }

    @Test
    fun captures_cimb_4_digit_amount_without_thousands_separator() {
        // Real CIMB capture: the amount has 4 leading digits and no comma. The previous
        // AMOUNT regex capped the leading group at 3 digits, so "1163.27" was matched as
        // "116" → 11600 minor units → displayed RM 116.00 instead of RM 1163.27.
        val text = "CIMB:MYR 1163.27 was charged on your card num..."
        val r = extractor.extract(text, "com.cimb.octo", now)!!
        assertThat(r.amountMinor).isEqualTo(116327L)
        assertThat(r.currency).isEqualTo("MYR")
    }
}
