package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class TripCategoriesRepoTest {

    private val now = Instant.parse("2026-07-01T00:00:00Z")
    private val database = mockk<TxDatabase>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val tripWindowDao = mockk<TripWindowDao>(relaxed = true)
    private val trackedCurrencyDao = mockk<TrackedCurrencyDao>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)

    /**
     * Drives [TransactionRepository.openTripBody] directly (not the public [openTrip] wrapper)
     * to avoid mocking Room's `withTransaction` extension, which does not invoke its lambda under
     * a relaxed mockk — the same isolation pattern used by PromotePoolEntryTest and
     * FundingSourceMergeTest.
     */
    @Test
    fun openTrip_seeds_template_categories_scoped_to_new_trip() = runTest {
        coEvery { tripWindowDao.insert(any()) } returns 55L
        val inserted = mutableListOf<Category>()
        coEvery { categoryDao.insert(capture(inserted)) } returns 1L

        val repo = makeRepo()
        val tripId = repo.openTripBody("USD", now, null, now)

        assertThat(tripId).isEqualTo(55L)
        assertThat(inserted).hasSize(DefaultTripCategories.template.size)
        assertThat(inserted.map { it.name })
            .containsExactlyElementsIn(DefaultTripCategories.template.map { it.name })
        assertThat(inserted.all { it.tripId == 55L }).isTrue()
        assertThat(inserted.all { !it.isCustom }).isTrue()
        inserted.forEachIndexed { i, c -> assertThat(c.sortOrder).isEqualTo(i) }
    }

    @Test
    fun addCategoryInScope_rejects_duplicate_within_same_trip() = runTest {
        coEvery { categoryDao.getForTrip(55L) } returns listOf(
            Category(id = 1, name = "Food & Drink", color = 1, isCustom = false, sortOrder = 0, tripId = 55L),
        )
        val repo = makeRepo()
        val result = repo.addCategoryInScope("food & drink", color = 2, keywordPattern = null, tripId = 55L)
        assertThat(result).isNull()
        coVerify(exactly = 0) { categoryDao.insert(any()) }
    }

    @Test
    fun addCategoryInScope_allows_same_name_in_different_trip() = runTest {
        coEvery { categoryDao.getForTrip(99L) } returns emptyList()
        coEvery { categoryDao.insert(any()) } returns 7L
        val repo = makeRepo()
        val result = repo.addCategoryInScope("Food & Drink", color = 2, keywordPattern = null, tripId = 99L)
        assertThat(result).isEqualTo(7L)
        coVerify { categoryDao.insert(match { it.name == "Food & Drink" && it.tripId == 99L }) }
    }

    @Test
    fun renameCategoryInScope_rejects_collision_within_scope() = runTest {
        val original = Category(id = 1, name = "Food", color = 1, isCustom = true, sortOrder = 0, tripId = 55L)
        coEvery { categoryDao.getForTrip(55L) } returns listOf(
            original,
            Category(id = 2, name = "Transport", color = 2, isCustom = true, sortOrder = 1, tripId = 55L)
        )
        val repo = makeRepo()
        val result = repo.renameCategoryInScope(original, "transport", 9, null)
        assertThat(result).isFalse()
        coVerify(exactly = 0) { categoryDao.update(any()) }
    }

    @Test
    fun renameCategoryInScope_allows_self_rename_recolor() = runTest {
        val original = Category(id = 1, name = "Food", color = 1, isCustom = true, sortOrder = 0, tripId = 55L)
        coEvery { categoryDao.getForTrip(55L) } returns listOf(original)
        val repo = makeRepo()
        val result = repo.renameCategoryInScope(original, "Food", 9, null)
        assertThat(result).isTrue()
        coVerify(exactly = 1) { categoryDao.update(any()) }
    }

    private fun makeRepo(): TransactionRepository = TransactionRepository(
        database = database,
        transactionDao = txDao,
        categoryDao = categoryDao,
        merchantMappingDao = mockk(relaxed = true),
        descriptionMappingDao = mockk(relaxed = true),
        merchantNoteDao = mockk(relaxed = true),
        userFacingSourceDao = mockk(relaxed = true),
        approvedSourceDao = mockk(relaxed = true),
        capturedNotificationDao = mockk(relaxed = true),
        rejectedSourceDao = mockk(relaxed = true),
        trackedCurrencyDao = trackedCurrencyDao,
        tripWindowDao = tripWindowDao,
        packageTextRewriteDao = mockk(relaxed = true),
        fundingSourceDao = mockk(relaxed = true),
        slDebitDao = mockk(relaxed = true),
        reimbursementEntryDao = mockk(relaxed = true),
        customSourceLabelDao = mockk(relaxed = true),
        autoPromoteSourceDao = mockk(relaxed = true),
        categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
        descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk<FundingSourceClassifier>().also {
            coEvery { it.classify(any(), any(), any()) } returns 1L
        },
    )
}
