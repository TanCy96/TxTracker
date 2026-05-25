package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceLabelsTest {

    @Test
    fun labels_known_malaysian_finance_packages() {
        assertThat(SourceLabels.label("my.com.gxsbank")).isEqualTo("GX Bank")
        assertThat(SourceLabels.label("com.cimb.octo")).isEqualTo("CIMB")
        assertThat(SourceLabels.label(SourcePackages.TOUCH_N_GO)).isEqualTo("TnG")
    }
}
