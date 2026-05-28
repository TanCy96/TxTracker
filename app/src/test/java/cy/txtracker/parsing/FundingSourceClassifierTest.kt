package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceDao
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.MANUAL_SOURCE_APP
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class FundingSourceClassifierTest {

    @Test
    fun rule1_with_card_name_and_bullets_extracts_card_and_last4() {
        val detected = FundingSourceClassifier.detect(
            rawText = "HEXTAR LUCKIN (M) SB RM7.41 with CIMB Cash Rebate Plat MasterCard ••1868",
            sourceApp = "com.google.android.apps.walletnfcrel",
        )
        assertThat(detected.kind).isEqualTo(FundingSourceKind.CREDIT_CARD)
        assertThat(detected.last4).isEqualTo("1868")
        assertThat(detected.displayName).isEqualTo("CIMB Cash Rebate Plat MasterCard 1868")
        assertThat(detected.sourceAppHint).isEqualTo("com.google.android.apps.walletnfcrel")
    }

    @Test
    fun rule2_bullets_only_uses_bank_label_for_name() {
        val detected = FundingSourceClassifier.detect(
            rawText = "Charged RM 50.00 ••1868 at MERCHANT",
            sourceApp = "com.hsbc.hsbcclassic",
        )
        assertThat(detected.kind).isEqualTo(FundingSourceKind.CREDIT_CARD)
        assertThat(detected.last4).isEqualTo("1868")
        assertThat(detected.displayName).isEqualTo("HSBC card 1868")
        assertThat(detected.sourceAppHint).isEqualTo("com.hsbc.hsbcclassic")
    }

    @Test
    fun rule3_card_ending_text_form() {
        val detected = FundingSourceClassifier.detect(
            rawText = "RM 30.00 charged on card 1234 @MERCHANT on 09/05",
            sourceApp = "com.cimbmalaysia",
        )
        assertThat(detected.kind).isEqualTo(FundingSourceKind.CREDIT_CARD)
        assertThat(detected.last4).isEqualTo("1234")
        assertThat(detected.displayName).isEqualTo("CIMB card 1234")
        assertThat(detected.sourceAppHint).isEqualTo("com.cimbmalaysia")
    }

    @Test
    fun rule4_account_ending_attributed_to_debit_bank() {
        val detected = FundingSourceClassifier.detect(
            rawText = "26MAY2026: Debited your A/C ending 0025 with MYR 13.00 " +
                "for DuitNow Transfer via Mobile Banking.",
            sourceApp = "com.hsbc.hsbcclassic",
        )
        assertThat(detected.kind).isEqualTo(FundingSourceKind.DEBIT_BANK)
        assertThat(detected.last4).isEqualTo("0025")
        assertThat(detected.displayName).isEqualTo("HSBC account 0025")
        assertThat(detected.sourceAppHint).isEqualTo("com.hsbc.hsbcclassic")
    }

    @Test
    fun rule5_duitnow_marker_with_no_last4_falls_to_unknown_account() {
        val detected = FundingSourceClassifier.detect(
            rawText = "We have debited your account due to DuitNow Transfer via Mobile Banking. " +
                "Amount: MYR 161.00 to ACME SDN BHD",
            sourceApp = "com.hsbc.email",
        )
        assertThat(detected.kind).isEqualTo(FundingSourceKind.DEBIT_BANK)
        assertThat(detected.last4).isNull()
        assertThat(detected.displayName).isEqualTo("HSBC (unknown account)")
    }

    @Test
    fun rule6_known_wallet_app_attributes_to_ewallet_with_no_last4() {
        val detected = FundingSourceClassifier.detect(
            rawText = "RM 1.00 has been successfully transferred to LIM SHER LYNN.",
            sourceApp = "my.com.tngdigital.ewallet",
        )
        assertThat(detected.kind).isEqualTo(FundingSourceKind.E_WALLET)
        assertThat(detected.last4).isNull()
        assertThat(detected.displayName).isEqualTo("TnG")
    }

    @Test
    fun wallet_app_with_transfer_in_body_still_attributes_to_ewallet() {
        // Regression: the word "Transfer" appears in DUITNOW_MARKERS, but wallet identity
        // is authoritative — a TnG notification using the word "Transfer" must still be
        // E_WALLET, not DEBIT_BANK.
        val detected = FundingSourceClassifier.detect(
            rawText = "RM 5.00 Transfer to JOHN DOE",
            sourceApp = "my.com.tngdigital.ewallet",
        )
        assertThat(detected.kind).isEqualTo(FundingSourceKind.E_WALLET)
        assertThat(detected.last4).isNull()
        assertThat(detected.displayName).isEqualTo("TnG")
        assertThat(detected.sourceAppHint).isEqualTo("my.com.tngdigital.ewallet")
    }

    @Test
    fun rule7_manual_entry_returns_cash_kind_via_classify() = runTest {
        val dao = mockk<FundingSourceDao>(relaxed = true)
        val cash = FundingSource(
            id = 7L,
            kind = FundingSourceKind.CASH,
            displayName = "Cash",
            last4 = null,
            sourceAppHint = null,
            isUserNamed = false,
            createdAt = Instant.parse("2026-05-28T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-28T00:00:00Z"),
        )
        coEvery { dao.getDefaultCash() } returns cash
        val classifier = FundingSourceClassifier(dao)
        val id = classifier.classify(
            rawText = null,
            sourceApp = MANUAL_SOURCE_APP,
            now = cash.createdAt,
        )
        assertThat(id).isEqualTo(7L)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun rule8_catchall_for_unknown_bank_with_no_markers() {
        val detected = FundingSourceClassifier.detect(
            rawText = "RM 5.00 spent at MERCHANT",
            sourceApp = "com.somebank.unknownapp",
        )
        assertThat(detected.kind).isEqualTo(FundingSourceKind.DEBIT_BANK)
        assertThat(detected.last4).isNull()
        // bankLabel falls back to last package segment capitalized — "Unknownapp"
        assertThat(detected.displayName).startsWith("Unknownapp")
    }

    @Test
    fun learning_loop_returns_existing_user_set_kind_without_reinferring() = runTest {
        val dao = mockk<FundingSourceDao>(relaxed = true)
        val userEdited = FundingSource(
            id = 42L,
            kind = FundingSourceKind.DEBIT_BANK,        // user flipped from CREDIT_CARD
            displayName = "HSBC debit 1868",            // user-renamed
            last4 = "1868",
            sourceAppHint = "com.hsbc.hsbcclassic",
            isUserNamed = true,
            createdAt = Instant.parse("2026-05-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-20T00:00:00Z"),
        )
        coEvery {
            dao.findByKey("com.hsbc.hsbcclassic", "1868")
        } returns userEdited
        val classifier = FundingSourceClassifier(dao)
        val id = classifier.classify(
            rawText = "Charged RM 50.00 ••1868 at MERCHANT",   // would infer CREDIT_CARD
            sourceApp = "com.hsbc.hsbcclassic",
            now = Instant.parse("2026-05-28T00:00:00Z"),
        )
        assertThat(id).isEqualTo(42L)
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.update(any()) }
        io.mockk.coVerify(exactly = 1) {
            dao.findByKey("com.hsbc.hsbcclassic", "1868")
        }
    }

    @Test
    fun classify_inserts_new_source_on_first_observation() = runTest {
        val dao = mockk<FundingSourceDao>(relaxed = true)
        coEvery { dao.findByKey(any(), any()) } returns null
        coEvery { dao.insert(any()) } returns 99L
        val classifier = FundingSourceClassifier(dao)
        val id = classifier.classify(
            rawText = "Charged RM 50.00 ••1868 at MERCHANT",
            sourceApp = "com.hsbc.hsbcclassic",
            now = Instant.parse("2026-05-28T00:00:00Z"),
        )
        assertThat(id).isEqualTo(99L)
        coVerify(exactly = 1) {
            dao.insert(match {
                it.kind == FundingSourceKind.CREDIT_CARD &&
                    it.displayName == "HSBC card 1868" &&
                    it.last4 == "1868" &&
                    it.sourceAppHint == "com.hsbc.hsbcclassic" &&
                    !it.isUserNamed
            })
        }
    }
}
