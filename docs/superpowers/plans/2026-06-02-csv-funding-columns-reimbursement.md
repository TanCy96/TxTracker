# CSV Funding-Source Columns + Multi-Entry Reimbursements — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the CSV `Source` text column with four fixed funding-bucket columns (gross positives), and extend reimbursements to multiple per-person entries that each land as a negative in their destination bucket column.

**Architecture:** New `reimbursement_entries` child table (Approach A). `Transaction.reimbursedMinor` is retained as a maintained *cached sum* of its entries, so all existing net-spend SQL/Insights/notification/Foreign surfaces stay byte-for-byte unchanged. The CSV builder and backup format consume the entries directly. Spec: `docs/superpowers/specs/2026-06-02-csv-funding-columns-reimbursement-design.md`.

**Tech Stack:** Kotlin, Room (schema v12→v13), kotlinx.serialization (backup v9→v10), Jetpack Compose, JUnit4 + Truth + (androidTest) Room migration testing.

**Conventions / environment:**
- Shell is PowerShell on Windows. Gradle wrapper: `./gradlew.bat`. Module: `:app`.
- **Test policy (hard rule):** only `:app:testDebugUnitTest` and compile tasks run locally. **Never** boot an emulator or run `connectedDebugAndroidTest`. For `androidTest` sources the local gate is `:app:compileDebugAndroidTestKotlin`; those tests run on CI/device, not in-session.
- Commit after each task (the user has authorized per-task commits). End commit messages with the `Co-Authored-By` trailer used in this repo.
- `parseAmountMinor` lives in `cy.txtracker.ui.manual` (`AddManualViewModel.kt`). `formatAmount(minor, symbol)` lives in `cy.txtracker.ui.format`. `fundingBucketLabel`/`KIND_ORDER` live in `cy.txtracker.ui.common`. The export package keeps its OWN private copies (`bucketLabel`, `CANONICAL_KIND_ORDER`) to avoid an export→ui dependency — do not cross-import.

---

## File Structure

**Create:**
- `app/src/main/java/cy/txtracker/data/ReimbursementEntry.kt` — the new entity (kept in its own file; `Entities.kt` is already large).
- `app/src/main/java/cy/txtracker/data/ReimbursementEntryDao.kt` — CRUD + sum + getAll DAO.
- `app/src/androidTest/java/cy/txtracker/data/MigrationV12ToV13Test.kt` — migration test.
- `app/src/androidTest/java/cy/txtracker/data/ReimbursementEntryDaoTest.kt` — DAO + cached-sum recompute test.

**Modify:**
- `app/src/main/java/cy/txtracker/domain/Share.kt` — add `reimbursedTotalMinor` + `isValidReimbursementTotal`.
- `app/src/main/java/cy/txtracker/data/TxDatabase.kt` — register entity, `version = 13`, expose dao.
- `app/src/main/java/cy/txtracker/di/DatabaseModule.kt` — `MIGRATION_12_13` + registration.
- `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` — entry CRUD wrappers + getters; constructor wires new dao.
- `app/src/main/java/cy/txtracker/export/CsvExporter.kt` — drop `Source`; four bucket columns; multi-entry category cells; thread entries through.
- `app/src/main/java/cy/txtracker/export/Backup.kt` — `CURRENT_VERSION = 10`, `BackupReimbursementEntry`, `reimbursementEntries` field.
- `app/src/main/java/cy/txtracker/export/BackupExporter.kt` — serialize entries.
- `app/src/main/java/cy/txtracker/export/BackupImporter.kt` — (no code change beyond `SUPPORTED_VERSIONS` auto-extends via `CURRENT_VERSION`; verify).
- `app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt` + `EditTransactionSheet.kt` — entry-list section.
- `app/src/main/java/cy/txtracker/ui/manual/AddManualViewModel.kt` + `AddManualSheet.kt` — entry-list section + post-insert persist.

**Test (unit, runnable locally):**
- `app/src/test/java/cy/txtracker/domain/ShareTest.kt`
- `app/src/test/java/cy/txtracker/export/BuildCsvTest.kt` (rewritten for new layout)
- `app/src/test/java/cy/txtracker/export/ReimbursedBackupTest.kt`

---

## Task 1: Domain helpers for the entry total

**Files:**
- Modify: `app/src/main/java/cy/txtracker/domain/Share.kt`
- Test: `app/src/test/java/cy/txtracker/domain/ShareTest.kt`

- [ ] **Step 1: Add failing tests** — append these to `ShareTest.kt` (inside the existing `class ShareTest {`, before the closing brace):

```kotlin
    // ─── Multi-entry reimbursement total ────────────────────────────────────────────────

    @Test fun total_of_empty_entries_is_null() {
        assertThat(reimbursedTotalMinor(emptyList())).isNull()
    }

    @Test fun total_sums_entries() {
        assertThat(reimbursedTotalMinor(listOf(1000L, 1200L))).isEqualTo(2200L)
    }

    @Test fun total_of_all_zero_is_null() {
        // Defensive: a degenerate all-zero set carries no reimbursement.
        assertThat(reimbursedTotalMinor(listOf(0L))).isNull()
    }

    @Test fun entry_set_valid_when_each_positive_and_sum_in_range() {
        assertThat(isValidReimbursementTotal(listOf(4000L, 6000L), 10000L)).isTrue()
    }

    @Test fun entry_set_invalid_when_sum_exceeds_amount() {
        assertThat(isValidReimbursementTotal(listOf(4000L, 6001L), 10000L)).isFalse()
    }

    @Test fun entry_set_invalid_when_any_entry_non_positive() {
        assertThat(isValidReimbursementTotal(listOf(0L, 5000L), 10000L)).isFalse()
    }

    @Test fun empty_entry_set_is_invalid() {
        assertThat(isValidReimbursementTotal(emptyList(), 10000L)).isFalse()
    }
```

- [ ] **Step 2: Run, verify it fails to compile** (functions undefined):

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.domain.ShareTest"`
Expected: FAIL — `unresolved reference: reimbursedTotalMinor` / `isValidReimbursementTotal`.

- [ ] **Step 3: Implement** — append to `Share.kt` (after `isValidReimbursedMinor`):

```kotlin
/**
 * The cached `Transaction.reimbursedMinor` value derived from a transaction's reimbursement
 * entries: the sum of [entryAmountsMinor], or `null` when there are no positive entries
 * (so the column reads "not reimbursed" exactly as a single cleared toggle did).
 */
fun reimbursedTotalMinor(entryAmountsMinor: List<Long>): Long? =
    entryAmountsMinor.sum().takeIf { it > 0 }

/**
 * The full set of reimbursement entries is valid when there is at least one entry, every
 * entry amount is positive, and their sum is within `(0, amountMinor]`. Built on
 * [isValidReimbursedMinor] so the ceiling rule stays in one place.
 */
fun isValidReimbursementTotal(entryAmountsMinor: List<Long>, amountMinor: Long): Boolean =
    entryAmountsMinor.isNotEmpty() &&
        entryAmountsMinor.all { it > 0 } &&
        isValidReimbursedMinor(entryAmountsMinor.sum(), amountMinor)
```

- [ ] **Step 4: Run, verify pass:**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.domain.ShareTest"`
Expected: PASS (all, including the 5 pre-existing tests).

- [ ] **Step 5: Commit:**

```bash
git add app/src/main/java/cy/txtracker/domain/Share.kt app/src/test/java/cy/txtracker/domain/ShareTest.kt
git commit -m "Reimbursement: domain helpers for multi-entry total + validation"
```

---

## Task 2: `ReimbursementEntry` entity + DB registration

**Files:**
- Create: `app/src/main/java/cy/txtracker/data/ReimbursementEntry.kt`
- Modify: `app/src/main/java/cy/txtracker/data/TxDatabase.kt`

> No new TypeConverter needed: `Converters` already maps `FundingSourceKind` (lines 34–38) and `Instant`.

- [ ] **Step 1: Create the entity file** — `ReimbursementEntry.kt`:

```kotlin
package cy.txtracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * One person's reimbursement of part of a [Transaction]. A transaction may have several.
 * The portion others returned is netted out of *your* spend; the cached aggregate lives on
 * [Transaction.reimbursedMinor] (kept in step by the repository) so existing net-spend SQL
 * is untouched. These rows add the per-person / per-destination detail consumed by the edit
 * sheet and the CSV funding columns.
 *
 * [destinationKind] is the funding bucket the money landed in — it selects which CSV funding
 * column receives the negative term. [personLabel] is in-app only (never exported to CSV).
 * Deleting the parent transaction cascades these away.
 */
@Entity(
    tableName = "reimbursement_entries",
    foreignKeys = [
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transactionId")],
)
data class ReimbursementEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    /** Portion this person returned, in minor units. Always > 0. */
    val amountMinor: Long,
    /** Funding bucket the money landed in; selects the CSV funding column for the negative. */
    val destinationKind: FundingSourceKind,
    /** Optional free-text label for who reimbursed. In-app only; never emitted to CSV. */
    val personLabel: String? = null,
    /** Stable ordering for the edit sheet and CSV term order. */
    val createdAt: Instant,
)
```

- [ ] **Step 2: Register in `TxDatabase.kt`** — bump version and add the entity + dao accessor.

In the `@Database(...)` annotation change `version = 12,` to `version = 13,` and add `ReimbursementEntry::class,` to the `entities = [...]` list (after `FundingSource::class,`):

```kotlin
@Database(
    version = 13,
    exportSchema = true,
    entities = [
        Transaction::class,
        Category::class,
        MerchantMapping::class,
        MerchantDescriptionMapping::class,
        CategoryDescriptionMapping::class,
        MerchantNote::class,
        UserFacingSource::class,
        ApprovedSource::class,
        CapturedNotification::class,
        RejectedSource::class,
        TrackedCurrency::class,
        TripWindow::class,
        PackageTextRewrite::class,
        FundingSource::class,
        ReimbursementEntry::class,
    ],
)
```

Add the dao accessor after `abstract fun fundingSourceDao(): FundingSourceDao`:

```kotlin
    abstract fun reimbursementEntryDao(): ReimbursementEntryDao
```

- [ ] **Step 3: Compile-gate** (Room will report a missing migration at runtime, not compile — that's Task 3; the DAO type is created in Task 4, so compilation will fail until then). Defer the build to the end of Task 4. For now just confirm the entity file has no syntax error by eye.

- [ ] **Step 4: Commit:**

```bash
git add app/src/main/java/cy/txtracker/data/ReimbursementEntry.kt app/src/main/java/cy/txtracker/data/TxDatabase.kt
git commit -m "Reimbursement: add ReimbursementEntry entity, register at DB v13"
```

---

## Task 3: Migration v12 → v13 (+ legacy backfill)

**Files:**
- Modify: `app/src/main/java/cy/txtracker/di/DatabaseModule.kt`
- Test: `app/src/androidTest/java/cy/txtracker/data/MigrationV12ToV13Test.kt` (create)

- [ ] **Step 1: Add `MIGRATION_12_13`** — in `DatabaseModule.kt`, immediately after the `MIGRATION_11_12` object (ends at line ~450):

```kotlin
/**
 * v13 adds the `reimbursement_entries` child table for multi-person reimbursements. Each
 * row records an amount, the destination funding bucket, and an optional person label.
 * `Transaction.reimbursedMinor` is retained as the cached sum of a transaction's entries.
 *
 * Backfill: every existing reimbursed transaction (reimbursedMinor > 0) gets ONE entry with
 * destinationKind = 'DEBIT_BANK' (bank transfer is the common reimbursement channel) so the
 * CSV funding columns reconcile for pre-v13 data. reimbursedMinor itself is left unchanged
 * (it already equals the new sum). See
 * docs/superpowers/specs/2026-06-02-csv-funding-columns-reimbursement-design.md.
 */
private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `reimbursement_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `transactionId` INTEGER NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `destinationKind` TEXT NOT NULL,
                `personLabel` TEXT,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reimbursement_entries_transactionId` " +
                "ON `reimbursement_entries`(`transactionId`)",
        )
        // Backfill one DEBIT_BANK entry per existing reimbursed transaction. createdAt copies
        // the transaction's occurredAt (epoch-ms Long, matching the Instant converter).
        db.execSQL(
            """
            INSERT INTO `reimbursement_entries`
                (`transactionId`, `amountMinor`, `destinationKind`, `personLabel`, `createdAt`)
            SELECT `id`, `reimbursedMinor`, 'DEBIT_BANK', NULL, `occurredAt`
            FROM `transactions`
            WHERE `reimbursedMinor` IS NOT NULL AND `reimbursedMinor` > 0
            """.trimIndent(),
        )
    }
}
```

- [ ] **Step 2: Register it** — in `DatabaseModule.addMigrations(...)`, add `MIGRATION_12_13,` after `MIGRATION_11_12,` (around line 91):

```kotlin
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
```

- [ ] **Step 3: Write the migration test** — create `MigrationV12ToV13Test.kt`. Mirror the existing `MigrationV11ToV12Test.kt` structure (open it first to copy the exact `MigrationTestHelper` setup, `TestDb` factory, and imports — match that file's package, runner, and helper construction verbatim, changing only versions and assertions). The test body must assert: (a) the migration runs and validates against Room's exported v13 schema, and (b) a v12 row with `reimbursedMinor = 5000` yields exactly one backfilled `reimbursement_entries` row with `destinationKind = 'DEBIT_BANK'` and `amountMinor = 5000`, while a non-reimbursed row yields none.

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
class MigrationV12ToV13Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbName = "migration-v12-v13-test"

    @Test
    fun backfills_one_debit_bank_entry_per_reimbursed_transaction() {
        helper.createDatabase(dbName, 12).use { db ->
            // Reimbursed transaction.
            db.execSQL(
                """
                INSERT INTO transactions
                    (amountMinor, currency, merchantRaw, merchantNormalized, categoryId,
                     description, occurredAt, timeBucket, sourceApp, rawText, direction,
                     createdAt, notificationDedupeKey, needsVerification,
                     needsCurrencyConfirmation, fundingSourceId, reimbursedMinor)
                VALUES
                    (10000, 'MYR', 'A', 'A', NULL, NULL, 1700000000000, 'MIDDAY', 'manual',
                     NULL, 'OUT', 1700000000000, 'k-reimb', 0, 0, NULL, 5000)
                """.trimIndent(),
            )
            // Non-reimbursed transaction.
            db.execSQL(
                """
                INSERT INTO transactions
                    (amountMinor, currency, merchantRaw, merchantNormalized, categoryId,
                     description, occurredAt, timeBucket, sourceApp, rawText, direction,
                     createdAt, notificationDedupeKey, needsVerification,
                     needsCurrencyConfirmation, fundingSourceId, reimbursedMinor)
                VALUES
                    (3000, 'MYR', 'B', 'B', NULL, NULL, 1700000000000, 'MIDDAY', 'manual',
                     NULL, 'OUT', 1700000000000, 'k-plain', 0, 0, NULL, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 13, true)

        db.query("SELECT transactionId, amountMinor, destinationKind, personLabel FROM reimbursement_entries")
            .use { c ->
                assertThat(c.count).isEqualTo(1)
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(1)).isEqualTo(5000)
                assertThat(c.getString(2)).isEqualTo("DEBIT_BANK")
                assertThat(c.isNull(3)).isTrue()
            }
    }
}
```

> If `MigrationV11ToV12Test.kt` uses a different `MigrationTestHelper` constructor signature (e.g. without the `emptyList()` arg), copy *its* exact signature — Room's helper API differs across versions and the existing test is the source of truth.

- [ ] **Step 4: Compile-gate the androidTest source** (do NOT run on device):

Run: `./gradlew.bat :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL (compiles; the test itself runs on CI/device later).

- [ ] **Step 5: Commit:**

```bash
git add app/src/main/java/cy/txtracker/di/DatabaseModule.kt app/src/androidTest/java/cy/txtracker/data/MigrationV12ToV13Test.kt
git commit -m "Reimbursement: MIGRATION_12_13 with legacy DEBIT_BANK backfill + test"
```

---

## Task 4: `ReimbursementEntryDao` + repository wiring

**Files:**
- Create: `app/src/main/java/cy/txtracker/data/ReimbursementEntryDao.kt`
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt`
- Test: `app/src/androidTest/java/cy/txtracker/data/ReimbursementEntryDaoTest.kt` (create)

- [ ] **Step 1: Create the DAO** — `ReimbursementEntryDao.kt`:

```kotlin
package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReimbursementEntryDao {
    @Insert suspend fun insert(entry: ReimbursementEntry): Long
    @Update suspend fun update(entry: ReimbursementEntry)
    @Delete suspend fun delete(entry: ReimbursementEntry)

    @Query("SELECT * FROM reimbursement_entries WHERE transactionId = :txId ORDER BY createdAt, id")
    fun observeForTransaction(txId: Long): Flow<List<ReimbursementEntry>>

    @Query("SELECT * FROM reimbursement_entries WHERE transactionId = :txId ORDER BY createdAt, id")
    suspend fun getForTransaction(txId: Long): List<ReimbursementEntry>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM reimbursement_entries WHERE transactionId = :txId")
    suspend fun totalForTransaction(txId: Long): Long

    /** All entries across all transactions, for CSV export + backup. */
    @Query("SELECT * FROM reimbursement_entries ORDER BY createdAt, id")
    suspend fun getAll(): List<ReimbursementEntry>
}
```

- [ ] **Step 2: Wire the DAO into the repository** — in `TransactionRepository.kt`:

(a) Add the constructor parameter after `private val fundingSourceDao: FundingSourceDao,` (line 82):

```kotlin
    private val reimbursementEntryDao: ReimbursementEntryDao,
```

(b) In the secondary `constructor(...)` delegation (the `: this(...)` block, ~line 100–120), add this argument alongside the other `database.xxxDao()` wirings (e.g. after `fundingSourceDao = database.fundingSourceDao(),`):

```kotlin
        reimbursementEntryDao = database.reimbursementEntryDao(),
```

(c) **Keep** the existing `setTransactionReimbursed` (lines 372–374) for now — Task 8 removes it together with its only caller, so removing it here would break the Task 4 compile gate. **Add** the new entry API alongside it (`FundingSourceKind` and `ReimbursementEntry` are in the same `cy.txtracker.data` package, so no new imports are needed):

```kotlin
    // Reimbursement entries (multi-person). The cached Transaction.reimbursedMinor is kept in
    // step after every mutation so all net-spend SQL stays unchanged.

    fun observeReimbursementEntries(txId: Long): Flow<List<ReimbursementEntry>> =
        reimbursementEntryDao.observeForTransaction(txId)

    suspend fun getReimbursementEntries(txId: Long): List<ReimbursementEntry> =
        reimbursementEntryDao.getForTransaction(txId)

    /** All entries across all transactions, grouped by transactionId. Used by CSV export. */
    suspend fun getReimbursementEntriesByTransaction(): Map<Long, List<ReimbursementEntry>> =
        reimbursementEntryDao.getAll().groupBy { it.transactionId }

    suspend fun addReimbursementEntry(
        txId: Long,
        amountMinor: Long,
        destinationKind: FundingSourceKind,
        personLabel: String?,
        now: Instant = Clock.System.now(),
    ) = database.withTransaction {
        reimbursementEntryDao.insert(
            ReimbursementEntry(
                transactionId = txId,
                amountMinor = amountMinor,
                destinationKind = destinationKind,
                personLabel = personLabel?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now,
            ),
        )
        recomputeReimbursedTotal(txId)
    }

    suspend fun updateReimbursementEntry(entry: ReimbursementEntry) = database.withTransaction {
        reimbursementEntryDao.update(
            entry.copy(personLabel = entry.personLabel?.trim()?.takeIf { it.isNotEmpty() }),
        )
        recomputeReimbursedTotal(entry.transactionId)
    }

    suspend fun deleteReimbursementEntry(entry: ReimbursementEntry) = database.withTransaction {
        reimbursementEntryDao.delete(entry)
        recomputeReimbursedTotal(entry.transactionId)
    }

    /** Re-derives and writes the cached `Transaction.reimbursedMinor` from the entry rows. */
    private suspend fun recomputeReimbursedTotal(txId: Long) {
        val total = reimbursementEntryDao.totalForTransaction(txId)
        transactionDao.updateReimbursed(txId, total.takeIf { it > 0 })
    }
```

> Keep the existing `transactionDao.updateReimbursed` DAO query (lines 327–328) — it remains the single writer of the cached column, now also called from `recomputeReimbursedTotal`. The old public `setTransactionReimbursed` wrapper stays until Task 8 removes it with its caller.

- [ ] **Step 3: Create the DAO test** — `ReimbursementEntryDaoTest.kt` (androidTest). Mirror `FundingSourceDaoTest.kt` for the in-memory DB + dao setup (open it first and copy its `@Before`/`@After` and `Room.inMemoryDatabaseBuilder` boilerplate verbatim, changing only the dao references). Cover: insert→observe ordering; recompute (via repository) sets the cached `reimbursedMinor` and clears it to null when the last entry is deleted; cascade delete when the parent transaction is removed.

```kotlin
package cy.txtracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReimbursementEntryDaoTest {

    private lateinit var db: TxDatabase
    private lateinit var dao: ReimbursementEntryDao
    private lateinit var txDao: TransactionDao

    private val t0 = Instant.parse("2026-06-02T00:00:00Z")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TxDatabase::class.java,
        ).build()
        dao = db.reimbursementEntryDao()
        txDao = db.transactionDao()
    }

    @After fun tearDown() { db.close() }

    private fun insertTx(dedupe: String, reimbursed: Long? = null): Long = runBlocking {
        txDao.insert(
            Transaction(
                amountMinor = 10000,
                currency = "MYR",
                merchantRaw = "M",
                merchantNormalized = "M",
                categoryId = null,
                description = null,
                occurredAt = t0,
                timeBucket = cy.txtracker.domain.TimeBucket.MIDDAY,
                sourceApp = "manual",
                rawText = null,
                direction = Direction.OUT,
                createdAt = t0,
                notificationDedupeKey = dedupe,
                reimbursedMinor = reimbursed,
            ),
        )
    }

    @Test fun entries_observed_in_created_order() = runBlocking {
        val txId = insertTx("k1")
        dao.insert(ReimbursementEntry(transactionId = txId, amountMinor = 1000, destinationKind = FundingSourceKind.DEBIT_BANK, createdAt = t0))
        dao.insert(ReimbursementEntry(transactionId = txId, amountMinor = 1200, destinationKind = FundingSourceKind.E_WALLET, createdAt = t0))
        val entries = dao.observeForTransaction(txId).first()
        assertThat(entries.map { it.amountMinor }).containsExactly(1000L, 1200L).inOrder()
        assertThat(dao.totalForTransaction(txId)).isEqualTo(2200L)
    }

    @Test fun deleting_parent_transaction_cascades_entries() = runBlocking {
        val txId = insertTx("k2")
        dao.insert(ReimbursementEntry(transactionId = txId, amountMinor = 500, destinationKind = FundingSourceKind.CASH, createdAt = t0))
        txDao.delete(txId)
        assertThat(dao.getForTransaction(txId)).isEmpty()
    }
}
```

> If `TransactionDao.delete` has a different signature than `delete(id: Long)`, adjust the call to match (check the DAO). Foreign keys are enforced by Room by default, so the cascade applies.

- [ ] **Step 4: Build — compile main + run unit tests + compile androidTest:**

Run: `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL. (This is the first full compile after Tasks 2–4; Room now finds the dao + migration. The v13 schema JSON is generated at `app/schemas/cy.txtracker.data.TxDatabase/13.json`.)

- [ ] **Step 5: Verify the schema export exists:**

Run: `Test-Path app/schemas/cy.txtracker.data.TxDatabase/13.json`
Expected: `True`. Stage it with the commit.

- [ ] **Step 6: Commit:**

```bash
git add app/src/main/java/cy/txtracker/data/ReimbursementEntryDao.kt app/src/main/java/cy/txtracker/data/TransactionRepository.kt app/src/androidTest/java/cy/txtracker/data/ReimbursementEntryDaoTest.kt app/schemas/cy.txtracker.data.TxDatabase/13.json
git commit -m "Reimbursement: entry DAO + repository CRUD with cached-sum recompute"
```

---

## Task 5: CSV builder — funding columns + multi-entry category cells

**Files:**
- Modify: `app/src/main/java/cy/txtracker/export/CsvExporter.kt`
- Test: `app/src/test/java/cy/txtracker/export/BuildCsvTest.kt` (rewrite)

This task changes `buildCsv`'s signature and column layout, so the whole test file is rewritten.

- [ ] **Step 1: Rewrite `BuildCsvTest.kt`** for the new layout. Replace the ENTIRE file with:

```kotlin
package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import cy.txtracker.data.Direction
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.ReimbursementEntry
import cy.txtracker.data.Transaction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import org.junit.Test

class BuildCsvTest {

    private val food = Category(id = 1, name = "Food", color = 0, isCustom = false, sortOrder = 0)
    private val transport = Category(id = 2, name = "Transport", color = 0, isCustom = false, sortOrder = 1)
    private val categories = listOf(food, transport)

    // Header: date,description,Food,Transport,Unverified,Credit Card,E-Wallet,Debit/Transfer,Cash
    private val header = "date,description,Food,Transport,Unverified,Credit Card,E-Wallet,Debit/Transfer,Cash"

    private fun tx(
        id: Long = 0,
        amountMinor: Long,
        merchant: String,
        description: String? = null,
        categoryId: Long? = null,
        occurredAt: Instant = Instant.parse("2026-05-09T04:30:00Z"),  // 12:30 KL
        fundingSourceId: Long? = null,
    ) = Transaction(
        id = id,
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
    )

    private fun entry(txId: Long, amountMinor: Long, kind: FundingSourceKind) =
        ReimbursementEntry(
            transactionId = txId,
            amountMinor = amountMinor,
            destinationKind = kind,
            createdAt = Instant.parse("2026-05-09T04:30:00Z"),
        )

    // ─── Header & basic placement ───────────────────────────────────────────────────────

    @Test
    fun header_drops_source_and_appends_four_bucket_columns() {
        val csv = buildCsv(transactions = emptyList(), categories = categories)
        assertThat(csv.lines().first()).isEqualTo(header)
    }

    @Test
    fun custom_category_added_appears_before_unverified() {
        val custom = Category(id = 3, name = "Pets", color = 0, isCustom = true, sortOrder = 2)
        val csv = buildCsv(transactions = emptyList(), categories = categories + custom)
        assertThat(csv.lines().first()).isEqualTo(
            "date,description,Food,Transport,Pets,Unverified,Credit Card,E-Wallet,Debit/Transfer,Cash",
        )
    }

    @Test
    fun single_tx_lands_in_category_column_only_no_funding_when_unlinked() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1250, merchant = "PJ Cafe", description = "lunch", categoryId = food.id)),
            categories = categories,
        )
        // Food=12.50; Transport, Unverified, and all four funding columns blank.
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,lunch,12.50,,,,,,")
    }

    @Test
    fun uncategorized_amount_lands_in_unverified_column() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1500, merchant = "Mystery", categoryId = null)),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,,,15.00,,,,")
    }

    @Test
    fun multiple_txs_same_day_same_category_collapse_into_a_formula() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1250, merchant = "Cafe A", description = "lunch", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T04:00:00Z")),
                tx(amountMinor = 400, merchant = "Cafe B", description = "coffee", categoryId = food.id,
                    occurredAt = Instant.parse("2026-05-09T08:00:00Z")),
            ),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,\"lunch, coffee\",=12.50+4.00,,,,,,")
    }

    @Test
    fun description_with_comma_is_quoted_and_escaped() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 1000, merchant = "A", description = "coffee, fast", categoryId = food.id)),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,\"coffee, fast\",10.00,,,,,,")
    }

    @Test
    fun deleted_category_falls_through_to_unverified() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 500, merchant = "A", categoryId = 99L)),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,,,5.00,,,,")
    }

    // ─── Funding-source columns (positives) ─────────────────────────────────────────────

    @Test
    fun gross_amount_lands_in_its_bucket_column() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1000, merchant = "STARBUCKS", categoryId = food.id, fundingSourceId = 10L),
            ),
            categories = categories,
            fundingSourcesById = mapOf(10L to fs(10L, FundingSourceKind.CREDIT_CARD)),
        )
        // Food=10.00 and Credit Card=10.00 (gross); other buckets blank.
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,10.00,,,10.00,,,")
    }

    @Test
    fun two_txs_same_bucket_same_day_form_a_funding_formula() {
        val csv = buildCsv(
            transactions = listOf(
                tx(amountMinor = 1000, merchant = "A", categoryId = food.id, fundingSourceId = 10L,
                    occurredAt = Instant.parse("2026-05-09T04:00:00Z")),
                tx(amountMinor = 500, merchant = "B", categoryId = food.id, fundingSourceId = 10L,
                    occurredAt = Instant.parse("2026-05-09T08:00:00Z")),
            ),
            categories = categories,
            fundingSourcesById = mapOf(10L to fs(10L, FundingSourceKind.CREDIT_CARD)),
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,=10.00+5.00,,,=10.00+5.00,,,")
    }

    @Test
    fun unlinked_tx_contributes_to_no_funding_column() {
        val csv = buildCsv(
            transactions = listOf(tx(amountMinor = 300, merchant = "U", categoryId = food.id, fundingSourceId = null)),
            categories = categories,
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,3.00,,,,,,")
    }

    // ─── Reimbursement: category net + funding negatives ────────────────────────────────

    @Test
    fun reimbursement_negatives_land_in_destination_buckets_and_category_nets() {
        // RM100 dinner on credit card; Person A RM10 via bank, Person B RM12 via e-wallet.
        val dinner = tx(id = 1L, amountMinor = 10000, merchant = "Dinner", categoryId = food.id, fundingSourceId = 10L)
        val csv = buildCsv(
            transactions = listOf(dinner),
            categories = categories,
            fundingSourcesById = mapOf(10L to fs(10L, FundingSourceKind.CREDIT_CARD)),
            reimbursementsByTxId = mapOf(
                1L to listOf(
                    entry(1L, 1000, FundingSourceKind.DEBIT_BANK),
                    entry(1L, 1200, FundingSourceKind.E_WALLET),
                ),
            ),
        )
        // Food net = 100-10-12; Credit Card=+100; E-Wallet=-12; Debit/Transfer=-10; Cash blank.
        assertThat(csv.trimEnd().lines()[1])
            .isEqualTo("2026-05-09,,=100.00-10.00-12.00,,,100.00,-12.00,-10.00,")
    }

    @Test
    fun single_reimbursement_to_a_bucket_renders_bare_negative_literal() {
        val t = tx(id = 5L, amountMinor = 10000, merchant = "A", categoryId = food.id, fundingSourceId = 10L)
        val csv = buildCsv(
            transactions = listOf(t),
            categories = categories,
            fundingSourcesById = mapOf(10L to fs(10L, FundingSourceKind.CREDIT_CARD)),
            reimbursementsByTxId = mapOf(5L to listOf(entry(5L, 5000, FundingSourceKind.DEBIT_BANK))),
        )
        // Food net = 100-50; Credit Card=100.00; Debit/Transfer=-50.00 (bare).
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,=100.00-50.00,,,100.00,,-50.00,")
    }

    @Test
    fun reimbursement_into_same_bucket_that_funded_nets_within_the_cell() {
        // Paid by Debit/Transfer, reimbursed into Debit/Transfer → "=100.00-40.00" in that column.
        val t = tx(id = 7L, amountMinor = 10000, merchant = "A", categoryId = food.id, fundingSourceId = 20L)
        val csv = buildCsv(
            transactions = listOf(t),
            categories = categories,
            fundingSourcesById = mapOf(20L to fs(20L, FundingSourceKind.DEBIT_BANK)),
            reimbursementsByTxId = mapOf(7L to listOf(entry(7L, 4000, FundingSourceKind.DEBIT_BANK))),
        )
        assertThat(csv.trimEnd().lines()[1]).isEqualTo("2026-05-09,,=100.00-40.00,,,,,=100.00-40.00,")
    }
}

private fun fs(id: Long, kind: FundingSourceKind) = FundingSource(
    id = id,
    kind = kind,
    displayName = "Source $id",
    last4 = null,
    sourceAppHint = null,
    isUserNamed = false,
    createdAt = Instant.parse("2026-05-28T00:00:00Z"),
    updatedAt = Instant.parse("2026-05-28T00:00:00Z"),
)
```

- [ ] **Step 2: Run, verify it fails** (old `buildCsv` signature / Source column):

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.export.BuildCsvTest"`
Expected: FAIL — compile error (`reimbursementsByTxId` param doesn't exist) or assertion failures on the old `Source` header.

- [ ] **Step 3: Rewrite the `buildCsv` body + helpers** in `CsvExporter.kt`.

(a) Add import at the top, with the other `cy.txtracker.data` imports:

```kotlin
import cy.txtracker.data.ReimbursementEntry
```

(b) Replace `buildCsv` (lines 176–235) with:

```kotlin
fun buildCsv(
    transactions: List<Transaction>,
    categories: List<Category>,
    fundingSourcesById: Map<Long, FundingSource> = emptyMap(),
    reimbursementsByTxId: Map<Long, List<ReimbursementEntry>> = emptyMap(),
): String {
    val orderedCategories = categories.sortedWith(
        compareBy<Category> { it.sortOrder }.thenBy { it.name },
    )
    val categoryById = orderedCategories.associateBy { it.id }

    val sb = StringBuilder()

    // Header: date, description, <categories>, Unverified, then the four funding buckets.
    sb.append("date,description")
    for (c in orderedCategories) sb.append(',').append(csvEscape(c.name))
    sb.append(",Unverified")
    for (kind in CANONICAL_KIND_ORDER) sb.append(',').append(csvEscape(bucketLabel(kind)))
    sb.append('\n')

    val byDate: Map<LocalDate, List<Transaction>> = transactions
        .groupBy { it.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
        .toSortedMap()

    for ((date, daysTransactions) in byDate) {
        val txs = daysTransactions.sortedBy { it.occurredAt }

        sb.append(formatDate(date))

        // Description column.
        val descriptions = txs.mapNotNull { it.description?.takeIf { d -> d.isNotBlank() } }
        sb.append(',').append(csvEscape(descriptions.joinToString(", ")))

        // Per-category columns — net (gross minus each reimbursement entry inline).
        for (c in orderedCategories) {
            sb.append(',')
            sb.append(buildAmountCell(txs.filter { it.categoryId == c.id }.map { categoryTerm(it, reimbursementsByTxId) }))
        }

        // Unverified column.
        sb.append(',')
        val unverified = txs.filter { it.categoryId == null || categoryById[it.categoryId] == null }
        sb.append(buildAmountCell(unverified.map { categoryTerm(it, reimbursementsByTxId) }))

        // Funding-bucket columns: gross positives (by the tx's source kind) + reimbursement
        // negatives (by each entry's destinationKind), in canonical order. Positives first.
        for (kind in CANONICAL_KIND_ORDER) {
            sb.append(',')
            val positives = txs
                .filter { tx -> tx.fundingSourceId?.let { fundingSourcesById[it]?.kind } == kind }
                .map { "+${formatAmount(it.amountMinor)}" }
            val negatives = txs
                .flatMap { reimbursementsByTxId[it.id] ?: emptyList() }
                .filter { it.destinationKind == kind }
                .map { "-${formatAmount(it.amountMinor)}" }
            sb.append(csvEscape(buildFundingCell(positives + negatives)))
        }

        sb.append('\n')
    }

    return sb.toString()
}
```

(c) Replace the old `buildAmountCell` (lines 266–273) with the term-based pair below, and add `categoryTerm` + `buildFundingCell`:

```kotlin
/**
 * A category term is `(grossAmountMinor, reimbursementAmountsMinor)`. The cell subtracts
 * each reimbursement inline so it evaluates to net spend.
 */
private fun categoryTerm(
    tx: Transaction,
    reimbursementsByTxId: Map<Long, List<ReimbursementEntry>>,
): Pair<Long, List<Long>> =
    tx.amountMinor to (reimbursementsByTxId[tx.id]?.map { it.amountMinor } ?: emptyList())

/**
 * Builds one category column for a day:
 *   - empty                                  → ""
 *   - one plain term (no reimbursement)      → "12.50"
 *   - anything else                          → "=t1+t2+..."  where a reimbursed term is
 *                                              "amount-r1-r2"  (e.g. "=100.00-10.00-12.00+30.00")
 */
private fun buildAmountCell(terms: List<Pair<Long, List<Long>>>): String = when {
    terms.isEmpty() -> ""
    terms.size == 1 && terms[0].second.isEmpty() -> formatAmount(terms[0].first)
    else -> terms.joinToString(prefix = "=", separator = "+") { (amount, reimbs) ->
        buildString {
            append(formatAmount(amount))
            reimbs.forEach { append('-').append(formatAmount(it)) }
        }
    }
}

/**
 * Builds one funding-bucket column from signed terms ("+100.00", "-10.00"):
 *   - empty      → ""
 *   - one term   → the term with any leading '+' stripped ("100.00" or "-10.00")
 *   - many terms → "=" + concatenation with the leading '+' stripped ("=100.00-10.00")
 */
private fun buildFundingCell(signedTerms: List<String>): String = when {
    signedTerms.isEmpty() -> ""
    signedTerms.size == 1 -> signedTerms[0].removePrefix("+")
    else -> "=" + signedTerms.joinToString(separator = "").removePrefix("+")
}
```

(d) Update the `buildCsv` KDoc above the function (lines 158–175) so it describes the funding-bucket columns instead of the `Source` column. Replace the `Source`-cell bullet with:

```
 *   - The four funding-bucket columns (Credit Card, E-Wallet, Debit/Transfer, Cash) carry
 *     the GROSS amount of each transaction funded from that bucket (positive), plus each
 *     reimbursement entry as a negative in its destination bucket. A cell with a single term
 *     is a bare literal; multiple terms form a "=" formula. Unlinked transactions contribute
 *     to no funding column.
```

- [ ] **Step 4: Run, verify pass:**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.export.BuildCsvTest"`
Expected: PASS.

- [ ] **Step 5: Commit:**

```bash
git add app/src/main/java/cy/txtracker/export/CsvExporter.kt app/src/test/java/cy/txtracker/export/BuildCsvTest.kt
git commit -m "CSV: replace Source column with funding-bucket columns + reimbursement negatives"
```

---

## Task 6: Wire entries into `CsvExporter` I/O paths

**Files:**
- Modify: `app/src/main/java/cy/txtracker/export/CsvExporter.kt`

> No unit test (these are I/O glue methods). `buildCsv` is already covered. Gate is compile.

- [ ] **Step 1: Thread the entries map through `writeCsv`** — replace `writeCsv` (lines 148–156):

```kotlin
fun writeCsv(
    transactions: List<Transaction>,
    categories: List<Category>,
    fundingSourcesById: Map<Long, FundingSource> = emptyMap(),
    reimbursementsByTxId: Map<Long, List<ReimbursementEntry>> = emptyMap(),
    output: OutputStream,
) {
    val csv = buildCsv(transactions, categories, fundingSourcesById, reimbursementsByTxId)
    output.write(csv.toByteArray(Charsets.UTF_8))
}
```

- [ ] **Step 2: Fetch entries in `exportCsv`** — replace its body (lines 49–60):

```kotlin
    suspend fun exportCsv(currency: String, range: ExportDateRange? = null): Uri {
        val transactions = filterByRange(
            repository.getAllTransactionsOnceForCurrency(currency),
            range,
        )
        val categories = repository.getAllCategoriesOnce()
        val fundingSourcesById = repository.observeFundingSources().first().associateBy { it.id }
        val reimbursementsByTxId = repository.getReimbursementEntriesByTransaction()
        val dir = exportDir()
        val file = File(dir, csvFileName(currency, range))
        file.outputStream().use {
            writeCsv(transactions, categories, fundingSourcesById, reimbursementsByTxId, it)
        }
        return uriFor(file)
    }
```

- [ ] **Step 3: Same for `exportAllCurrenciesZip`** — fetch once before the loop and pass it. Add after the `fundingSourcesById` line (line 69):

```kotlin
        val reimbursementsByTxId = repository.getReimbursementEntriesByTransaction()
```

and change the `writeCsv(...)` call inside the loop (line 80) to:

```kotlin
                writeCsv(rows, categories, fundingSourcesById, reimbursementsByTxId, zip)
```

> The map is keyed by transactionId (globally unique), so passing the full map to every per-currency CSV is correct — only the current currency's transactions look entries up.

- [ ] **Step 4: Compile + full unit suite:**

Run: `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit:**

```bash
git add app/src/main/java/cy/txtracker/export/CsvExporter.kt
git commit -m "CSV: load reimbursement entries in export I/O paths"
```

---

## Task 7: Backup v9 → v10

**Files:**
- Modify: `app/src/main/java/cy/txtracker/export/Backup.kt`, `BackupExporter.kt`, `TransactionRepository.kt` (applyBackup)
- Test: `app/src/test/java/cy/txtracker/export/ReimbursedBackupTest.kt`

The backup links entries to their parent transaction by the parent's `notificationDedupeKey` (transaction ids regenerate on import).

- [ ] **Step 1: Add failing serialization tests** — append to `ReimbursedBackupTest.kt` (inside the class):

```kotlin
    @Test
    fun reimbursement_entry_survives_round_trip() {
        val e = BackupReimbursementEntry(
            transactionDedupeKey = "k",
            amountMinor = 1000,
            destinationKind = "DEBIT_BANK",
            personLabel = "Person A",
            createdAt = Instant.parse("2026-05-09T04:30:00Z"),
        )
        val decoded = json.decodeFromString<BackupReimbursementEntry>(json.encodeToString(e))
        assertThat(decoded.amountMinor).isEqualTo(1000)
        assertThat(decoded.destinationKind).isEqualTo("DEBIT_BANK")
        assertThat(decoded.personLabel).isEqualTo("Person A")
    }

    @Test
    fun backup_defaults_reimbursement_entries_to_empty_for_v9() {
        // A v9 payload has no reimbursementEntries field; it must parse to an empty list.
        val v9 = """{"version":9,"exportedAt":"2026-05-09T04:30:00Z","categories":[],
            "merchantMappings":[],"merchantDescriptionMappings":[],"categoryDescriptionMappings":[]}""".trimIndent()
        val decoded = json.decodeFromString<Backup>(v9)
        assertThat(decoded.reimbursementEntries).isEmpty()
    }
```

- [ ] **Step 2: Run, verify fail** (`BackupReimbursementEntry` / field undefined):

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.export.ReimbursedBackupTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Update `Backup.kt`** — bump version, add the field and the new type.

(a) Change `const val CURRENT_VERSION = 9` (line 54) to `10`.

(b) Add to the version-history KDoc (after the `v9` line, line 33):

```
 *   v10 – added [reimbursementEntries] (multi-person reimbursement entries; the per-tx
 *         reimbursedMinor is the cached sum and is also carried on each BackupTransaction)
```

(c) Add the field to `Backup` (after `fundingSources: List<BackupFundingSource> = emptyList(),`, line 51):

```kotlin
    val reimbursementEntries: List<BackupReimbursementEntry> = emptyList(),
```

(d) Add the new serializable type (after `BackupFundingSource`, near line 17):

```kotlin
@Serializable
data class BackupReimbursementEntry(
    /** Parent transaction's notificationDedupeKey — stable across reinstalls/devices. */
    val transactionDedupeKey: String,
    val amountMinor: Long,
    val destinationKind: String,   // FundingSourceKind name
    val personLabel: String?,
    val createdAt: Instant,
)
```

- [ ] **Step 4: Serialize entries in `BackupExporter.kt`** — build the entry list keyed by parent dedupe key, scoped to the exported transactions.

After the `txs` are resolved (line 68) add a dedupe-key set + the entries fetch. Insert before `val fundingSources = ...` (line 70):

```kotlin
        // Reimbursement entries, linked to their parent by dedupe key, scoped to exported txs.
        val txIdToDedupe = txs.associate { it.id to it.notificationDedupeKey }
        val exportedReimbursements = repository.getReimbursementEntriesByTransaction()
            .flatMap { (txId, entries) ->
                val dedupe = txIdToDedupe[txId] ?: return@flatMap emptyList()
                entries.map { e ->
                    BackupReimbursementEntry(
                        transactionDedupeKey = dedupe,
                        amountMinor = e.amountMinor,
                        destinationKind = e.destinationKind.name,
                        personLabel = e.personLabel,
                        createdAt = e.createdAt,
                    )
                }
            }
```

Then add to the `Backup(...)` constructor call (after the `tripWindows = ...` block, before the closing `)`, line 175):

```kotlin
            reimbursementEntries = exportedReimbursements,
```

- [ ] **Step 5: Restore entries in `applyBackup`** (`TransactionRepository.kt`).

(a) In the transaction insert loop (step 12, lines 1328–1353), capture the new row id by dedupe key. Add a map before the loop (just before `var transactionsAdded = 0`, line 1327):

```kotlin
        val newTxIdByDedupe = mutableMapOf<String, Long>()
```

and inside the loop, after `if (rowId >= 0) transactionsAdded++` (line 1352), add:

```kotlin
            if (rowId >= 0) newTxIdByDedupe[bt.notificationDedupeKey] = rowId
```

(b) Add a new step 13 after the transaction loop closes (after line 1353, before `ImportResult(...)`):

```kotlin
        // 13. Reimbursement entries. Only attach to transactions THIS import inserted
        //     (newTxIdByDedupe) — when a local tx won the dedupe conflict, its entries stay
        //     local. v10 backups carry explicit entries; for older backups (or any inserted
        //     reimbursed tx with no matching entries) synthesize a single DEBIT_BANK entry so
        //     the funding columns reconcile, mirroring the v12→v13 migration backfill.
        val entriesByDedupe = backup.reimbursementEntries.groupBy { it.transactionDedupeKey }
        for ((dedupe, newId) in newTxIdByDedupe) {
            val backupEntries = entriesByDedupe[dedupe]
            if (!backupEntries.isNullOrEmpty()) {
                for (be in backupEntries) {
                    val kind = runCatching { FundingSourceKind.valueOf(be.destinationKind) }.getOrNull()
                        ?: FundingSourceKind.DEBIT_BANK
                    reimbursementEntryDao.insert(
                        ReimbursementEntry(
                            transactionId = newId,
                            amountMinor = be.amountMinor,
                            destinationKind = kind,
                            personLabel = be.personLabel,
                            createdAt = be.createdAt,
                        ),
                    )
                }
            } else {
                val reimbursed = backup.transactions
                    .firstOrNull { it.notificationDedupeKey == dedupe }?.reimbursedMinor
                if (reimbursed != null && reimbursed > 0) {
                    reimbursementEntryDao.insert(
                        ReimbursementEntry(
                            transactionId = newId,
                            amountMinor = reimbursed,
                            destinationKind = FundingSourceKind.DEBIT_BANK,
                            personLabel = null,
                            createdAt = backup.transactions
                                .first { it.notificationDedupeKey == dedupe }.createdAt,
                        ),
                    )
                }
            }
        }
```

> The inserted transaction already carries `bt.reimbursedMinor` (the cached sum), so it stays consistent with the entries created here — no recompute needed.

- [ ] **Step 6: Verify `BackupImporter` accepts v10** — `SUPPORTED_VERSIONS = 5..Backup.CURRENT_VERSION` (line 60) auto-extends to `5..10`. No edit needed; confirm by reading.

- [ ] **Step 7: Run, verify pass:**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.export.ReimbursedBackupTest"`
Expected: PASS.

- [ ] **Step 8: Extend `ApplyBackupTest` (androidTest) — write only.** Open `app/src/androidTest/java/cy/txtracker/data/ApplyBackupTest.kt`, copy its construction pattern, and add two cases: (1) a v10 `Backup` with one transaction + two `BackupReimbursementEntry` rows restores both entries against the new tx id and leaves `reimbursedMinor` as the cached sum; (2) a v9-style `Backup` (empty `reimbursementEntries`) with a `reimbursedMinor = 5000` transaction synthesizes one `DEBIT_BANK` entry. Use the same `BackupReimbursementEntry`/`Backup` builders as the unit test. Gate locally with compile only:

Run: `./gradlew.bat :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit:**

```bash
git add app/src/main/java/cy/txtracker/export/Backup.kt app/src/main/java/cy/txtracker/export/BackupExporter.kt app/src/main/java/cy/txtracker/data/TransactionRepository.kt app/src/test/java/cy/txtracker/export/ReimbursedBackupTest.kt app/src/androidTest/java/cy/txtracker/data/ApplyBackupTest.kt
git commit -m "Backup: v10 reimbursement entries with v9 DEBIT_BANK synth on restore"
```

---

## Task 8: Edit-sheet entry-list UI

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt`, `EditTransactionSheet.kt`, `TransactionRepository.kt` (delete the now-unused `setTransactionReimbursed`)

> Compose UI — no instrumentation tests (emulator forbidden). Gate is `:app:compileDebugKotlin`. Verify behavior visually later via the `run`/`verify` skills if desired.

- [ ] **Step 1: ViewModel — carry + mutate entries.**

(a) Add to `EditUiState.Editing` (after `fundingSource: FundingSource? = null,`, line 31):

```kotlin
        val reimbursements: List<ReimbursementEntry> = emptyList(),
```

(b) Add the import (top of file): `import cy.txtracker.data.ReimbursementEntry` and `import cy.txtracker.data.FundingSourceKind`.

(c) In `load(...)` (lines 43–60), populate it. Add to the `EditUiState.Editing(...)` constructor call:

```kotlin
                    reimbursements = repository.getReimbursementEntries(transactionId),
```

(d) Delete the old `setReimbursed` ViewModel method (lines 101–111) and add the entry mutators below. Then delete the now-unused `setTransactionReimbursed` wrapper from `TransactionRepository.kt` (lines 372–374) — this was its only caller. Add these methods to the ViewModel:

```kotlin
    fun addReimbursement(transactionId: Long, amountMinor: Long, destinationKind: FundingSourceKind, personLabel: String?) {
        viewModelScope.launch {
            repository.addReimbursementEntry(transactionId, amountMinor, destinationKind, personLabel)
            refreshReimbursements(transactionId)
        }
    }

    fun updateReimbursement(entry: ReimbursementEntry) {
        viewModelScope.launch {
            repository.updateReimbursementEntry(entry)
            refreshReimbursements(entry.transactionId)
        }
    }

    fun removeReimbursement(entry: ReimbursementEntry) {
        viewModelScope.launch {
            repository.deleteReimbursementEntry(entry)
            refreshReimbursements(entry.transactionId)
        }
    }

    /** Reloads both the entry list and the transaction (for the cached reimbursedMinor). */
    private suspend fun refreshReimbursements(transactionId: Long) {
        val tx = repository.getTransaction(transactionId) ?: return
        val entries = repository.getReimbursementEntries(transactionId)
        val current = _state.value
        if (current is EditUiState.Editing) {
            _state.value = current.copy(transaction = tx, reimbursements = entries)
        }
    }
```

- [ ] **Step 2: Sheet — replace the single-amount section with an entry list.**

(a) Update the `EditingContent` signature (lines 140–152): remove `onReimbursedChange: (Long?) -> Unit,` and add:

```kotlin
    onAddReimbursement: (Long, FundingSourceKind, String?) -> Unit,
    onUpdateReimbursement: (ReimbursementEntry) -> Unit,
    onRemoveReimbursement: (ReimbursementEntry) -> Unit,
```

(b) Update the `EditingContent(...)` call (lines 88–100): remove the `onReimbursedChange = {...}` block and add:

```kotlin
                onAddReimbursement = { amt, kind, person ->
                    viewModel.addReimbursement(transactionId, amt, kind, person)
                },
                onUpdateReimbursement = { entry -> viewModel.updateReimbursement(entry) },
                onRemoveReimbursement = { entry -> viewModel.removeReimbursement(entry) },
```

(c) Replace the entire "Reimbursed by others" block (lines 335–388) with an entry-list section. Imports to add at the top of the file: `import cy.txtracker.data.FundingSourceKind`, `import cy.txtracker.data.ReimbursementEntry`, `import cy.txtracker.domain.isValidReimbursementTotal`, `import cy.txtracker.ui.common.fundingBucketLabel`, `import cy.txtracker.ui.common.KIND_ORDER`. Remove the now-unused `import cy.txtracker.domain.isValidReimbursedMinor`.

```kotlin
        Text(text = "Reimbursed by others", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        run {
            val entries = state.reimbursements
            val totalReimbursed = entries.sumOf { it.amountMinor }
            if (entries.isEmpty()) {
                Text(
                    text = "No reimbursements",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Others returned ${formatAmount(totalReimbursed, "").trim()} of " +
                        "${formatAmount(tx.amountMinor, "").trim()} ${tx.currency}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                entries.forEach { entry ->
                    ReimbursementRow(
                        entry = entry,
                        currency = tx.currency,
                        onRemove = { onRemoveReimbursement(entry) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Add-new editor: amount + destination bucket + optional person.
            var newAmount by remember(tx.id, entries.size) { mutableStateOf("") }
            var newKind by remember(tx.id, entries.size) { mutableStateOf(FundingSourceKind.DEBIT_BANK) }
            var newPerson by remember(tx.id, entries.size) { mutableStateOf("") }

            OutlinedTextField(
                value = newAmount,
                onValueChange = { newAmount = it },
                label = { Text("Reimbursed amount (${tx.currency})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            KindDropdown(selected = newKind, onSelect = { newKind = it })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newPerson,
                onValueChange = { newPerson = it },
                label = { Text("Who (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            val parsedNew = parseAmountMinor(newAmount)
            // Validate the prospective set: existing entries + this new amount.
            val prospective = entries.map { it.amountMinor } + (parsedNew ?: 0L)
            val canAdd = parsedNew != null && isValidReimbursementTotal(prospective, tx.amountMinor)
            Button(
                onClick = {
                    onAddReimbursement(parsedNew!!, newKind, newPerson.takeIf { it.isNotBlank() })
                    newAmount = ""; newPerson = ""
                },
                enabled = canAdd,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add reimbursement") }
        }
```

(d) Add two private composables at the bottom of `EditTransactionSheet.kt` (after the last existing private composable):

```kotlin
@Composable
private fun ReimbursementRow(
    entry: ReimbursementEntry,
    currency: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                append("−").append(formatAmount(entry.amountMinor, "").trim()).append(" ")
                append(currency).append(" → ").append(fundingBucketLabel(entry.destinationKind))
                entry.personLabel?.let { append("  (").append(it).append(")") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = cy.txtracker.ui.theme.ReimbursedAccent,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindDropdown(
    selected: FundingSourceKind,
    onSelect: (FundingSourceKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = fundingBucketLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text("Landed in") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            KIND_ORDER.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(fundingBucketLabel(kind)) },
                    onClick = { onSelect(kind); expanded = false },
                )
            }
        }
    }
}
```

> Add the missing Material3 imports used above: `Button`, `TextButton`, `DropdownMenuItem`, `ExposedDropdownMenuBox`, `ExposedDropdownMenu`, `ExposedDropdownMenuDefaults`, `ExperimentalMaterial3Api`, and `androidx.compose.foundation.text.KeyboardOptions` / `androidx.compose.ui.text.input.KeyboardType` / `androidx.compose.ui.text.input.ImeAction` if not already imported. Let the compiler tell you which are missing.

- [ ] **Step 3: Compile-gate:**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (resolve any missing imports until it passes).

- [ ] **Step 4: Commit:**

```bash
git add app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt app/src/main/java/cy/txtracker/data/TransactionRepository.kt
git commit -m "Edit sheet: multi-entry reimbursement list (amount + bucket + person)"
```

---

## Task 9: Add-Manual entry-list UI + post-insert persist

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/manual/AddManualViewModel.kt`, `AddManualSheet.kt`

The manual sheet has no transaction id until after insert, so entries are buffered in UI state and persisted right after the row is created.

- [ ] **Step 1: ViewModel — buffer entries, persist after insert.**

(a) Add a draft type at the top of `AddManualViewModel.kt` (near `AddManualUiState`):

```kotlin
/** A reimbursement the user added before the transaction exists. Persisted after insert. */
data class DraftReimbursement(
    val amountMinor: Long,
    val destinationKind: FundingSourceKind,
    val personLabel: String?,
)
```

(b) Replace the single `reimbursedText` field + `reimbursedMinor` computed prop in `AddManualUiState` (lines ~50–54 of the data class) with a list and a derived total:

```kotlin
    /** Reimbursements staged before save. */
    val reimbursements: List<DraftReimbursement> = emptyList(),
```

and replace the `reimbursedMinor` getter with:

```kotlin
    /** Cached reimbursed total for the row, or null when none. Validated at add-time. */
    val reimbursedMinor: Long?
        get() = reimbursements.sumOf { it.amountMinor }.takeIf { it > 0 }
```

(c) Replace `setReimbursed(text:)` (lines 137–146) with add/remove:

```kotlin
    fun addReimbursement(amountMinor: Long, destinationKind: FundingSourceKind, personLabel: String?) {
        _state.update {
            it.copy(reimbursements = it.reimbursements + DraftReimbursement(amountMinor, destinationKind, personLabel?.trim()?.takeIf { p -> p.isNotEmpty() }))
        }
    }

    fun removeReimbursement(index: Int) {
        _state.update { it.copy(reimbursements = it.reimbursements.filterIndexed { i, _ -> i != index }) }
    }
```

(d) In `save(...)` (lines 150–171), capture the new row id and persist drafts. Replace the `repository.addManualTransaction(...)` call so its result is used:

```kotlin
        viewModelScope.launch {
            val newId = repository.addManualTransaction(
                amountMinor = amount,
                merchantRaw = s.merchantText.trim(),
                categoryId = s.categoryId,
                description = s.descriptionText.takeIf { it.isNotBlank() },
                occurredAt = occurredAt,
                currency = s.currency,
                fundingSourceId = s.fundingSource?.id,
                reimbursedMinor = s.reimbursedMinor,
            )
            if (newId != null) {
                s.reimbursements.forEach { d ->
                    repository.addReimbursementEntry(newId, d.amountMinor, d.destinationKind, d.personLabel)
                }
            }
            _state.update { it.copy(isSaving = false) }
            onSaved()
        }
```

> `addManualTransaction` already writes `reimbursedMinor = s.reimbursedMinor` on the row; `addReimbursementEntry`'s recompute then re-derives the same value — consistent. Keep passing `reimbursedMinor` so a row inserted without entries (none) stays null.

(e) Add `import cy.txtracker.data.FundingSourceKind` if not present.

- [ ] **Step 2: Sheet — entry list + add editor.** In `AddManualSheet.kt`:

(a) Update the `Content` signature (lines 99–112): replace `onReimbursedChange: (String) -> Unit,` with:

```kotlin
    onAddReimbursement: (Long, FundingSourceKind, String?) -> Unit,
    onRemoveReimbursement: (Int) -> Unit,
```

(b) Update the `Content(...)` call (lines 79–93): replace `onReimbursedChange = viewModel::setReimbursed,` with:

```kotlin
    onAddReimbursement = viewModel::addReimbursement,
    onRemoveReimbursement = viewModel::removeReimbursement,
```

(c) Replace the "Reimbursed by others (optional)" block (lines 171–180) with an entry list + add editor mirroring the edit sheet. Reuse the same `parseAmountMinor` + `isValidReimbursementTotal` validation. Imports to add: `cy.txtracker.data.FundingSourceKind`, `cy.txtracker.domain.isValidReimbursementTotal`, `cy.txtracker.ui.common.fundingBucketLabel`, `cy.txtracker.ui.common.KIND_ORDER`, and the dropdown Material3 imports.

```kotlin
Text(text = "Reimbursed by others (optional)", style = MaterialTheme.typography.labelLarge)
Spacer(Modifier.height(8.dp))
state.reimbursements.forEachIndexed { index, d ->
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                append("−").append(formatAmount(d.amountMinor, "").trim()).append(" ")
                append(state.currency).append(" → ").append(fundingBucketLabel(d.destinationKind))
                d.personLabel?.let { append("  (").append(it).append(")") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = cy.txtracker.ui.theme.ReimbursedAccent,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onRemoveReimbursement(index) }) { Text("Remove") }
    }
    Spacer(Modifier.height(8.dp))
}

var rAmount by remember(state.reimbursements.size) { mutableStateOf("") }
var rKind by remember(state.reimbursements.size) { mutableStateOf(FundingSourceKind.DEBIT_BANK) }
var rPerson by remember(state.reimbursements.size) { mutableStateOf("") }

OutlinedTextField(
    value = rAmount,
    onValueChange = { rAmount = it },
    label = { Text("Reimbursed amount (${state.currency})") },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
    modifier = Modifier.fillMaxWidth(),
)
Spacer(Modifier.height(8.dp))
ManualKindDropdown(selected = rKind, onSelect = { rKind = it })
Spacer(Modifier.height(8.dp))
OutlinedTextField(
    value = rPerson,
    onValueChange = { rPerson = it },
    label = { Text("Who (optional)") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
)
Spacer(Modifier.height(8.dp))
run {
    val parsed = parseAmountMinor(rAmount)
    val amt = state.amountMinor
    val prospective = state.reimbursements.map { it.amountMinor } + (parsed ?: 0L)
    val canAdd = parsed != null && amt != null && isValidReimbursementTotal(prospective, amt)
    Button(
        onClick = { onAddReimbursement(parsed!!, rKind, rPerson.takeIf { it.isNotBlank() }); rAmount = ""; rPerson = "" },
        enabled = canAdd,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Add reimbursement") }
}
Spacer(Modifier.height(8.dp))
```

(d) Add a `ManualKindDropdown` private composable identical in shape to `KindDropdown` from Task 8 (copy it; the name differs to avoid a clash if both files were ever merged). Place it at the bottom of `AddManualSheet.kt` with the same Material3 imports.

- [ ] **Step 3: Compile-gate:**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit:**

```bash
git add app/src/main/java/cy/txtracker/ui/manual/AddManualViewModel.kt app/src/main/java/cy/txtracker/ui/manual/AddManualSheet.kt
git commit -m "Add Manual: multi-entry reimbursement list, persisted after insert"
```

---

## Task 10: Full build + final verification

**Files:** none (verification only).

- [ ] **Step 1: Full debug build + all unit tests + androidTest compile:**

Run: `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL; all unit tests green. (Do NOT run connected/androidTest.)

- [ ] **Step 2: Spot-check the schema** — confirm `app/schemas/cy.txtracker.data.TxDatabase/13.json` exists and includes the `reimbursement_entries` table (search the file for `reimbursement_entries`).

- [ ] **Step 3: Confirm no `Source` regressions** — grep the export package for leftover `Source`-column references that should have been removed:

Run: `git grep -n "\",Source\"\|append(\",Source\")" -- app/src/main`
Expected: no matches (the header no longer emits a `Source` column).

- [ ] **Step 4: Final commit (if any schema/stray files remain unstaged):**

```bash
git add -A
git commit -m "Reimbursement/CSV feature: final build + schema export"
```

---

## Notes for the implementer

- **Branch policy:** Stay on the current branch (`main`). Do not create branches or merge. The `feature/share-debit` conflict (Source→bucket columns, SL Debit column) is that branch's documented merge burden — ignore it here.
- **Do not** change any net-spend SQL, `InsightsAggregator`, `SummaryWorker`, or the `HomeScreen`/Foreign row rendering: they read the cached `Transaction.reimbursedMinor`, which the repository keeps correct. If a task tempts you to touch them, stop — that's a sign the cached-sum invariant was broken upstream.
- **Person label** is never written to CSV by design (spec §9).
- If `MigrationTestHelper` / `Room.inMemoryDatabaseBuilder` boilerplate differs from what's shown, the existing `MigrationV11ToV12Test.kt` and `FundingSourceDaoTest.kt` are the authoritative templates — copy their exact setup.
```
