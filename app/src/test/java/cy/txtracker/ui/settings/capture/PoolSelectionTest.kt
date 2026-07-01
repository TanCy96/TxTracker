package cy.txtracker.ui.settings.capture

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.TransactionRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PoolSelectionTest {

    private val repository = mockk<TransactionRepository>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(): PoolViewModel {
        every { repository.observeGlobalCategories() } returns flowOf(emptyList())
        every { repository.observePool(any(), any()) } returns flowOf(emptyList())
        every { repository.observeCustomLabels() } returns flowOf(emptyMap())
        return PoolViewModel(repository)
    }

    @Test
    fun enter_toggle_clear_selection() = runTest {
        val m = vm()
        m.enterSelection(1L)
        assertThat(m.selectionMode.value).isTrue()
        m.toggleSelect(2L)
        assertThat(m.selectedIds.value).containsExactly(1L, 2L)
        m.toggleSelect(1L)
        assertThat(m.selectedIds.value).containsExactly(2L)
        m.clearSelection()
        assertThat(m.selectionMode.value).isFalse()
        assertThat(m.selectedIds.value).isEmpty()
    }

    @Test
    fun toggling_last_id_exits_selection_mode() = runTest {
        val m = vm()
        m.enterSelection(1L)
        m.toggleSelect(1L)
        assertThat(m.selectedIds.value).isEmpty()
        assertThat(m.selectionMode.value).isFalse()
    }

    @Test
    fun approveSelected_promotes_and_clears() = runTest {
        val m = vm()
        m.enterSelection(3L)
        m.approveSelected()
        coVerify { repository.promotePoolEntries(listOf(3L), any()) }
        assertThat(m.selectionMode.value).isFalse()
    }

    @Test
    fun rejectSelected_marks_noise_and_clears() = runTest {
        val m = vm()
        m.enterSelection(4L)
        m.rejectSelected()
        coVerify { repository.markPoolEntriesNoise(listOf(4L)) }
        assertThat(m.selectionMode.value).isFalse()
    }
}
