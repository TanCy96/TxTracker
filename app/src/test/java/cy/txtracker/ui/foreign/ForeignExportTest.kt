package cy.txtracker.ui.foreign

import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.TransactionRepository
import cy.txtracker.data.TripWindow
import cy.txtracker.export.CsvExporter
import io.mockk.coEvery
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
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForeignExportTest {

    private val repository = mockk<TransactionRepository>(relaxed = true)
    private val csvExporter = mockk<CsvExporter>(relaxed = true)

    private val t0 = Instant.parse("2026-06-01T00:00:00Z")

    private val fakeUri = mockk<Uri>().also { every { it.toString() } returns "content://export/USD.csv" }

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

        every { repository.observeAllTrips() } returns flowOf(listOf(trip))
        every { repository.observeCategoriesForTrip(7L) } returns flowOf(emptyList())
        every { repository.observeTransactionsForTrip(any(), any(), any()) } returns flowOf(emptyList())
        every { repository.observeMerchantNotes() } returns flowOf(emptyList())

        coEvery { csvExporter.exportCsv("USD", any()) } returns fakeUri

        return ForeignViewModel(repository, csvExporter)
    }

    @Test
    fun exportCurrentTrip_emitsReady_withUriString() = runTest {
        val m = vm()

        // Wait for loaded state first
        m.state.test {
            var state = awaitItem()
            if (state is ForeignUiState.Loading) state = awaitItem()
            assertThat(state).isInstanceOf(ForeignUiState.Loaded::class.java)
            cancelAndIgnoreRemainingEvents()
        }

        m.exportEvent.test {
            // Initial value is null
            assertThat(awaitItem()).isNull()

            m.exportCurrentTrip()

            val event = awaitItem()
            assertThat(event).isInstanceOf(ForeignExport.Ready::class.java)
            assertThat((event as ForeignExport.Ready).uri).isEqualTo("content://export/USD.csv")

            cancelAndIgnoreRemainingEvents()
        }

        coVerify { csvExporter.exportCsv("USD", any()) }
    }

    @Test
    fun consumeExportEvent_resetsToNull() = runTest {
        val m = vm()

        m.state.test {
            var state = awaitItem()
            if (state is ForeignUiState.Loading) state = awaitItem()
            assertThat(state).isInstanceOf(ForeignUiState.Loaded::class.java)
            cancelAndIgnoreRemainingEvents()
        }

        m.exportCurrentTrip()

        m.exportEvent.test {
            // skip whatever value is there
            awaitItem()
            m.consumeExportEvent()
            val afterConsume = awaitItem()
            assertThat(afterConsume).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
