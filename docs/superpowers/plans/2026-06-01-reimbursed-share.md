# Reimbursed-by-others Share — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Commit policy (project convention):** The user runs `git commit` themselves. Treat the "Commit" step at the end of each task as a **checkpoint to hand back to the user** — show the staged diff and the suggested message, but do NOT run `git commit` automatically.

**Goal:** Let any OUT transaction record a portion reimbursed by other people, reducing its effective spend without changing the original amount — no pool, no deposits (unlike SL Debit).

**Architecture:** A single nullable `Transaction.reimbursedMinor` column, subtracted wherever a net spend is computed (Home totals SQL, Insights `chartAmountMinor`, Foreign trip totals, CSV cells). The original `amountMinor` is never mutated. Edit/Add-Manual sheets get a "Reimbursed by others" section; rows render a three-number net display. Built on `main` (DB v11→v12, backup v8→v9); `feature/share-debit` inherits it via the documented `main → feature/share-debit` merge.

**Tech Stack:** Kotlin, Jetpack Compose, Room, kotlinx.serialization, JUnit + Truth + Turbine.

**Spec:** `docs/superpowers/specs/2026-06-01-reimbursed-share-design.md`

**Test gates (run after each task):**
```
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
```
Migration tests and DAO tests live in `androidTest` (instrumented) — they are **run on-device by the user**, never via `connectedDebugAndroidTest` here. CI/gates compile them only.

---

### Task 1: Domain helper `isValidReimbursedMinor`

**Files:**
- Create: `app/src/main/java/cy/txtracker/domain/Share.kt`
- Test: `app/src/test/java/cy/txtracker/domain/ShareTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package cy.txtracker.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareTest {

    @Test fun zero_is_invalid() {
        assertThat(isValidReimbursedMinor(0, 10000)).isFalse()
    }

    @Test fun one_is_valid() {
        assertThat(isValidReimbursedMinor(1, 10000)).isTrue()
    }

    @Test fun equal_to_amount_is_valid() {
        assertThat(isValidReimbursedMinor(10000, 10000)).isTrue()
    }

    @Test fun greater_than_amount_is_invalid() {
        assertThat(isValidReimbursedMinor(10001, 10000)).isFalse()
    }

    @Test fun negative_is_invalid() {
        assertThat(isValidReimbursedMinor(-1, 10000)).isFalse()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.domain.ShareTest"`
Expected: FAIL — `isValidReimbursedMinor` is unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package cy.txtracker.domain

/**
 * Pure helpers for the reimbursed-by-others share. No Android / Room dependencies so they
 * are directly JVM-unit-testable. See docs/superpowers/specs/2026-06-01-reimbursed-share-design.md.
 */

/**
 * A reimbursed amount is valid (the toggle may be saved) when it is in `(0, amountMinor]` —
 * i.e. `1..amountMinor`. The original `amountMinor` is never reduced; `reimbursedMinor` is the
 * portion others returned, subtracted only when computing net spend.
 */
fun isValidReimbursedMinor(reimbursedMinor: Long, amountMinor: Long): Boolean =
    reimbursedMinor in 1L..amountMinor
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.domain.ShareTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit (checkpoint — hand to user)**

```bash
git add app/src/main/java/cy/txtracker/domain/Share.kt app/src/test/java/cy/txtracker/domain/ShareTest.kt
git commit -m "Reimbursed share: domain validation helper (TDD)"
```

---

### Task 2: Entity column + DB v12 migration

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/Entities.kt:89` (after `fundingSourceId`)
- Modify: `app/src/main/java/cy/txtracker/data/TxDatabase.kt:9` (`version = 11` → `12`)
- Modify: `app/src/main/java/cy/txtracker/di/DatabaseModule.kt` (add + register `MIGRATION_11_12`)
- Generated: `app/schemas/cy.txtracker.data.TxDatabase/12.json` (emitted by the compile step)
- Test: `app/src/androidTest/java/cy/txtracker/data/MigrationV11ToV12Test.kt`

- [ ] **Step 1: Add the column to the entity**

In `Entities.kt`, the `Transaction` data class currently ends with `val fundingSourceId: Long? = null,` (line 89). Add after it (still inside the constructor):

```kotlin
    val fundingSourceId: Long? = null,
    /**
     * The portion of this transaction that others have reimbursed, in minor units.
     * null = not reimbursed. The original `amountMinor` is never reduced; this is subtracted
     * only when computing net spend (Home totals, Insights charts, Foreign totals, CSV).
     * Invariant (enforced by repository/UI, not the schema): 0 < reimbursedMinor <= amountMinor.
     * Only ever set on direction = OUT rows. Any currency. Independent of SL Debit.
     */
    val reimbursedMinor: Long? = null,
```

- [ ] **Step 2: Bump the DB version**

In `TxDatabase.kt`, change `version = 11,` to `version = 12,`.

- [ ] **Step 3: Add and register the migration**

In `DatabaseModule.kt`, add to the `.addMigrations(...)` list (after `MIGRATION_10_11,` near line 93):

```kotlin
                MIGRATION_10_11,
                MIGRATION_11_12,
```

Then add the migration object next to the other `private val MIGRATION_X_Y` declarations (after `MIGRATION_10_11`, ~line 448):

```kotlin
/**
 * v12 adds the reimbursed-by-others share: a nullable `reimbursedMinor` column on
 * `transactions`. Existing rows keep reimbursedMinor = NULL (not reimbursed). No backfill,
 * no new tables. See docs/superpowers/specs/2026-06-01-reimbursed-share-design.md.
 */
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `reimbursedMinor` INTEGER DEFAULT NULL")
    }
}
```

(The `Migration` and `SupportSQLiteDatabase` imports are already present — existing migrations use them.)

- [ ] **Step 4: Compile to generate the v12 schema JSON**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, and a new `app/schemas/cy.txtracker.data.TxDatabase/12.json` is written (Room's schema export). It must be committed so `MigrationTestHelper` can validate against it.

- [ ] **Step 5: Write the migration test (instrumented — user runs on device)**

Create `app/src/androidTest/java/cy/txtracker/data/MigrationV11ToV12Test.kt`:

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
class MigrationV11ToV12Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrates_v11_to_v12_adds_reimbursedMinor_column() {
        helper.createDatabase(DB_NAME, 11).apply { close() }

        val db = helper.runMigrationsAndValidate(DB_NAME, 12, true)

        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(`transactions`)").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) columns.add(c.getString(nameIdx))
        }
        assertThat(columns).contains("reimbursedMinor")
    }

    companion object { private const val DB_NAME = "migration-v11-v12-test.db" }
}
```

- [ ] **Step 6: Verify the compile gates pass**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL. (The migration test itself is executed on-device by the user.)

- [ ] **Step 7: Commit (checkpoint — hand to user)**

```bash
git add app/src/main/java/cy/txtracker/data/Entities.kt \
        app/src/main/java/cy/txtracker/data/TxDatabase.kt \
        app/src/main/java/cy/txtracker/di/DatabaseModule.kt \
        app/schemas/cy.txtracker.data.TxDatabase/12.json \
        app/src/androidTest/java/cy/txtracker/data/MigrationV11ToV12Test.kt
git commit -m "Reimbursed share: reimbursedMinor column + v11->v12 migration"
```

---

### Task 3: Net math — DAO, repository, manual insert, Foreign totals

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/TransactionDao.kt:79-101` (two net queries) + add `updateReimbursed`
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` (wrapper + `addManualTransaction` param)
- Modify: `app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt:102,128` (net the trip total + breakdown)
- Test: `app/src/androidTest/java/cy/txtracker/data/TransactionDaoTest.kt` (add net test — user runs on device)

- [ ] **Step 1: Net the two Home aggregation queries**

In `TransactionDao.kt`, change `observeCategoryTotalsBetween`'s SELECT from:
```sql
SELECT categoryId, SUM(amountMinor) AS totalMinor
```
to:
```sql
SELECT categoryId, SUM(amountMinor - COALESCE(reimbursedMinor, 0)) AS totalMinor
```

And `observeTotalBetween`'s SELECT from:
```sql
SELECT COALESCE(SUM(amountMinor), 0)
```
to:
```sql
SELECT COALESCE(SUM(amountMinor - COALESCE(reimbursedMinor, 0)), 0)
```

(Leave the `WHERE ... direction = 'OUT' AND currency = 'MYR'` clauses unchanged.)

- [ ] **Step 2: Add the `updateReimbursed` DAO write**

Next to `updateFundingSource` in `TransactionDao.kt`, add:

```kotlin
@Query("UPDATE transactions SET reimbursedMinor = :reimbursedMinor WHERE id = :id")
suspend fun updateReimbursed(id: Long, reimbursedMinor: Long?)
```

- [ ] **Step 3: Add the repository wrapper**

In `TransactionRepository.kt`, next to `setTransactionFundingSource` (line 368), add:

```kotlin
suspend fun setTransactionReimbursed(txId: Long, reimbursedMinor: Long?) {
    transactionDao.updateReimbursed(txId, reimbursedMinor)
}
```

- [ ] **Step 4: Thread `reimbursedMinor` through manual insert**

In `addManualTransaction` (line 550), add a parameter (defaulted so existing callers are unaffected) and set it on the `Transaction`:

```kotlin
    suspend fun addManualTransaction(
        amountMinor: Long,
        merchantRaw: String,
        categoryId: Long?,
        description: String?,
        occurredAt: Instant,
        currency: String = "MYR",
        fundingSourceId: Long? = null,
        reimbursedMinor: Long? = null,
        now: Instant = Clock.System.now(),
    ): Long? {
```
and inside the `Transaction(...)` it builds, add the field (next to `fundingSourceId = fundingSourceId,`):
```kotlin
            fundingSourceId = fundingSourceId,
            reimbursedMinor = reimbursedMinor,
```

- [ ] **Step 5: Net the Foreign trip total and breakdown**

In `ForeignViewModel.kt`, line 102 currently sums `rows.sumOf { it.amountMinor }` for the category breakdown, and line 128 sums `.sumOf { it.amountMinor }` for the trip total. Change both to net the reimbursed portion:

```kotlin
// line ~102
CategoryBreakdownEntry(category = categoryId?.let(byId::get), totalMinor = rows.sumOf { it.amountMinor - (it.reimbursedMinor ?: 0L) })
```
```kotlin
// line ~128
.sumOf { it.amountMinor - (it.reimbursedMinor ?: 0L) }
```

- [ ] **Step 6: Add the DAO net test (instrumented — user runs on device)**

In `TransactionDaoTest.kt`, add after `observeTotalBetween_sums_only_OUT_in_window` (line 82):

```kotlin
    @Test
    fun observeTotalBetween_nets_out_reimbursed_portion() = runTest {
        val start = Instant.parse("2026-05-01T00:00:00Z")
        val end = Instant.parse("2026-06-01T00:00:00Z")

        val id = dbRule.transactionDao.insert(
            txAt(Instant.parse("2026-05-09T12:30:00Z"), amountMinor = 10000, dedupeKey = "r"),
        )
        dbRule.transactionDao.updateReimbursed(id, 4000)

        dbRule.transactionDao.observeTotalBetween(start, end).test {
            assertThat(awaitItem()).isEqualTo(6000L)
            cancelAndIgnoreRemainingEvents()
        }
    }
```

- [ ] **Step 7: Run the gates**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; existing unit tests still pass. (The new DAO test runs on-device.)

- [ ] **Step 8: Commit (checkpoint — hand to user)**

```bash
git add app/src/main/java/cy/txtracker/data/TransactionDao.kt \
        app/src/main/java/cy/txtracker/data/TransactionRepository.kt \
        app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt \
        app/src/androidTest/java/cy/txtracker/data/TransactionDaoTest.kt
git commit -m "Reimbursed share: net the portion in Home + Foreign totals; updateReimbursed"
```

---

### Task 4: Insights `chartAmountMinor` nets reimbursement

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/insights/InsightsAggregator.kt:30`
- Test: `app/src/test/java/cy/txtracker/ui/insights/InsightsAggregatorTest.kt`

- [ ] **Step 1: Write the failing test**

In `InsightsAggregatorTest.kt`, the private `tx(...)` helper has no reimbursed param. Add an overload-friendly param and a test. First add `reimbursedMinor` to the helper (after `fundingSourceId`):

```kotlin
    private fun tx(
        amountMinor: Long,
        occurredAt: Instant,
        categoryId: Long? = null,
        fundingSourceId: Long? = null,
        direction: Direction = Direction.OUT,
        reimbursedMinor: Long? = null,
    ) = Transaction(
        id = 0,
        amountMinor = amountMinor,
        currency = "MYR",
        merchantRaw = "M",
        merchantNormalized = "M",
        categoryId = categoryId,
        description = null,
        occurredAt = occurredAt,
        timeBucket = TimeBucket.MIDDAY,
        sourceApp = "manual",
        rawText = null,
        direction = direction,
        createdAt = occurredAt,
        notificationDedupeKey = "k-$occurredAt-$amountMinor-$categoryId-$fundingSourceId-$direction",
        fundingSourceId = fundingSourceId,
        reimbursedMinor = reimbursedMinor,
    )
```

Then add the test:

```kotlin
    @Test
    fun chart_amount_subtracts_reimbursed_portion() {
        assertThat(tx(10000, may, reimbursedMinor = 4000).chartAmountMinor()).isEqualTo(6000)
    }

    @Test
    fun chart_amount_is_full_when_not_reimbursed() {
        assertThat(tx(10000, may).chartAmountMinor()).isEqualTo(10000)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.ui.insights.InsightsAggregatorTest"`
Expected: FAIL on `chart_amount_subtracts_reimbursed_portion` (returns 10000, expected 6000). (If `chartAmountMinor` is `internal`, the test is in the same package so it resolves.)

- [ ] **Step 3: Net the reimbursed portion in `chartAmountMinor`**

In `InsightsAggregator.kt`, change line 30 from:
```kotlin
internal fun Transaction.chartAmountMinor(): Long = amountMinor
```
to:
```kotlin
internal fun Transaction.chartAmountMinor(): Long = amountMinor - (reimbursedMinor ?: 0L)
```

Update the KDoc just above it (lines ~24-27) to note it now subtracts `reimbursedMinor` so charts show net-of-reimbursement spend.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.ui.insights.InsightsAggregatorTest"`
Expected: PASS (all tests).

- [ ] **Step 5: Commit (checkpoint — hand to user)**

```bash
git add app/src/main/java/cy/txtracker/ui/insights/InsightsAggregator.kt \
        app/src/test/java/cy/txtracker/ui/insights/InsightsAggregatorTest.kt
git commit -m "Reimbursed share: net the portion in Insights chartAmountMinor (TDD)"
```

---

### Task 5: CSV inline subtraction

**Files:**
- Modify: `app/src/main/java/cy/txtracker/export/CsvExporter.kt:218-229` (call sites) and `:263-267` (`buildAmountCell`)
- Test: `app/src/test/java/cy/txtracker/export/BuildCsvTest.kt`

- [ ] **Step 1: Write the failing test**

In `BuildCsvTest.kt`, the `tx(...)` helper has no reimbursed param. Add it (after `fundingSourceId`):

```kotlin
    private fun tx(
        amountMinor: Long,
        merchant: String,
        description: String? = null,
        categoryId: Long? = null,
        occurredAt: Instant = Instant.parse("2026-05-09T04:30:00Z"),  // 12:30 KL
        fundingSourceId: Long? = null,
        reimbursedMinor: Long? = null,
    ) = Transaction(
        id = 0,
        amountMinor = amountMinor,
        currency = "MYR",
        merchantRaw = merchant,
        merchantNormalized = merchant.uppercase(),
        categoryId = categoryId,
        description = description,
        occurredAt = occurredAt,
        timeBucket = TimeBucket.MIDDAY,
        sourceApp = "manual",
        rawText = null,
        direction = Direction.OUT,
        createdAt = occurredAt,
        notificationDedupeKey = "k-$merchant-$amountMinor",
        fundingSourceId = fundingSourceId,
        reimbursedMinor = reimbursedMinor,
    )
```

Then add two tests:

```kotlin
    @Test
    fun single_reimbursed_tx_shows_inline_subtraction_formula() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 10000, merchant = "PJ Cafe", categoryId = food.id, reimbursedMinor = 5000),
            ),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        // Food column shows =100.00-50.00, evaluating to the net 50.00.
        assertThat(rows[1]).isEqualTo("2026-05-09,,,=100.00-50.00,,")
    }

    @Test
    fun reimbursed_mixes_with_plain_tx_in_same_category_day() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 10000, merchant = "A", categoryId = food.id, reimbursedMinor = 5000),
                tx(amountMinor = 3000, merchant = "B", categoryId = food.id),
            ),
            categories = categories,
            fundingSourcesById = emptyMap(),
        )
        val rows = csv.trimEnd().lines()
        assertThat(rows[1]).isEqualTo("2026-05-09,,,=100.00-50.00+30.00,,")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.export.BuildCsvTest"`
Expected: FAIL — current code emits `100.00` / `=100.00+30.00` (ignores reimbursement).

- [ ] **Step 3: Widen `buildAmountCell` to per-transaction terms**

In `CsvExporter.kt`, replace `buildAmountCell` (lines 263-267) with a version taking `(amount, reimbursed)` pairs:

```kotlin
/**
 * Builds the contents of a single category column for a single day. Each term is a
 * transaction's `amount`, or `amount-reimbursed` when others reimbursed part of it.
 *   - empty list                       → blank
 *   - one plain amount                 → "12.50"
 *   - one reimbursed amount            → "=100.00-50.00"  (a formula, since it subtracts)
 *   - many                             → "=12.50+100.00-50.00+5.00"
 * The cell always evaluates to the net spend for the day in that category.
 */
private fun buildAmountCell(terms: List<Pair<Long, Long?>>): String = when {
    terms.isEmpty() -> ""
    terms.size == 1 && terms[0].second == null -> formatAmount(terms[0].first)
    else -> terms.joinToString(prefix = "=", separator = "+") { (amount, reimbursed) ->
        if (reimbursed != null) "${formatAmount(amount)}-${formatAmount(reimbursed)}"
        else formatAmount(amount)
    }
}
```

- [ ] **Step 4: Update the two call sites to pass reimbursed**

In `buildCsv`, the per-category cell (lines 218-221) becomes:

```kotlin
        // Per-category columns.
        for (c in orderedCategories) {
            sb.append(',')
            val terms = txs.filter { it.categoryId == c.id }.map { it.amountMinor to it.reimbursedMinor }
            sb.append(buildAmountCell(terms))
        }
```

And the Unverified cell (lines 225-229) becomes:

```kotlin
        sb.append(',')
        val unverifiedTerms = txs
            .filter { it.categoryId == null || categoryById[it.categoryId] == null }
            .map { it.amountMinor to it.reimbursedMinor }
        sb.append(buildAmountCell(unverifiedTerms))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.export.BuildCsvTest"`
Expected: PASS (existing + 2 new tests). Existing tests still pass because plain amounts keep emitting `12.50` / `=12.50+4.00`.

- [ ] **Step 6: Commit (checkpoint — hand to user)**

```bash
git add app/src/main/java/cy/txtracker/export/CsvExporter.kt \
        app/src/test/java/cy/txtracker/export/BuildCsvTest.kt
git commit -m "Reimbursed share: CSV inline subtraction in category cells (TDD)"
```

---

### Task 6: Backup v8 → v9

**Files:**
- Modify: `app/src/main/java/cy/txtracker/export/Backup.kt:53` (version) `:125-143` (`BackupTransaction`)
- Modify: `app/src/main/java/cy/txtracker/export/BackupExporter.kt:128-147` (serialize)
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt:1298-1317` (`applyBackup` restore)
- Test: `app/src/test/java/cy/txtracker/export/ReimbursedBackupTest.kt`

- [ ] **Step 1: Write the failing round-trip test**

Create `app/src/test/java/cy/txtracker/export/ReimbursedBackupTest.kt`:

```kotlin
package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test

class ReimbursedBackupTest {
    private val json = BackupExporter.JSON

    private fun tx(reimbursedMinor: Long?) = BackupTransaction(
        amountMinor = 10000,
        currency = "MYR",
        merchantRaw = "M",
        merchantNormalized = "M",
        categoryName = null,
        description = null,
        occurredAt = Instant.parse("2026-05-09T04:30:00Z"),
        timeBucket = TimeBucket.MIDDAY,
        sourceApp = "manual",
        rawText = null,
        direction = Direction.OUT,
        createdAt = Instant.parse("2026-05-09T04:30:00Z"),
        notificationDedupeKey = "k",
        reimbursedMinor = reimbursedMinor,
    )

    @Test
    fun reimbursedMinor_survives_round_trip() {
        val encoded = json.encodeToString(tx(4000))
        val decoded = json.decodeFromString<BackupTransaction>(encoded)
        assertThat(decoded.reimbursedMinor).isEqualTo(4000)
    }

    @Test
    fun legacy_json_without_field_defaults_to_null() {
        val legacy = """
            {"amountMinor":10000,"currency":"MYR","merchantRaw":"M","merchantNormalized":"M",
             "categoryName":null,"description":null,"occurredAt":"2026-05-09T04:30:00Z",
             "timeBucket":"MIDDAY","sourceApp":"manual","rawText":null,"direction":"OUT",
             "createdAt":"2026-05-09T04:30:00Z","notificationDedupeKey":"k"}
        """.trimIndent()
        val decoded = json.decodeFromString<BackupTransaction>(legacy)
        assertThat(decoded.reimbursedMinor).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.export.ReimbursedBackupTest"`
Expected: FAIL to compile — `BackupTransaction` has no `reimbursedMinor` argument.

- [ ] **Step 3: Add the field to `BackupTransaction` and bump the version**

In `Backup.kt`, change `const val CURRENT_VERSION = 8` to `9`, and add a version-history line in the KDoc:
```
 *   v9 – added [BackupTransaction.reimbursedMinor] (reimbursed-by-others share)
```
Then add the field to `BackupTransaction` (after `fundingSourceLookupKey`, line 142):
```kotlin
    /** "<sourceAppHint>|<last4>" — null when unlinked. Empty strings for null parts (e.g. Cash = "|"). */
    val fundingSourceLookupKey: String? = null,
    /** Portion others reimbursed, in minor units. null = not reimbursed. Default keeps v8 backups parseable. */
    val reimbursedMinor: Long? = null,
```

- [ ] **Step 4: Serialize it in `BackupExporter`**

In `BackupExporter.kt`, in the `transactions = txs.map { tx -> BackupTransaction(...) }` block (line 145), add after `fundingSourceLookupKey = ...`:
```kotlin
                    fundingSourceLookupKey = tx.fundingSourceId?.let { fundingSourceKeyById[it] },
                    reimbursedMinor = tx.reimbursedMinor,
```

- [ ] **Step 5: Restore it in `applyBackup`**

In `TransactionRepository.kt`, in the `applyBackup` transaction-insert loop (the `Transaction(...)` at line 1299), add after `fundingSourceId = fundingSourceId,`:
```kotlin
                    fundingSourceId = fundingSourceId,
                    reimbursedMinor = bt.reimbursedMinor,
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.export.ReimbursedBackupTest"`
Expected: PASS (2 tests).

- [ ] **Step 7: Run the gates**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass. (Instrumented `ApplyBackupTest` round-trip is run on-device by the user.)

- [ ] **Step 8: Commit (checkpoint — hand to user)**

```bash
git add app/src/main/java/cy/txtracker/export/Backup.kt \
        app/src/main/java/cy/txtracker/export/BackupExporter.kt \
        app/src/main/java/cy/txtracker/data/TransactionRepository.kt \
        app/src/test/java/cy/txtracker/export/ReimbursedBackupTest.kt
git commit -m "Reimbursed share: backup v8->v9 carries reimbursedMinor (TDD)"
```

---

### Task 7: Theme accent color

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/theme/Color.kt`

- [ ] **Step 1: Add the accent**

Append to `Color.kt`:

```kotlin
/**
 * Accent for a reimbursed-by-others portion on a transaction row ("money coming back").
 * A blue tone, chosen to read distinctly from any green "income"/SL accent.
 */
val ReimbursedAccent = Color(0xFF1565C0)
```

(If `androidx.compose.ui.graphics.Color` isn't already imported in the file, add `import androidx.compose.ui.graphics.Color`.)

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit (checkpoint — hand to user)**

```bash
git add app/src/main/java/cy/txtracker/ui/theme/Color.kt
git commit -m "Reimbursed share: ReimbursedAccent theme color"
```

---

### Task 8: Edit sheet — "Reimbursed by others" section

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt` (add `setReimbursed`)
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt` (callback param + section)

- [ ] **Step 1: Add `setReimbursed` to the ViewModel**

In `EditTransactionViewModel.kt`, mirror `setFundingSource` (line 102). Add:

```kotlin
    /** Sets (or clears, when [reimbursedMinor] is null) the reimbursed-by-others portion. */
    fun setReimbursed(transactionId: Long, reimbursedMinor: Long?) {
        viewModelScope.launch {
            repository.setTransactionReimbursed(transactionId, reimbursedMinor)
            val refreshed = repository.getTransaction(transactionId) ?: return@launch
            val current = _state.value
            if (current is EditUiState.Editing) {
                _state.value = current.copy(transaction = refreshed)
            }
        }
    }
```

- [ ] **Step 2: Wire the callback in `EditTransactionSheet`**

At the `EditingContent(...)` call site (around line 84-92, where `onFundingSourceChange` is passed), add:

```kotlin
                onReimbursedChange = { reimbursedMinor ->
                    viewModel.setReimbursed(transactionId, reimbursedMinor)
                },
```

Add the parameter to `EditingContent`'s signature (next to `onFundingSourceChange: (Long?) -> Unit,` at line 138):

```kotlin
    onReimbursedChange: (Long?) -> Unit,
```

- [ ] **Step 3: Add the section UI**

In `EditingContent`, immediately after the "Funding source" block (the `FundingSourcePickerSheet` section ends around line 320; place this before the "Category" label), add:

```kotlin
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(text = "Reimbursed by others", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))

        val reimbursedEnabled = tx.reimbursedMinor != null
        // Local expansion state so the input shows immediately when the switch is turned on,
        // before any valid amount has been persisted. Re-seeded per transaction id.
        var reimbursedExpanded by remember(tx.id) { mutableStateOf(reimbursedEnabled) }
        var reimbursedText by remember(tx.id) {
            mutableStateOf(tx.reimbursedMinor?.let { formatAmount(it, "").trim() } ?: "")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (reimbursedEnabled) {
                    "Others returned ${formatAmount(tx.reimbursedMinor!!, "").trim()} of ${formatAmount(tx.amountMinor, "").trim()} ${tx.currency}"
                } else "Off",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = reimbursedExpanded,
                onCheckedChange = { checked ->
                    reimbursedExpanded = checked
                    if (!checked) {
                        // Turning it off clears any persisted reimbursement.
                        reimbursedText = ""
                        onReimbursedChange(null)
                    }
                    // Turning it on just reveals the (empty) field; nothing persists until
                    // the user types a valid amount (handled by the LaunchedEffect below).
                },
            )
        }
        if (reimbursedExpanded) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = reimbursedText,
                onValueChange = { reimbursedText = it },
                label = { Text("Reimbursed amount (${tx.currency})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            LaunchedEffect(reimbursedText) {
                val parsed = parseAmountMinor(reimbursedText)
                if (parsed != null &&
                    isValidReimbursedMinor(parsed, tx.amountMinor) &&
                    parsed != tx.reimbursedMinor
                ) {
                    onReimbursedChange(parsed)
                }
            }
        }
```

Add the imports at the top of `EditTransactionSheet.kt`:
```kotlin
import androidx.compose.material3.Switch
import cy.txtracker.domain.isValidReimbursedMinor
import cy.txtracker.ui.format.formatAmount
import cy.txtracker.ui.manual.parseAmountMinor
```
(`Switch` may be new; `OutlinedTextField`, `HorizontalDivider`, `KeyboardOptions`, `ImeAction`, `LaunchedEffect`, `formatMyr` are already imported. `parseAmountMinor` is `internal` in package `cy.txtracker.ui.manual` — importable from this module.)

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit (checkpoint — hand to user)**

```bash
git add app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt \
        app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt
git commit -m "Reimbursed share: edit-sheet section + setReimbursed"
```

---

### Task 9: Add Manual sheet — reimbursed field

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/manual/AddManualViewModel.kt` (state field + setter + save wiring)
- Modify: `app/src/main/java/cy/txtracker/ui/manual/AddManualSheet.kt` (section UI)

- [ ] **Step 1: Add state field + setter + save wiring in the ViewModel**

In `AddManualUiState` (line 27), add a field after `fundingSource`:
```kotlin
    val fundingSource: FundingSource? = null,
    /** Free-typed reimbursed-by-others amount. Blank = not reimbursed. */
    val reimbursedText: String = "",
) {
    val amountMinor: Long? get() = parseAmountMinor(amountText)
    /** Reimbursed minor units, only when it parses AND is within (0, amountMinor]. */
    val reimbursedMinor: Long?
        get() {
            val amt = amountMinor ?: return null
            val parsed = parseAmountMinor(reimbursedText) ?: return null
            return parsed.takeIf { it in 1L..amt }
        }
    val canSave: Boolean
        get() = !isSaving && (amountMinor ?: 0) > 0 && merchantText.trim().isNotEmpty()
}
```

Add a setter next to `setDescription` (line 126):
```kotlin
    fun setReimbursed(text: String) {
        // Same sanitization as setAmount: digits and at most one 2-dp decimal.
        val sanitized = text.filter { it.isDigit() || it == '.' }
        val parts = sanitized.split('.')
        val cleaned = when {
            parts.size <= 1 -> sanitized
            parts.size == 2 -> parts[0] + "." + parts[1].take(2)
            else -> parts[0] + "." + parts.drop(1).joinToString("").take(2)
        }
        _state.update { it.copy(reimbursedText = cleaned) }
    }
```

In `save(...)` (line 138), pass it through:
```kotlin
            repository.addManualTransaction(
                amountMinor = amount,
                merchantRaw = s.merchantText.trim(),
                categoryId = s.categoryId,
                description = s.descriptionText.takeIf { it.isNotBlank() },
                occurredAt = occurredAt,
                currency = s.currency,
                fundingSourceId = s.fundingSource?.id,
                reimbursedMinor = s.reimbursedMinor,
            )
```

- [ ] **Step 2: Add the section to `AddManualSheet`**

Locate where the funding-source picker is rendered in `AddManualSheet.kt` (search for `setFundingSource` / the funding label). Immediately after it, add an optional reimbursed field bound to `state.reimbursedText`:

```kotlin
        Spacer(Modifier.height(16.dp))
        Text(text = "Reimbursed by others (optional)", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.reimbursedText,
            onValueChange = viewModel::setReimbursed,
            label = { Text("Reimbursed amount (${state.currency})") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
```

Ensure these are imported in `AddManualSheet.kt` (add any that are missing):
```kotlin
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit (checkpoint — hand to user)**

```bash
git add app/src/main/java/cy/txtracker/ui/manual/AddManualViewModel.kt \
        app/src/main/java/cy/txtracker/ui/manual/AddManualSheet.kt
git commit -m "Reimbursed share: add-manual reimbursed field"
```

---

### Task 10: Row display — net of reimbursement (Home + Foreign)

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt` (`TransactionRow` amount + new `RowAmount`)

The Foreign tab reuses `TransactionRow` via `ForeignRoute` (passing its own currency-aware `amountFormatter`), so changing the amount rendering here updates both surfaces.

- [ ] **Step 1: Replace the single amount Text with a net-aware composable**

In `TransactionRow` (lines 465-469), the amount is currently a single `Text`:
```kotlin
                Text(
                    text = amountFormatter(row.transaction.amountMinor),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
```
Replace it with a call to a new helper:
```kotlin
                RowAmount(transaction = row.transaction, amountFormatter = amountFormatter)
```

- [ ] **Step 2: Add the `RowAmount` composable**

Add near the other private composables in `HomeScreen.kt` (e.g. just below `TransactionRow`):

```kotlin
@Composable
private fun RowAmount(transaction: Transaction, amountFormatter: (Long) -> String) {
    val reimbursed = transaction.reimbursedMinor
    if (reimbursed == null) {
        Text(
            text = amountFormatter(transaction.amountMinor),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    } else {
        val net = transaction.amountMinor - reimbursed
        Column(horizontalAlignment = Alignment.End) {
            // Net "what you actually paid" — emphasized.
            Text(
                text = amountFormatter(net),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            // Original amount — de-emphasized, struck through.
            Text(
                text = amountFormatter(transaction.amountMinor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
            )
            // Reimbursed portion — "money coming back" accent.
            Text(
                text = "−${amountFormatter(reimbursed)} Reimbursed",
                style = MaterialTheme.typography.bodySmall,
                color = cy.txtracker.ui.theme.ReimbursedAccent,
            )
        }
    }
}
```

(`Column`, `Alignment`, `MaterialTheme`, `Text`, `FontWeight` are already imported in this file; `Transaction` is imported. `TextDecoration` and `ReimbursedAccent` are fully qualified inline above to avoid import churn.)

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full gates**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 5: Commit (checkpoint — hand to user)**

```bash
git add app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt
git commit -m "Reimbursed share: net three-number row display (Home + Foreign)"
```

---

## Final verification (after all tasks)

- [ ] Run the full gates one last time:
  `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`
- [ ] Hand off the instrumented tests (`MigrationV11ToV12Test`, the new `TransactionDaoTest` case, `ApplyBackupTest`) to the user to run on-device.
- [ ] Manual smoke (user, on device): add a manual tx with a reimbursed amount → row shows net + strikethrough + "−X Reimbursed"; Home/Insights totals drop by the reimbursed amount; CSV export shows `=amount-reimbursed`; backup/restore preserves it.

## Out of scope (do not implement)

Tracking who reimbursed / notes; split-evenly or percent helpers; reimbursing inflows; any pool/ledger/balance; a dedicated CSV "Reimbursed" column; multiple reimbursement entries per transaction.
