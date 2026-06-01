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
}
