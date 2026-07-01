package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * JVM unit tests for [resolveBackupCategoryId]. Exercises the core scope-resolution logic
 * extracted from [TransactionRepository.applyBackup]: for a given categoryName + currency +
 * occurredAt, pick the trip-scoped category when a covering trip exists, otherwise fall back
 * to the global category. No DB, no mockk — pure function calls only.
 */
class BackupCategoryResolutionTest {

    private val t0 = Instant.parse("2026-07-01T00:00:00Z")

    // Trip: USD, starts at t0, open-ended (endAt = null), id = 7.
    private val usdTrip = TripWindow(
        id = 7L,
        currency = "USD",
        startAt = t0,
        endAt = null,
        createdAt = t0,
    )

    // Global categories: "Attractions" -> 100L, "Food" -> 101L.
    private val globalCategoriesByName = mapOf(
        "Attractions" to 100L,
        "Food" to 101L,
    )

    // Trip-scoped categories: ("Attractions", tripId=7) -> 200L.
    // "Food" has no trip-scoped override.
    private val categoriesByScope = mapOf(
        ("Attractions" to 7L) to 200L,
    )

    private val tripWindows = listOf(usdTrip)

    // (a) USD tx inside the trip window, name "Attractions" → trip category 200L wins.
    @Test
    fun usd_tx_inside_window_prefers_trip_category() {
        val result = resolveBackupCategoryId(
            categoryName = "Attractions",
            currency = "USD",
            occurredAt = t0 + 15.days,
            tripWindows = tripWindows,
            categoriesByScope = categoriesByScope,
            globalCategoriesByName = globalCategoriesByName,
        )
        assertThat(result).isEqualTo(200L)
        assertThat(result).isNotEqualTo(100L)
    }

    // (b) MYR tx, name "Attractions" → global category 100L (MYR always uses global scope).
    @Test
    fun myr_tx_always_uses_global_category() {
        val result = resolveBackupCategoryId(
            categoryName = "Attractions",
            currency = "MYR",
            occurredAt = t0 + 15.days,
            tripWindows = tripWindows,
            categoriesByScope = categoriesByScope,
            globalCategoriesByName = globalCategoriesByName,
        )
        assertThat(result).isEqualTo(100L)
    }

    // (c) USD tx before the trip starts (outside the window) → global category 100L.
    @Test
    fun usd_tx_before_trip_start_falls_back_to_global() {
        val result = resolveBackupCategoryId(
            categoryName = "Attractions",
            currency = "USD",
            occurredAt = t0 - 1.days,
            tripWindows = tripWindows,
            categoriesByScope = categoriesByScope,
            globalCategoriesByName = globalCategoriesByName,
        )
        assertThat(result).isEqualTo(100L)
    }

    // (d) USD tx inside the window, name "Food" (no trip-scoped "Food") → global 101L.
    @Test
    fun usd_tx_inside_window_falls_back_to_global_when_no_trip_scoped_match() {
        val result = resolveBackupCategoryId(
            categoryName = "Food",
            currency = "USD",
            occurredAt = t0 + 15.days,
            tripWindows = tripWindows,
            categoriesByScope = categoriesByScope,
            globalCategoriesByName = globalCategoriesByName,
        )
        assertThat(result).isEqualTo(101L)
    }

    // (e) null categoryName → null.
    @Test
    fun null_category_name_returns_null() {
        val result = resolveBackupCategoryId(
            categoryName = null,
            currency = "USD",
            occurredAt = t0 + 15.days,
            tripWindows = tripWindows,
            categoriesByScope = categoriesByScope,
            globalCategoriesByName = globalCategoriesByName,
        )
        assertThat(result).isNull()
    }
}
