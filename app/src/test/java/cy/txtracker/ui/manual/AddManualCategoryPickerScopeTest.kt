package cy.txtracker.ui.manual

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import cy.txtracker.data.FundingSourceDao
import cy.txtracker.data.TransactionRepository
import cy.txtracker.data.TripWindow
import cy.txtracker.service.FeatureFlags
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddManualCategoryPickerScopeTest {

    private val repository = mockk<TransactionRepository>(relaxed = true)
    private val fundingSourceDao = mockk<FundingSourceDao>(relaxed = true)
    private val featureFlags = mockk<FeatureFlags>(relaxed = true)

    private val t0 = Instant.parse("2026-06-01T08:00:00Z")

    private val globalCat = Category(id = 1L, name = "Food", color = 0xFF4CAF50.toInt(), isCustom = false, sortOrder = 0, tripId = null)
    private val tripCat = Category(id = 2L, name = "Attractions", color = 0xFF2196F3.toInt(), isCustom = true, sortOrder = 0, tripId = 7L)
    private val trip = TripWindow(id = 7L, currency = "JPY", startAt = t0, endAt = null, createdAt = t0)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        every { featureFlags.slDebitUnlocked } returns MutableStateFlow(false)
        every { repository.observeTrackedCurrencies() } returns flowOf(emptyList())
        every { repository.observeGlobalCategories() } returns flowOf(listOf(globalCat))
        every { repository.observeCategoriesForTrip(7L) } returns flowOf(listOf(tripCat))
        every { repository.observeAllCategories() } returns flowOf(listOf(globalCat, tripCat))

        coEvery { fundingSourceDao.getAll() } returns emptyList()
        coEvery { fundingSourceDao.getDefaultCash() } returns null
        coEvery { repository.getSlDebitAccount() } returns null
        coEvery { repository.findActiveTrip("MYR", any()) } returns null
        coEvery { repository.findActiveTrip("JPY", any()) } returns trip
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = AddManualViewModel(repository, fundingSourceDao, featureFlags)

    /** MYR (Home) load → picker shows only global categories; observeAllCategories not used for picker. */
    @Test
    fun `MYR initial currency shows only global categories`() = runTest {
        val m = vm()
        m.load(initialCurrency = "MYR", initialOccurredAt = t0)

        val state = m.state.value
        assertThat(state.categories).containsExactly(globalCat)
        assertThat(state.categories).doesNotContain(tripCat)

        // observeAllCategories must NOT be called for the picker
        verify(exactly = 0) { repository.observeAllCategories() }
    }

    /** Null initial currency (default MYR) → same scoping as explicit MYR. */
    @Test
    fun `null initial currency defaults to MYR scope`() = runTest {
        val m = vm()
        m.load(initialCurrency = null, initialOccurredAt = t0)

        val state = m.state.value
        assertThat(state.categories).containsExactly(globalCat)
        assertThat(state.categories).doesNotContain(tripCat)
    }

    /** Non-MYR with a covering trip → picker shows trip categories, not global. */
    @Test
    fun `JPY with covering trip shows trip categories`() = runTest {
        val m = vm()
        m.load(initialCurrency = "JPY", initialOccurredAt = t0)

        val state = m.state.value
        assertThat(state.categories).containsExactly(tripCat)
        assertThat(state.categories).doesNotContain(globalCat)

        verify(exactly = 0) { repository.observeAllCategories() }
    }

    /** Switching from JPY (trip) to MYR → categories switch to global, selected trip category cleared. */
    @Test
    fun `switching from JPY to MYR clears trip category and shows global categories`() = runTest {
        val m = vm()
        m.load(initialCurrency = "JPY", initialOccurredAt = t0)

        // Select the trip category
        m.setCategoryId(tripCat.id)
        assertThat(m.state.value.categoryId).isEqualTo(tripCat.id)

        // Switch to MYR
        m.setCurrency("MYR")

        val state = m.state.value
        assertThat(state.currency).isEqualTo("MYR")
        assertThat(state.categories).containsExactly(globalCat)
        assertThat(state.categories).doesNotContain(tripCat)
        // Selected category must be cleared because it's now out of scope
        assertThat(state.categoryId).isNull()
    }

    /** Switching from MYR to JPY (with covering trip) → categories switch to trip categories. */
    @Test
    fun `switching from MYR to JPY loads trip categories`() = runTest {
        val m = vm()
        m.load(initialCurrency = "MYR", initialOccurredAt = t0)

        assertThat(m.state.value.categories).containsExactly(globalCat)

        m.setCurrency("JPY")

        val state = m.state.value
        assertThat(state.currency).isEqualTo("JPY")
        assertThat(state.categories).containsExactly(tripCat)
    }
}
