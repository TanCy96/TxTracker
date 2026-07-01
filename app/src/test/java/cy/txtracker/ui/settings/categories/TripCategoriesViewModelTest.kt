package cy.txtracker.ui.settings.categories

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
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
class TripCategoriesViewModelTest {

    private val repository = mockk<TransactionRepository>(relaxed = true)

    private val ramenColor = 0xFF66BB6A.toInt()

    private val tripCategory = Category(
        id = 1L,
        name = "Food",
        color = ramenColor,
        isCustom = true,
        sortOrder = 0,
        tripId = 7L,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(): TripCategoriesViewModel {
        every { repository.observeCategoriesForTrip(7L) } returns flowOf(listOf(tripCategory))
        val handle = SavedStateHandle(mapOf("tripId" to 7L))
        return TripCategoriesViewModel(handle, repository)
    }

    @Test
    fun `categories flow proxies observeCategoriesForTrip for the trip`() = runTest {
        val m = vm()

        m.categories.test {
            val items = awaitItem()
            assertThat(items).containsExactly(tripCategory)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `add calls addCategoryInScope with null keywordPattern and correct tripId`() = runTest {
        val m = vm()

        m.add("Ramen", ramenColor)

        coVerify(exactly = 1) {
            repository.addCategoryInScope("Ramen", ramenColor, null, 7L)
        }
    }
}
