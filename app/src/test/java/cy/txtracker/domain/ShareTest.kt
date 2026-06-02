package cy.txtracker.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareTest {

    @Test fun zero_is_invalid() {
        assertThat(isValidReimbursedMinor(0, 10000)).isFalse()
    }

    @Test fun one_is_valid() {
        assertThat(isValidReimbursedMinor(1, 10000)).isTrue()
    }

    @Test fun equal_to_amount_is_valid() {
        assertThat(isValidReimbursedMinor(10000, 10000)).isTrue()
    }

    @Test fun greater_than_amount_is_invalid() {
        assertThat(isValidReimbursedMinor(10001, 10000)).isFalse()
    }

    @Test fun negative_is_invalid() {
        assertThat(isValidReimbursedMinor(-1, 10000)).isFalse()
    }

    // ─── Multi-entry reimbursement total ────────────────────────────────────────────────

    @Test fun total_of_empty_entries_is_null() {
        assertThat(reimbursedTotalMinor(emptyList())).isNull()
    }

    @Test fun total_sums_entries() {
        assertThat(reimbursedTotalMinor(listOf(1000L, 1200L))).isEqualTo(2200L)
    }

    @Test fun total_of_all_zero_is_null() {
        assertThat(reimbursedTotalMinor(listOf(0L))).isNull()
    }

    @Test fun entry_set_valid_when_each_positive_and_sum_in_range() {
        assertThat(isValidReimbursementTotal(listOf(4000L, 6000L), 10000L)).isTrue()
    }

    @Test fun entry_set_invalid_when_sum_exceeds_amount() {
        assertThat(isValidReimbursementTotal(listOf(4000L, 6001L), 10000L)).isFalse()
    }

    @Test fun entry_set_invalid_when_any_entry_non_positive() {
        assertThat(isValidReimbursementTotal(listOf(0L, 5000L), 10000L)).isFalse()
    }

    @Test fun empty_entry_set_is_invalid() {
        assertThat(isValidReimbursementTotal(emptyList(), 10000L)).isFalse()
    }
}
