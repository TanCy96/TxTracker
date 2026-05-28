package cy.txtracker.ui.home

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.FundingSourceKind
import org.junit.Test

class FundingFilterStateTest {
    @Test
    fun empty_filter_passes_everything() {
        assertThat(matchesFundingFilter(emptySet(), FundingSourceKind.CREDIT_CARD)).isTrue()
        assertThat(matchesFundingFilter(emptySet(), null)).isTrue()
    }

    @Test
    fun selected_kind_passes_only_matching() {
        val s = setOf(FundingSourceKind.CREDIT_CARD)
        assertThat(matchesFundingFilter(s, FundingSourceKind.CREDIT_CARD)).isTrue()
        assertThat(matchesFundingFilter(s, FundingSourceKind.CASH)).isFalse()
    }

    @Test
    fun null_kind_does_not_match_when_filter_is_active() {
        // We treat null (unlinked tx) as NOT matching any active filter — same as
        // categoryFilter behavior. Locks the contract.
        val s = setOf(FundingSourceKind.CREDIT_CARD)
        assertThat(matchesFundingFilter(s, null)).isFalse()
    }

    @Test
    fun multi_select_passes_when_any_match() {
        val s = setOf(FundingSourceKind.CREDIT_CARD, FundingSourceKind.E_WALLET)
        assertThat(matchesFundingFilter(s, FundingSourceKind.CREDIT_CARD)).isTrue()
        assertThat(matchesFundingFilter(s, FundingSourceKind.E_WALLET)).isTrue()
        assertThat(matchesFundingFilter(s, FundingSourceKind.CASH)).isFalse()
    }
}
