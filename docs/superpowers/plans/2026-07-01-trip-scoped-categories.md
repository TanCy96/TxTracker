# Trip-scoped Categories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each foreign-currency trip its own independent, manually-assigned category list (seeded from a travel template), invisible to Home and to other trips.

**Architecture:** Approach A — add a nullable `Category.tripId` foreign key (`null` = global/Home category, else trip-owned). All categories stay rows in the one `categories` table, so the existing `Transaction.categoryId` FK, category DAO, and management UI serve both kinds. Home/ingestion use global categories only; the Foreign tab and its edit picker use the current trip's categories.

**Tech Stack:** Kotlin, Room (SQLite), Hilt, Jetpack Compose, kotlinx-coroutines/-datetime/-serialization, JUnit4 + mockk + Truth (JVM unit tests), Room `MigrationTestHelper` (instrumented).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-01-trip-scoped-categories-design.md` (authoritative; this plan implements it).
- **No auto-categorization for trip categories.** Trip transactions are categorized manually only.
- **Home unchanged:** existing global categories keep `tripId = NULL`; Home's auto-categorization is untouched.
- **DB migrations are additive and non-destructive** in RELEASE (`fallbackToDestructiveMigration` is DEBUG-only). Every schema bump needs a real `Migration` in `di/DatabaseModule.kt` and its resulting schema must match the Room-generated schema (`exportSchema = true`).
- **Testing limits:** JVM unit tests (`./gradlew testDebugUnitTest`) and compile gates only. Instrumented/`androidTest` (Room migration tests) are written and compile-gated but **run by the user/CI**, never launched here.
- Commit style: `Area: imperative summary`, and every commit message ends with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Commit on the current branch; never create a branch.
- Category names are unique **within their scope** (unique among globals, or unique within one trip); the same name may repeat across trips and Home. Enforced in the repository (the old DB unique index on `name` is dropped).

---

## File Structure

**Data layer**
- `app/src/main/java/cy/txtracker/data/Entities.kt` — add `tripId` + FK to `Category`.
- `app/src/main/java/cy/txtracker/data/CategoryDao.kt` — scoped queries (`observeGlobal`, `observeForTrip`, `getAllGlobal`, `getForTrip`, `deleteForTrip`).
- `app/src/main/java/cy/txtracker/data/DefaultTripCategories.kt` — **new**: the travel template list + shared seed helper.
- `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` — scoped observers, seeding in `openTrip`, scope-aware name-uniqueness on add/rename, `setCategory` learn guard, non-MYR categoryId guard on promote.
- `app/src/main/java/cy/txtracker/di/DatabaseModule.kt` — `MIGRATION_15_16`, register it, bump nothing else.
- `app/src/main/java/cy/txtracker/data/TxDatabase.kt` — bump `version` to 16.

**Domain / ingestion**
- `app/src/main/java/cy/txtracker/domain/CategorizationEngine.kt` — keyword step reads global-only categories.
- `app/src/main/java/cy/txtracker/service/TxIngestor.kt` — skip auto-categorize for non-MYR.

**UI**
- `app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt` — categories come from the current trip.
- `app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt` — picker categories scoped to the transaction's trip; learn guard.
- `app/src/main/java/cy/txtracker/ui/settings/categories/TripCategoriesViewModel.kt` — **new**: trip-scoped variant of `CategoriesViewModel`.
- `app/src/main/java/cy/txtracker/ui/settings/categories/CategoriesScreen.kt` — parameterize to hide keyword/auto UI for trips (or a thin trip wrapper).
- `app/src/main/java/cy/txtracker/ui/foreign/ForeignRoute.kt` — "Manage categories" entry point.
- `app/src/main/java/cy/txtracker/ui/AppRoute.kt` — new `settings/trip-categories/{tripId}` route.

**Backup**
- `app/src/main/java/cy/txtracker/export/Backup.kt` — v12: `BackupCategory.tripKey`; scoped category resolution model.
- `app/src/main/java/cy/txtracker/export/BackupExporter.kt` — emit trip categories with a trip key.
- `app/src/main/java/cy/txtracker/export/BackupImporter.kt` — create trips first, then trip categories, then resolve transaction categories per scope.

---

## Phase 1 — Data foundation

### Task 1: Add `tripId` to `Category` + scoped DAO queries

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/Entities.kt` (the `Category` entity, currently lines 107–121)
- Modify: `app/src/main/java/cy/txtracker/data/CategoryDao.kt`
- Test: `app/src/test/java/cy/txtracker/data/CategoryScopeDaoTest.kt` (**new**) — NOTE: DAO queries need a real DB, so the *behavioral* proof of the queries lives in the migration/instrumented tests (Task 4) and the repository unit tests (Task 3). This task's unit test asserts the entity default only.

**Interfaces:**
- Produces: `Category(..., tripId: Long? = null)`; `CategoryDao.observeGlobal(): Flow<List<Category>>`, `observeForTrip(tripId: Long): Flow<List<Category>>`, `getAllGlobal(): List<Category>`, `getForTrip(tripId: Long): List<Category>`, `deleteForTrip(tripId: Long)`.

- [ ] **Step 1: Write the failing test** (`Category` defaults `tripId` to null so all existing construction sites remain global)

```kotlin
package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CategoryScopeDaoTest {
    @Test
    fun category_defaults_to_global_scope() {
        val c = Category(name = "Food", color = 1, isCustom = false, sortOrder = 0)
        assertThat(c.tripId).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.data.CategoryScopeDaoTest"`
Expected: FAIL to compile — `tripId` is not a member of `Category` yet.

- [ ] **Step 3: Add the column + FK to the entity**

Replace the `Category` entity in `Entities.kt` with:

```kotlin
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = TripWindow::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tripId")],
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
    val isCustom: Boolean,
    val sortOrder: Int,
    val keywordPattern: String? = null,
    /** null = global/Home category; else the id of the owning trip. */
    val tripId: Long? = null,
)
```

(Confirm `androidx.room.ForeignKey` and `androidx.room.Index` are imported in `Entities.kt` — they are already used by other entities in this file.)

- [ ] **Step 4: Add scoped queries to `CategoryDao`**

Add to `CategoryDao`:

```kotlin
@Query("SELECT * FROM categories WHERE tripId IS NULL ORDER BY sortOrder ASC, name ASC")
fun observeGlobal(): Flow<List<Category>>

@Query("SELECT * FROM categories WHERE tripId IS NULL ORDER BY sortOrder ASC, name ASC")
suspend fun getAllGlobal(): List<Category>

@Query("SELECT * FROM categories WHERE tripId = :tripId ORDER BY sortOrder ASC, name ASC")
fun observeForTrip(tripId: Long): Flow<List<Category>>

@Query("SELECT * FROM categories WHERE tripId = :tripId ORDER BY sortOrder ASC, name ASC")
suspend fun getForTrip(tripId: Long): List<Category>

@Query("DELETE FROM categories WHERE tripId = :tripId")
suspend fun deleteForTrip(tripId: Long)
```

Leave the existing `observeAll()`, `getAll()`, `insert`, `update`, `updateAll`, `delete`, `getById`, `count` untouched (still used by backup export + reorder).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.data.CategoryScopeDaoTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/cy/txtracker/data/Entities.kt \
        app/src/main/java/cy/txtracker/data/CategoryDao.kt \
        app/src/test/java/cy/txtracker/data/CategoryScopeDaoTest.kt
git commit -m "Categories: add nullable tripId + scoped DAO queries"
```
(append the Co-Authored-By trailer)

---

### Task 2: Travel template + shared seed helper

**Files:**
- Create: `app/src/main/java/cy/txtracker/data/DefaultTripCategories.kt`
- Test: `app/src/test/java/cy/txtracker/data/DefaultTripCategoriesTest.kt` (**new**)

**Interfaces:**
- Produces: `data class TripSeedCategory(val name: String, val color: Int)`; `object DefaultTripCategories { val template: List<TripSeedCategory> }`; `fun TxDatabase.Companion.seedTripCategories(db: SupportSQLiteDatabase, tripId: Long)` — used by the migration (Task 4). `openTrip` (Task 5) seeds via the DAO, iterating the same `template`.

- [ ] **Step 1: Write the failing test**

```kotlin
package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultTripCategoriesTest {
    @Test
    fun template_has_expected_travel_categories_in_order() {
        val names = DefaultTripCategories.template.map { it.name }
        assertThat(names).containsExactly(
            "Accommodation", "Food & Drink", "Transport", "Attractions",
            "Shopping", "Groceries", "Fees & Cash", "Other",
        ).inOrder()
    }

    @Test
    fun template_colors_are_distinct() {
        val colors = DefaultTripCategories.template.map { it.color }
        assertThat(colors).containsNoDuplicates()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.data.DefaultTripCategoriesTest"`
Expected: FAIL — `DefaultTripCategories` unresolved.

- [ ] **Step 3: Create `DefaultTripCategories.kt`**

```kotlin
package cy.txtracker.data

/** One row of the built-in travel template applied to every new trip. */
data class TripSeedCategory(val name: String, val color: Int)

/**
 * The travel category template seeded for each trip (on trip creation and, for
 * pre-existing trips, in migration 15->16). Distinct from Home's everyday
 * [DefaultCategories]; carries NO keyword patterns — trip categories are manual-only.
 */
object DefaultTripCategories {
    val template: List<TripSeedCategory> = listOf(
        TripSeedCategory("Accommodation", 0xFF5C6BC0.toInt()),
        TripSeedCategory("Food & Drink", 0xFFEF5350.toInt()),
        TripSeedCategory("Transport", 0xFF42A5F5.toInt()),
        TripSeedCategory("Attractions", 0xFFFFCA28.toInt()),
        TripSeedCategory("Shopping", 0xFFAB47BC.toInt()),
        TripSeedCategory("Groceries", 0xFF66BB6A.toInt()),
        TripSeedCategory("Fees & Cash", 0xFF8D6E63.toInt()),
        TripSeedCategory("Other", 0xFF78909C.toInt()),
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.data.DefaultTripCategoriesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/data/DefaultTripCategories.kt \
        app/src/test/java/cy/txtracker/data/DefaultTripCategoriesTest.kt
git commit -m "Categories: add built-in travel template for trips"
```

---

### Task 3: Repository scoped observers, seeding call, and scope-aware uniqueness

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt`
  - `observeAllCategories` (line 193), `openTrip` (line 1084), `addCategory` (1352), `updateCategory` (1354), `deleteCategory` (1367).
- Test: `app/src/test/java/cy/txtracker/data/TripCategoriesRepoTest.kt` (**new**, mockk — mirrors `PromotePoolEntryTest`/`BatchTransactionOpsTest` construction of `TransactionRepository`).

**Interfaces:**
- Produces (repository):
  - `fun observeGlobalCategories(): Flow<List<Category>>`
  - `fun observeCategoriesForTrip(tripId: Long): Flow<List<Category>>`
  - `suspend fun addCategoryInScope(name, color, keywordPattern, tripId): Long?` — returns null when the name already exists in that scope.
  - `suspend fun renameCategoryInScope(original: Category, newName, newColor, newKeywordPattern): Boolean` — false on scope collision.
  - `openTrip(...)` additionally seeds `DefaultTripCategories.template` with `tripId = newTripId`.
- Consumes: `CategoryDao.observeGlobal/observeForTrip/getAllGlobal/getForTrip`, `categoryDao.insert`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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

    @Test
    fun openTrip_seeds_template_categories_scoped_to_new_trip() = runTest {
        coEvery { tripWindowDao.insert(any()) } returns 55L
        val inserted = mutableListOf<Category>()
        coEvery { categoryDao.insert(capture(inserted)) } returns 1L

        val repo = makeRepo()
        val tripId = repo.openTrip("USD", now, null, now)

        assertThat(tripId).isEqualTo(55L)
        assertThat(inserted).hasSize(DefaultTripCategories.template.size)
        assertThat(inserted.map { it.name })
            .containsExactlyElementsIn(DefaultTripCategories.template.map { it.name })
        assertThat(inserted.all { it.tripId == 55L }).isTrue()
        assertThat(inserted.all { !it.isCustom }).isTrue()
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
```

Note: `database.withTransaction { }` on a relaxed `TxDatabase` mock runs the block — confirm by checking how `BatchTransactionOpsTest` drives `openTrip`-adjacent code; if `withTransaction` does not invoke the block under a relaxed mock, extract an `openTripBody(...)` (mirroring `promotePoolEntryBody`) and test that directly, exactly as `PromotePoolEntryTest` does.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.data.TripCategoriesRepoTest"`
Expected: FAIL — `addCategoryInScope`/`observeCategoriesForTrip` unresolved; `openTrip` doesn't seed.

- [ ] **Step 3: Implement repository changes**

Add near the existing category methods:

```kotlin
fun observeGlobalCategories(): Flow<List<Category>> = categoryDao.observeGlobal()

fun observeCategoriesForTrip(tripId: Long): Flow<List<Category>> =
    categoryDao.observeForTrip(tripId)

/**
 * Adds a category in [tripId]'s scope (null = global). Returns the new id, or null when a
 * category with the same name (case-insensitive) already exists in that scope. Replaces the
 * old reliance on the dropped unique name index.
 */
suspend fun addCategoryInScope(
    name: String,
    color: Int,
    keywordPattern: String?,
    tripId: Long?,
): Long? {
    val clean = name.trim()
    if (clean.isEmpty()) return null
    val scope = if (tripId == null) categoryDao.getAllGlobal() else categoryDao.getForTrip(tripId)
    if (scope.any { it.name.equals(clean, ignoreCase = true) }) return null
    val nextSort = (scope.maxOfOrNull { it.sortOrder } ?: -1) + 1
    return categoryDao.insert(
        Category(
            name = clean,
            color = color,
            isCustom = true,
            sortOrder = nextSort,
            keywordPattern = keywordPattern?.trim()?.takeIf { it.isNotEmpty() },
            tripId = tripId,
        ),
    )
}

/** Renames/recolors a category, enforcing scope uniqueness. Returns false on a name clash. */
suspend fun renameCategoryInScope(
    original: Category,
    newName: String,
    newColor: Int,
    newKeywordPattern: String?,
): Boolean {
    val clean = newName.trim()
    if (clean.isEmpty()) return false
    val scope = if (original.tripId == null) categoryDao.getAllGlobal()
    else categoryDao.getForTrip(original.tripId)
    if (scope.any { it.id != original.id && it.name.equals(clean, ignoreCase = true) }) return false
    categoryDao.update(
        original.copy(
            name = clean,
            color = newColor,
            keywordPattern = newKeywordPattern?.trim()?.takeIf { it.isNotEmpty() },
        ),
    )
    return true
}
```

In `openTrip`, after `transactionDao.clearCurrencyConfirmationForRange(...)` and before `tripId` is returned, seed the template:

```kotlin
DefaultTripCategories.template.forEachIndexed { index, seed ->
    categoryDao.insert(
        Category(
            name = seed.name,
            color = seed.color,
            isCustom = false,
            sortOrder = index,
            keywordPattern = null,
            tripId = tripId,
        ),
    )
}
```

Keep `addCategory`/`updateCategory` as-is for now (Task 8 migrates the global CategoriesViewModel onto the scope-aware methods; other callers unaffected).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.data.TripCategoriesRepoTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/data/TransactionRepository.kt \
        app/src/test/java/cy/txtracker/data/TripCategoriesRepoTest.kt
git commit -m "Categories: repo scoped observers, trip seeding, scope-aware uniqueness"
```

---

### Task 4: Migration 15 → 16 (+ instrumented migration test)

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/TxDatabase.kt` (`version = 16`; add `seedTripCategories` helper to the companion)
- Modify: `app/src/main/java/cy/txtracker/di/DatabaseModule.kt` (add `MIGRATION_15_16`, register in `.addMigrations(...)`)
- Test: `app/src/androidTest/java/cy/txtracker/data/MigrationV15ToV16Test.kt` (**new**, mirrors `MigrationV14ToV15Test`) — instrumented; compile-gated here, run by user/CI.

**Interfaces:**
- Consumes: `DefaultTripCategories.template` (Task 2).
- Produces: DB at v16 — `categories.tripId` column + FK + `index_categories_tripId`, old name-unique index dropped, existing categories `tripId = NULL`, existing trips seeded with template categories, non-MYR transactions' `categoryId` cleared.

- [ ] **Step 1: Write the failing (instrumented) migration test**

```kotlin
package cy.txtracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationV15ToV16Test {

    private val dbName = "migration-v15-v16-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate15To16_backfills_global_seeds_trips_and_clears_foreign_categories() {
        helper.createDatabase(dbName, 15).use { db ->
            // A trip and two categorized transactions: one MYR, one USD.
            db.execSQL(
                "INSERT INTO trip_windows(id, currency, startAt, endAt, createdAt) " +
                    "VALUES (1, 'USD', 1000, NULL, 1000)",
            )
            db.execSQL(
                "INSERT INTO categories(id, name, color, isCustom, sortOrder, keywordPattern) " +
                    "VALUES (10, 'Food', 1, 0, 0, NULL)",
            )
            db.execSQL(
                "INSERT INTO transactions(amountMinor, currency, merchantRaw, merchantNormalized, " +
                    "categoryId, occurredAt, timeBucket, sourceApp, direction, createdAt, " +
                    "notificationDedupeKey, needsVerification, needsCurrencyConfirmation, merchantUserEdited) " +
                    "VALUES (500,'MYR','A','A',10,2000,'AFTERNOON','p','OUT',3000,'k-myr',0,0,0)",
            )
            db.execSQL(
                "INSERT INTO transactions(amountMinor, currency, merchantRaw, merchantNormalized, " +
                    "categoryId, occurredAt, timeBucket, sourceApp, direction, createdAt, " +
                    "notificationDedupeKey, needsVerification, needsCurrencyConfirmation, merchantUserEdited) " +
                    "VALUES (900,'USD','B','B',10,2500,'AFTERNOON','p','OUT',3000,'k-usd',0,0,0)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        // Existing category kept, now global.
        db.query("SELECT tripId FROM categories WHERE id = 10").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
        }
        // Trip 1 seeded with the template.
        db.query("SELECT COUNT(*) FROM categories WHERE tripId = 1").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(DefaultTripCategories.template.size)
        }
        // MYR categoryId preserved; USD cleared.
        db.query("SELECT categoryId FROM transactions WHERE notificationDedupeKey = 'k-myr'").use { c ->
            c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(10)
        }
        db.query("SELECT categoryId FROM transactions WHERE notificationDedupeKey = 'k-usd'").use { c ->
            c.moveToFirst(); assertThat(c.isNull(0)).isTrue()
        }
    }
}
```

- [ ] **Step 2: Verify it fails to compile / would fail**

`MIGRATION_15_16` is unresolved and `TxDatabase` is still at version 15. (Do not run on device; compile-gate only: `./gradlew compileDebugAndroidTestKotlin`. Expected: FAIL — unresolved `MIGRATION_15_16`.)

- [ ] **Step 3: Add the seed helper to `TxDatabase` companion**

```kotlin
/** Seeds the travel template for one trip. Used by migration 15->16. */
fun seedTripCategories(db: SupportSQLiteDatabase, tripId: Long) {
    DefaultTripCategories.template.forEachIndexed { index, seed ->
        db.execSQL(
            "INSERT INTO categories (name, color, isCustom, sortOrder, keywordPattern, tripId) " +
                "VALUES (?, ?, 0, ?, NULL, ?)",
            arrayOf<Any?>(seed.name, seed.color, index, tripId),
        )
    }
}
```

Bump the class annotation: `version = 16`.

- [ ] **Step 4: Add `MIGRATION_15_16` in `DatabaseModule.kt`**

```kotlin
/**
 * v16 adds per-trip categories. The `categories` table gains a nullable `tripId` FK to
 * `trip_windows(id)` (ON DELETE CASCADE). Because SQLite cannot add a foreign key via
 * ALTER, the table is recreated. The old unique index on `name` is intentionally NOT
 * recreated — name uniqueness is now enforced per-scope in the repository. Existing
 * categories become global (tripId = NULL); every existing trip is seeded with the travel
 * template; and categoryId is cleared on all non-MYR transactions (they will be
 * re-categorized manually against their trip's categories).
 */
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `categories_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `color` INTEGER NOT NULL,
                `isCustom` INTEGER NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `keywordPattern` TEXT DEFAULT NULL,
                `tripId` INTEGER,
                FOREIGN KEY(`tripId`) REFERENCES `trip_windows`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `categories_new` (id, name, color, isCustom, sortOrder, keywordPattern, tripId)
            SELECT id, name, color, isCustom, sortOrder, keywordPattern, NULL FROM `categories`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `categories`")
        db.execSQL("ALTER TABLE `categories_new` RENAME TO `categories`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_tripId` ON `categories`(`tripId`)")

        // Seed template categories for every existing trip.
        val tripIds = mutableListOf<Long>()
        db.query("SELECT id FROM `trip_windows`").use { c ->
            while (c.moveToNext()) tripIds.add(c.getLong(0))
        }
        tripIds.forEach { TxDatabase.seedTripCategories(db, it) }

        // Existing foreign categorizations pointed at Home categories; clear them so trips
        // start clean against their travel template (see design doc, "Existing data").
        db.execSQL("UPDATE `transactions` SET `categoryId` = NULL WHERE `currency` != 'MYR'")
    }
}
```

Register it in the builder's `.addMigrations(...)` list right after `MIGRATION_14_15`:

```kotlin
                MIGRATION_14_15,
                MIGRATION_15_16,
```

- [ ] **Step 5: Compile-gate the migration + schema**

Run: `./gradlew compileDebugAndroidTestKotlin assembleDebug`
Expected: BUILD SUCCESSFUL. Room regenerates schema JSON for v16; if Room reports a schema mismatch at build time, reconcile the `categories_new` DDL with the generated `16.json` (column order/affinity/index name) until it validates. **Ask the user to run** `./gradlew connectedDebugAndroidTest --tests "*MigrationV15ToV16Test"` on a device/emulator (per the no-device-test policy) and report results.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/cy/txtracker/data/TxDatabase.kt \
        app/src/main/java/cy/txtracker/di/DatabaseModule.kt \
        app/src/androidTest/java/cy/txtracker/data/MigrationV15ToV16Test.kt \
        app/schemas
git commit -m "DB: migration 15->16 for per-trip categories"
```

---

## Phase 2 — Manual-only enforcement

### Task 5: Categorization engine + ingestion stay global/MYR-only

**Files:**
- Modify: `app/src/main/java/cy/txtracker/domain/CategorizationEngine.kt` (line 45: `getAll()` → `getAllGlobal()`)
- Modify: `app/src/main/java/cy/txtracker/service/TxIngestor.kt` (line 50)
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` — `promotePoolEntryBody` (line ~787) and `recategorizeNullRows` (line ~1256): don't put a category on non-MYR rows.
- Test: extend `app/src/test/java/cy/txtracker/domain/CategorizationEngineTest.kt` (JVM, mockk).

**Interfaces:**
- Consumes: `CategoryDao.getAllGlobal()` (Task 1).

- [ ] **Step 1: Write the failing test** (engine keyword step must use global categories only)

Add to `app/src/test/java/cy/txtracker/domain/CategorizationEngineTest.kt`:

```kotlin
@Test
fun keyword_step_reads_global_categories_only() = runTest {
    coEvery { merchantDao.get(any()) } returns null
    coEvery { merchantDao.getAllOrderedByRecency() } returns emptyList()
    coEvery { categoryDao.getAllGlobal() } returns listOf(
        cat(id = 1L, name = "Coffee", sortOrder = 0, pattern = "STARBUCKS"),
    )
    assertThat(engine.categorize("STARBUCKS KLCC")).isEqualTo(1L)
    io.mockk.coVerify(exactly = 0) { categoryDao.getAll() }
}
```

(Uses this file's existing `cat(...)`, `engine`, `categoryDao`, `merchantDao` helpers.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.domain.CategorizationEngineTest"`
Expected: FAIL — engine still calls `getAll()`; `getAllGlobal()` stub unused, `getAll` verified.

- [ ] **Step 3: Implement**

In `CategorizationEngine.kt` line 45 change:
```kotlin
val categories = categoryDao.getAllGlobal() // global only; trip categories are manual
```

In `TxIngestor.kt` line 50:
```kotlin
val categoryId =
    if (parsed.currency == "MYR") categorizationEngine.categorize(merchantNormalized) else null
```

In `TransactionRepository.promotePoolEntryBody` — where the `Transaction` is built (categoryId = edit.categoryId), guard non-MYR:
```kotlin
categoryId = if (edit.currency == "MYR") edit.categoryId else null,
```

In `recategorizeNullRows` (line ~1255 loop), skip non-MYR rows:
```kotlin
for (row in rows) {
    if (row.currency != "MYR") continue
    val newCategoryId = categorizationEngine.categorize(row.merchantNormalized) ?: continue
    transactionDao.updateCategory(row.id, newCategoryId)
    updated++
}
```

- [ ] **Step 4: Run tests to verify they pass (and no regression)**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.domain.CategorizationEngineTest" --tests "cy.txtracker.data.*"`
Expected: PASS. If `CategoryBackfillTest` mocks `categoryDao.getAll()` for the engine, update it to `getAllGlobal()`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/domain/CategorizationEngine.kt \
        app/src/main/java/cy/txtracker/service/TxIngestor.kt \
        app/src/main/java/cy/txtracker/data/TransactionRepository.kt \
        app/src/test/java/cy/txtracker/domain/CategorizationEngineTest.kt
git commit -m "Categorization: keep auto-assign global/MYR-only for trip separation"
```

---

## Phase 3 — Read-side UI (Foreign tab + edit picker)

### Task 6: Foreign tab renders the current trip's categories

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt` (line 52: the `observeAllCategories()` source)
- Test: `app/src/test/java/cy/txtracker/ui/foreign/ForeignCategoriesTest.kt` (**new**, mirrors `HomeSelectionTest`'s repository-mock + flow-collection style)

**Interfaces:**
- Consumes: `repository.observeCategoriesForTrip(tripId)` (Task 3).

- [ ] **Step 1: Write the failing test**

```kotlin
package cy.txtracker.ui.foreign

// Mirror HomeSelectionTest setup: mock TransactionRepository, stub observeAllTrips()/
// observeTransactionsForTrip()/observeMerchantNotes(), and stub
// observeCategoriesForTrip(<tripId>) to return a known trip-only list. Collect state,
// assert ForeignUiState.Loaded.categories == the trip's categories (and that
// observeAllCategories() is never used).
```

Write it concretely against `HomeSelectionTest.kt`'s patterns (same module): stub `repository.observeAllTrips()` to emit one `TripWindow(id = 7, currency = "USD", startAt = t0, endAt = null, createdAt = t0)`, `repository.observeCategoriesForTrip(7)` to emit `listOf(Category(id = 1, name = "Attractions", color = 1, isCustom = false, sortOrder = 0, tripId = 7))`, `observeTransactionsForTrip(...)`/`observeMerchantNotes()` to `flowOf(emptyList())`; assert the loaded state's `categories` equals that list.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.ui.foreign.ForeignCategoriesTest"`
Expected: FAIL — VM still combines `observeAllCategories()`.

- [ ] **Step 3: Implement**

In `ForeignViewModel`, the categories flow depends on the selected trip, so move it inside `flatMapLatest` where `trip` is known. Replace the top-level `repository.observeAllCategories()` in the outer `combine` with the per-trip observer inside the branch:

```kotlin
val state: StateFlow<ForeignUiState> =
    combine(repository.observeAllTrips(), _selectedTripIndex, _filter) { trips, idx, filter ->
        Triple(trips.sortedByDescending { it.startAt }, idx, filter)
    }.flatMapLatest { (trips, requestedIdx, filter) ->
        if (trips.isEmpty()) {
            MutableStateFlow(ForeignUiState.NoTrips)
        } else {
            val idx = requestedIdx.coerceIn(0, trips.lastIndex)
            if (idx != requestedIdx) _selectedTripIndex.value = idx
            val trip = trips[idx]
            val end = trip.endAtExclusive
            combine(
                repository.observeTransactionsForTrip(trip.currency, trip.startAt, end),
                repository.observeMerchantNotes(),
                repository.observeCategoriesForTrip(trip.id),
            ) { txs, notes, cats ->
                buildLoaded(trips, idx, trip, cats, txs, notes, filter)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ForeignUiState.Loading)
```

Remove the now-unused `Quad` helper if nothing else uses it. `buildLoaded` is unchanged (already takes `categories`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.ui.foreign.ForeignCategoriesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt \
        app/src/test/java/cy/txtracker/ui/foreign/ForeignCategoriesTest.kt
git commit -m "Foreign: render the selected trip's own categories"
```

---

### Task 7: Edit picker scoped to the transaction's trip (+ learn guard)

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt` (`load`, line 62)
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` — `setCategory(...)` learn guard for trip categories.
- Test: `app/src/test/java/cy/txtracker/data/SetCategoryLearnGuardTest.kt` (**new**, mockk)

**Interfaces:**
- Consumes: `repository.findActiveTrip(currency, at)`, `repository.observeCategoriesForTrip(tripId)`, `repository.observeGlobalCategories()`.

- [ ] **Step 1: Write the failing test** (setting a trip category must not learn a global merchant→category mapping)

Inspect `TransactionRepository.setCategory` first to mirror its exact dependencies. The test asserts: when the target category has `tripId != null`, `merchantMappingDao.upsert(...)` (or whatever learn call it uses) is NOT invoked even with `learnMapping = true`; when `tripId == null`, it IS invoked. Construct the repo via the shared mockk `makeRepo()` pattern (copy from `TripCategoriesRepoTest`), stub `categoryDao.getById(categoryId)` to return a trip category, and `coVerify(exactly = 0)` on the mapping upsert.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.data.SetCategoryLearnGuardTest"`
Expected: FAIL — `setCategory` learns unconditionally.

- [ ] **Step 3: Implement the guard**

In `TransactionRepository.setCategory`, before learning the mapping, load the category and skip learning when it is trip-scoped:

```kotlin
val isTripCategory = categoryId?.let { categoryDao.getById(it)?.tripId != null } ?: false
val doLearn = learnMapping && !isTripCategory
```
Use `doLearn` where `learnMapping` currently gates the mapping upsert. (Match the exact variable/param names in the existing method.)

- [ ] **Step 4: Scope the edit picker to the transaction's trip**

In `EditTransactionViewModel.load`, replace the `categories = repository.observeAllCategories().first()` line with a trip-aware resolution:

```kotlin
val categories = if (tx.currency == "MYR") {
    repository.observeGlobalCategories().first()
} else {
    val trip = repository.findActiveTrip(tx.currency, tx.occurredAt)
    if (trip != null) repository.observeCategoriesForTrip(trip.id).first()
    else emptyList() // parked foreign row: no trip yet -> nothing to assign
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.data.SetCategoryLearnGuardTest"`
Expected: PASS. Then `./gradlew compileDebugKotlin` (the VM change is UI-adjacent, compile-gated).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt \
        app/src/main/java/cy/txtracker/data/TransactionRepository.kt \
        app/src/test/java/cy/txtracker/data/SetCategoryLearnGuardTest.kt
git commit -m "Edit: scope category picker to the transaction's trip; guard learning"
```

---

## Phase 4 — Per-trip category management UI

### Task 8: Trip-scoped categories screen + navigation

**Files:**
- Create: `app/src/main/java/cy/txtracker/ui/settings/categories/TripCategoriesViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/settings/categories/CategoriesScreen.kt` — add a `showKeywordUi: Boolean = true` parameter and an injectable set of callbacks so the same composable serves both global and trip modes; when `false`, hide the "learned/auto" chips and the keyword-pattern field. Alternatively add a thin `TripCategoriesScreen` wrapper that calls the shared body with `showKeywordUi = false`.
- Modify: `app/src/main/java/cy/txtracker/ui/AppRoute.kt` — add `const val SETTINGS_TRIP_CATEGORIES = "settings/trip-categories/{tripId}"` to `Routes`, a `composable(...)` with a `tripId` `navArgument` (LongType) that renders the trip screen, and pass `onBack = { nav.popBackStack() }`.
- Modify: `app/src/main/java/cy/txtracker/ui/foreign/ForeignRoute.kt` — add a "Manage categories" action (e.g. in the trip's overflow/top bar) that calls a new `onManageCategories: (tripId: Long) -> Unit` param; wire it in `AppRoute` to `nav.navigate("settings/trip-categories/${tripId}")`.
- Test: `app/src/test/java/cy/txtracker/ui/settings/categories/TripCategoriesViewModelTest.kt` (**new**, mockk)

**Interfaces:**
- Produces: `TripCategoriesViewModel(SavedStateHandle, TransactionRepository)` reading `tripId` from the nav arg; exposes `categories: StateFlow<List<Category>>` = `repository.observeCategoriesForTrip(tripId)`, and `add(name, color)`, `rename(original, name, color)`, `delete(category)`, `reorder(list)` that call the scope-aware repository methods (`addCategoryInScope(..., tripId)`, `renameCategoryInScope(...)`, `deleteCategory`, `reorderCategories`).
- Consumes: Task 3 repository methods.

- [ ] **Step 1: Write the failing test**

```kotlin
package cy.txtracker.ui.settings.categories

// Construct TripCategoriesViewModel with a SavedStateHandle carrying tripId = 7 and a
// mockk TransactionRepository. Assert:
//  - categories flow proxies repository.observeCategoriesForTrip(7)
//  - add("Ramen", color) calls repository.addCategoryInScope("Ramen", color, null, 7)
// Use runTest + Dispatchers rules matching CategoryFilterSnapBackTest / existing VM tests.
```

Write concretely mirroring an existing ViewModel unit test in `app/src/test/java/cy/txtracker/ui/` for coroutine/dispatcher setup.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.ui.settings.categories.TripCategoriesViewModelTest"`
Expected: FAIL — `TripCategoriesViewModel` unresolved.

- [ ] **Step 3: Implement `TripCategoriesViewModel`**

Mirror `CategoriesViewModel` (same file dir), but: read `tripId` from `SavedStateHandle`; source `categories` from `observeCategoriesForTrip(tripId)`; drop the `categoryCounts`/merchant-mapping/recent-transaction machinery (not shown for trips); `add`/`rename` call the scope-aware repository methods with `tripId`.

```kotlin
@HiltViewModel
class TripCategoriesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TransactionRepository,
) : ViewModel() {
    private val tripId: Long = checkNotNull(savedStateHandle["tripId"])

    val categories: StateFlow<List<Category>> = repository.observeCategoriesForTrip(tripId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun add(name: String, color: Int) {
        viewModelScope.launch { repository.addCategoryInScope(name, color, keywordPattern = null, tripId = tripId) }
    }
    fun rename(original: Category, name: String, color: Int) {
        viewModelScope.launch { repository.renameCategoryInScope(original, name, color, newKeywordPattern = null) }
    }
    fun delete(category: Category) { viewModelScope.launch { repository.deleteCategory(category) } }
    fun reorder(ordered: List<Category>) { viewModelScope.launch { repository.reorderCategories(ordered) } }
}
```

- [ ] **Step 4: Wire the screen + navigation**

- Parameterize `CategoriesScreen` (add `showKeywordUi: Boolean = true`) or add a `TripCategoriesScreen(onBack)` that uses `hiltViewModel<TripCategoriesViewModel>()` and reuses the list/add/edit/reorder composables with keyword UI hidden.
- In `AppRoute.kt`: add the route constant, then
```kotlin
composable(
    route = Routes.SETTINGS_TRIP_CATEGORIES,
    arguments = listOf(navArgument("tripId") { type = NavType.LongType }),
) { TripCategoriesScreen(onBack = { nav.popBackStack() }) }
```
- In `ForeignRoute.kt`: add `onManageCategories: (Long) -> Unit`, surface a "Manage categories" action for the current `trip.tripId`; in `AppRoute`'s `composable(Routes.FOREIGN)` pass `onManageCategories = { tripId -> nav.navigate("settings/trip-categories/$tripId") }`.

- [ ] **Step 5: Run test + compile-gate the UI**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.ui.settings.categories.TripCategoriesViewModelTest"` → PASS
Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/settings/categories/TripCategoriesViewModel.kt \
        app/src/main/java/cy/txtracker/ui/settings/categories/CategoriesScreen.kt \
        app/src/main/java/cy/txtracker/ui/AppRoute.kt \
        app/src/main/java/cy/txtracker/ui/foreign/ForeignRoute.kt \
        app/src/test/java/cy/txtracker/ui/settings/categories/TripCategoriesViewModelTest.kt
git commit -m "Foreign: per-trip category management screen + navigation"
```

---

### Task 9: Point the global CategoriesViewModel/Home at global-only categories

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/settings/categories/CategoriesViewModel.kt` (line 27) — `observeAllCategories()` → `observeGlobalCategories()`; route `add`/`editCategory` through `addCategoryInScope(name, color, keywordPattern, tripId = null)` / `renameCategoryInScope(...)` so Home add/rename share the scope-aware uniqueness.
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt` — wherever it reads categories for the list/breakdown, switch `observeAllCategories()` → `observeGlobalCategories()`.
- Modify: `app/src/main/java/cy/txtracker/ui/settings/capture/PoolViewModel.kt` (line 49) — promote sheet shows global categories: `observeAllCategories()` → `observeGlobalCategories()`.
- Test: extend `app/src/test/java/cy/txtracker/ui/home/HomeSelectionTest.kt` (or a small new test) asserting Home reads `observeGlobalCategories()`.

**Interfaces:**
- Consumes: `observeGlobalCategories()`, `addCategoryInScope`, `renameCategoryInScope` (Task 3).

- [ ] **Step 1: Write the failing test**

Add a test that stubs `repository.observeGlobalCategories()` (returns a known list) and asserts the relevant Home/Categories VM surfaces exactly that list; if the VM still calls `observeAllCategories()`, the stub is unused and the assertion fails.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.ui.home.HomeSelectionTest"`
Expected: FAIL.

- [ ] **Step 3: Implement the swaps** (as listed in Files). Keep `repository.observeAllCategories()` available for any remaining all-scope consumer, or delete it if now unused (grep first: `grep -rn "observeAllCategories" app/src/main`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (full suite — this is a cross-cutting swap; catch any missed call site).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/settings/categories/CategoriesViewModel.kt \
        app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt \
        app/src/main/java/cy/txtracker/ui/settings/capture/PoolViewModel.kt \
        app/src/test/java/cy/txtracker/ui/home/HomeSelectionTest.kt
git commit -m "Categories: Home/pool/global manager read global-only categories"
```

---

## Phase 5 — Backup / restore

### Task 10: Backup format v12 — trip-scoped categories round-trip

**Files:**
- Modify: `app/src/main/java/cy/txtracker/export/Backup.kt` — `CURRENT_VERSION = 12`; add `val tripKey: String? = null` to `BackupCategory` (null = global; else `"<currency>|<startAtEpochMs>"` identifying the owning trip via its `BackupTripWindow`). Document v12 in the version history.
- Modify: `app/src/main/java/cy/txtracker/export/BackupExporter.kt` — emit every category (global + trip) with its `tripKey` (look up the owning trip's currency+startAt); for each `BackupTransaction`, keep `categoryName` but rely on the transaction's currency+occurredAt to resolve scope on import.
- Modify: `app/src/main/java/cy/txtracker/export/BackupImporter.kt` — order of restore: trips → per-trip categories (create against the newly-created trip id) + global categories → transactions (resolve `categoryName` within the transaction's scope: global for MYR/no-trip, else the covering trip's category set).
- Test: extend `app/src/test/java/cy/txtracker/export/BackupSerializationTest.kt` and `BackupImporterTest.kt` (JVM).

**Interfaces:**
- Consumes: `getAllCategoriesOnce()` (all scopes), `tripWindowDao`, Task 3 scoped methods.
- Produces: `BackupCategory.tripKey`; import correctly re-associates trip categories.

- [ ] **Step 1: Write the failing round-trip test**

In `BackupSerializationTest`/`BackupImporterTest` add: a backup containing one trip (`USD`, startAt = t0), a global category "Food", a trip category "Attractions" (`tripKey = "USD|<t0ms>"`), and a USD transaction categorized "Attractions". Serialize → deserialize → import into a fresh in-memory-equivalent (follow the existing test's repository/dao mocks or in-memory setup). Assert the imported USD transaction's category resolves to the trip's "Attractions" (not a global), and that a same-named global category would not collide.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.export.*"`
Expected: FAIL — `tripKey` unresolved / importer ignores scope.

- [ ] **Step 3: Implement v12** (model field, exporter emission, importer scoped resolution as described in Files). Keep older backups (v5–v11) parseable: `tripKey` defaults null, so pre-v12 categories import as global exactly as today.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.export.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/export/Backup.kt \
        app/src/main/java/cy/txtracker/export/BackupExporter.kt \
        app/src/main/java/cy/txtracker/export/BackupImporter.kt \
        app/src/test/java/cy/txtracker/export/BackupSerializationTest.kt \
        app/src/test/java/cy/txtracker/export/BackupImporterTest.kt
git commit -m "Backup: v12 round-trips per-trip categories"
```

---

## Phase 6 — Final verification

### Task 11: Full-suite gate + manual on-device checklist

- [ ] **Step 1: Run the full JVM unit-test suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 2: Compile everything incl. instrumented sources**

Run: `./gradlew assembleDebug compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Hand the user the on-device checklist** (per the no-device-test policy — do not run these here):
  - Run `MigrationV15ToV16Test` on a device/emulator.
  - Upgrade an existing install: existing Home categories intact; existing trips now show the travel template; foreign transactions are uncategorized.
  - Start a new trip → it seeds the template; add/rename/delete/reorder its categories; the same name is accepted in a different trip and rejected within the same trip.
  - Categorize a foreign transaction from the Foreign edit picker; confirm no Home merchant→category mapping is created (check Settings → Merchant mappings).
  - Export a backup, wipe, restore → trip categories reattach to their trips and foreign transactions resolve to trip categories.

- [ ] **Step 4: Final commit (if any doc/checklist changes)** and hand off.

---

## Self-review notes (author)

- **Spec coverage:** data model + migration (Tasks 1,4), app-level uniqueness (Task 3), template seeding on openTrip + migration backfill (Tasks 2,3,4), manual-only/global-only categorization (Task 5), Foreign display + edit picker + parked-row edge case (Tasks 6,7), per-trip management UI + nav (Task 8), Home/pool global-only (Task 9), existing-foreign clear on migration (Task 4), backup round-trip (Task 10). All spec sections map to a task.
- **Cross-task type consistency:** `addCategoryInScope(name, color, keywordPattern, tripId)`, `renameCategoryInScope(original, newName, newColor, newKeywordPattern)`, `observeGlobalCategories()`, `observeCategoriesForTrip(tripId)`, `CategoryDao.getAllGlobal()/getForTrip(tripId)`, `seedTripCategories(db, tripId)`, `DefaultTripCategories.template`, `BackupCategory.tripKey` — names used identically across tasks.
- **Known instrumented-only work (run by user/CI):** `MigrationV15ToV16Test`; the on-device checklist in Task 11.
