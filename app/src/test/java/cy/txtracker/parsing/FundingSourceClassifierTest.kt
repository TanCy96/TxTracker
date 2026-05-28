package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.FundingSourceKind
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
}
