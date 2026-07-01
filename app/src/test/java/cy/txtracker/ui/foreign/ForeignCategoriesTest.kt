package cy.txtracker.ui.foreign

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import cy.txtracker.data.TransactionRepository
import cy.txtracker.data.TripWindow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ForeignCategoriesTest {

    private val repository = mockk<TransactionRepository>(relaxed = true)

    private val t0 = Instant.parse("2026-06-01T00:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(): ForeignViewModel {
        val trip = TripWindow(id = 7, currency = "USD", startAt = t0, endAt = null, createdAt = t0)
        val category = Category(id = 1, name = "Attractions", color = 1, isCustom = false, sortOrder = 0, tripId = 7)

        every { repository.observeAllTrips() } returns flowOf(listOf(trip))
        every { repository.observeCategoriesForTrip(7L) } returns flowOf(listOf(category))
        every { repository.observeTransactionsForTrip(any(), any(), any()) } returns flowOf(emptyList())
        every { repository.observeMerchantNotes() } returns flowOf(emptyList())

        return ForeignViewModel(repository)
    }

    @Test
    fun loaded_state_uses_trip_categories_not_global() = runTest {
        val m = vm()

        m.state.test {
            // Skip Loading if emitted first
            var state = awaitItem()
            if (state is ForeignUiState.Loading) {
                state = awaitItem()
            }

            assertThat(state).isInstanceOf(ForeignUiState.Loaded::class.java)
            val loaded = state as ForeignUiState.Loaded
            assertThat(loaded.categories).containsExactly(
                Category(id = 1, name = "Attractions", color = 1, isCustom = false, sortOrder = 0, tripId = 7)
            )
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 0) { repository.observeAllCategories() }
    }
}
