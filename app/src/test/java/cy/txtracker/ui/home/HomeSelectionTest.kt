package cy.txtracker.ui.home

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.TimeBucket
import cy.txtracker.notify.DeeplinkBus
import cy.txtracker.ui.edit.DeletedTransaction
import cy.txtracker.service.CurrencyPrefs
import cy.txtracker.service.FeatureFlags
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSelectionTest {

    private val repository = mockk<TransactionRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(): HomeViewModel {
        every { repository.observeCurrencyReviewTransactions() } returns flowOf(emptyList())
        every { repository.observeAllCategories() } returns flowOf(emptyList())
        every { repository.observeFundingSources() } returns flowOf(emptyList())
        every { repository.observeSlDebitBalance() } returns flowOf(0L)
        every { repository.observeSlDebitAccount() } returns flowOf(null)
        every { repository.observeMerchantNotes() } returns flowOf(emptyList())
        val prefs = mockk<CurrencyPrefs>(relaxed = true).also {
            every { it.dismissed } returns MutableStateFlow(emptySet())
        }
        val flags = mockk<FeatureFlags>(relaxed = true).also {
            every { it.slDebitUnlocked } returns MutableStateFlow(false)
        }
        val bus = mockk<DeeplinkBus>(relaxed = true).also {
            every { it.forHome } returns MutableSharedFlow()
        }
        return HomeViewModel(repository, prefs, bus, flags)
    }

    @Test
    fun enter_toggle_clear_selection_transitions() = runTest {
        val m = vm()
        m.enterSelection(1L)
        assertThat(m.selectionMode.value).isTrue()
        assertThat(m.selectedIds.value).containsExactly(1L)
        m.toggleSelect(2L)
        assertThat(m.selectedIds.value).containsExactly(1L, 2L)
        m.toggleSelect(1L)
        assertThat(m.selectedIds.value).containsExactly(2L)
        m.clearSelection()
        assertThat(m.selectionMode.value).isFalse()
        assertThat(m.selectedIds.value).isEmpty()
    }

    @Test
    fun confirmSelected_calls_repo_and_clears() = runTest {
        val m = vm()
        m.enterSelection(3L)
        m.toggleSelect(4L)
        m.confirmSelected()
        coVerify { repository.confirmTransactions(match { it.toSet() == setOf(3L, 4L) }, any()) }
        assertThat(m.selectionMode.value).isFalse()
    }

    @Test
    fun deleteSelected_passes_snapshots_to_callback_and_clears() = runTest {
        val snapshot = DeletedTransaction(
            transaction = sampleTx(5L),
            reimbursements = emptyList(),
        )
        io.mockk.coEvery { repository.deleteTransactions(listOf(5L)) } returns listOf(snapshot)
        val m = vm()
        m.enterSelection(5L)
        var got: List<DeletedTransaction>? = null
        m.deleteSelected { snapshots -> got = snapshots }
        coVerify { repository.deleteTransactions(listOf(5L)) }
        assertThat(got).isEqualTo(listOf(snapshot))
        assertThat(m.selectionMode.value).isFalse()
    }

    @Test
    fun restoreTransactionsBatch_calls_repository() = runTest {
        val snapshot = DeletedTransaction(sampleTx(7L), emptyList())
        val m = vm()
        m.restoreTransactionsBatch(listOf(snapshot))
        coVerify { repository.restoreTransactions(listOf(snapshot)) }
    }

    private fun sampleTx(id: Long) = Transaction(
        id = id, amountMinor = 100L, currency = "MYR", merchantRaw = "X",
        merchantNormalized = "X", categoryId = null, description = null,
        occurredAt = kotlinx.datetime.Instant.parse("2026-06-17T12:00:00Z"),
        timeBucket = TimeBucket.AFTERNOON, sourceApp = "p",
        rawText = null, direction = Direction.OUT,
        createdAt = kotlinx.datetime.Instant.parse("2026-06-17T12:00:00Z"),
        notificationDedupeKey = "k",
    )
}
