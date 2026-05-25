# Capture Pool Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace capture-all/permissive transaction insertion with a reviewable notification pool, tracked/rejected app management, and auto-hide the Home currency-review chip when empty.

**Architecture:** Keep the existing `TransactionRepository` as the app's write boundary, but add dedicated Room entities/DAOs for captured notifications and rejected sources. Extract the amount-only pipeline into testable pure classes so `TxNotificationListener` only extracts notification text, asks for a capture decision, and writes either a transaction or a pool row. Add Settings-owned Compose screens for pool review and tracked apps; wire navigation through the existing `AppRoute` settings graph.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Hilt, Room v9 schema, WorkManager, kotlinx.datetime, JUnit/Truth/MockK/Turbine.

---

## Important Conflict Notes

Another agent may be implementing `docs/superpowers/specs/2026-05-25-home-list-layout-design.md` at the same time.

Likely overlapping files:

- `app/src/main/java/cy/txtracker/ui/home/HomeUiState.kt`
- `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt`
- `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt`

Rules before each task:

1. Run `git status --short`.
2. If any task file is already modified, inspect it with `git diff -- <file>` and preserve those changes.
3. Do not revert `.claude/settings.local.json` or any other unrelated user/agent changes.
4. Do not run device/emulator tests. Use local unit tests and build checks only unless the user explicitly changes that instruction.
5. Do not commit this plan or implementation unless explicitly asked.

Home-list conflict resolution:

- If the other agent already added `currencyReviewCount` to `HomeUiState`, reuse it.
- If `FilterRow` was deleted by the home-list work, do not recreate it. Add the `currencyReviewCount > 0` visibility rule to the new status-chip row instead.
- If the other agent added category snap-back logic, keep it and add the currency-review snap-back beside it.

---

## Task 1: Add Shared Amount Parser And Source Labels

**Files:**
- Create: `app/src/main/java/cy/txtracker/parsing/NotificationAmountParser.kt`
- Create: `app/src/main/java/cy/txtracker/parsing/SourceLabels.kt`
- Modify: `app/src/main/java/cy/txtracker/parsing/HeuristicExtractor.kt`
- Test: `app/src/test/java/cy/txtracker/parsing/NotificationAmountParserTest.kt`
- Test: `app/src/test/java/cy/txtracker/parsing/SourceLabelsTest.kt`

**Step 1: Write failing parser tests**

Add `NotificationAmountParserTest`:

```kotlin
package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationAmountParserTest {
    @Test fun finds_prefix_amount() {
        val match = NotificationAmountParser.findFirst("Paid RM 1,163.27 today")
        assertThat(match?.amountMinor).isEqualTo(116327L)
        assertThat(match?.currency).isEqualTo("MYR")
    }

    @Test fun finds_suffix_amount() {
        val match = NotificationAmountParser.findFirst("Spent 25.50 USD")
        assertThat(match?.amountMinor).isEqualTo(2550L)
        assertThat(match?.currency).isEqualTo("USD")
    }

    @Test fun rejects_date_like_text() {
        assertThat(NotificationAmountParser.findFirst("Transaction Date: 18MAY2026")).isNull()
    }

    @Test fun resolves_ambiguous_symbol_from_defaults() {
        val match = NotificationAmountParser.findFirst("Paid $12.00", mapOf("$" to "SGD"))
        assertThat(match?.currency).isEqualTo("SGD")
    }
}
```

Add `SourceLabelsTest`:

```kotlin
package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceLabelsTest {
    @Test fun labels_known_malaysian_finance_packages() {
        assertThat(SourceLabels.label("my.com.gxsbank")).isEqualTo("GX Bank")
        assertThat(SourceLabels.label("com.cimb.octo")).isEqualTo("CIMB")
        assertThat(SourceLabels.label(SourcePackages.TOUCH_N_GO)).isEqualTo("TnG")
    }
}
```

**Step 2: Run tests to verify they fail**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "cy.txtracker.parsing.NotificationAmountParserTest" --tests "cy.txtracker.parsing.SourceLabelsTest"
```

Expected: compile fails because `NotificationAmountParser` and `SourceLabels` do not exist.

**Step 3: Implement parser and labels**

`NotificationAmountParser.kt`:

```kotlin
package cy.txtracker.parsing

data class ParsedAmount(
    val amountMinor: Long,
    val currency: String,
)

object NotificationAmountParser {
    private val AMOUNT = Regex(
        """(?:""" +
            """(?<![A-Za-z])(?<prefix>RM|MYR|[\u00A3\u20AC\u00A5\u20B9\u20A9\u20BD\u0E3F$])\s*(?<amtA>(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)""" +
            """|""" +
            """(?<![A-Za-z0-9])(?<amtB>(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)\s*(?<suffix>(?-i:[A-Z]{3}))(?![A-Za-z0-9])""" +
            """)""",
        RegexOption.IGNORE_CASE,
    )

    fun findFirst(
        text: String,
        symbolDefaults: Map<String, String> = emptyMap(),
    ): ParsedAmount? {
        if (text.isBlank()) return null
        val match = AMOUNT.find(text) ?: return null
        val amountStr = match.groups["amtA"]?.value
            ?: match.groups["amtB"]?.value
            ?: return null
        val prefixToken = match.groups["prefix"]?.value
        val suffixToken = match.groups["suffix"]?.value
        return ParsedAmount(
            amountMinor = parseAmountMinor(amountStr),
            currency = Currencies.resolve(prefixToken, suffixToken, symbolDefaults),
        )
    }
}
```

`SourceLabels.kt`:

```kotlin
package cy.txtracker.parsing

object SourceLabels {
    fun label(sourceApp: String): String = when {
        sourceApp == SourcePackages.GOOGLE_WALLET -> "GWallet"
        sourceApp == SourcePackages.GOOGLE_PAY -> "GPay"
        sourceApp.contains("cimb") -> "CIMB"
        sourceApp.contains("maybank") -> "Maybank"
        sourceApp.contains("publicbank") -> "Public Bank"
        sourceApp.contains("rhb") -> "RHB"
        sourceApp.contains("hsbc") -> "HSBC"
        sourceApp.contains("hlb") || sourceApp.contains("hongleong") -> "Hong Leong"
        sourceApp.contains("ambank") -> "AmBank"
        sourceApp.contains("bsn") -> "BSN"
        sourceApp.contains("gxs") || sourceApp.contains("gxbank") -> "GX Bank"
        sourceApp.contains("wise") || sourceApp.contains("transferwise") -> "Wise"
        sourceApp == SourcePackages.TOUCH_N_GO -> "TnG"
        sourceApp == SourcePackages.GRAB -> "Grab"
        else -> sourceApp.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
```

Update `HeuristicExtractor.extract` to call `NotificationAmountParser.findFirst(trimmed, symbolDefaults)` and use the returned `amountMinor` and `currency`. Keep the merchant extraction logic unchanged.

**Step 4: Run tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "cy.txtracker.parsing.NotificationAmountParserTest" --tests "cy.txtracker.parsing.SourceLabelsTest" --tests "cy.txtracker.parsing.HeuristicExtractorTest"
```

Expected: pass.

---

## Task 2: Add Capture Pool Room Schema

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/Entities.kt`
- Modify: `app/src/main/java/cy/txtracker/data/Converters.kt`
- Create: `app/src/main/java/cy/txtracker/data/CapturedNotificationDao.kt`
- Create: `app/src/main/java/cy/txtracker/data/RejectedSourceDao.kt`
- Modify: `app/src/main/java/cy/txtracker/data/TxDatabase.kt`
- Modify: `app/src/main/java/cy/txtracker/di/DatabaseModule.kt`
- Modify: `app/src/androidTest/java/cy/txtracker/data/DbRule.kt`
- Modify: `app/src/androidTest/java/cy/txtracker/data/TxDatabaseTest.kt`
- Create/update generated schema: `app/schemas/cy.txtracker.data.TxDatabase/9.json`

**Step 1: Add migration tests first**

In `TxDatabaseTest.kt`, add tests for v8 to v9:

```kotlin
@Test
fun migrate_8_to_9_moves_review_transactions_with_raw_text_into_pool() {
    helper.createDatabase(TEST_DB, 8).use { db ->
        seedV8Category(db)
        insertV8Transaction(
            db = db,
            merchantRaw = "GX Bank (review)",
            sourceApp = "my.com.gxsbank",
            rawText = "RM9.40 to ML TRADITIONAL DESSERT",
        )
    }

    val migrated = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9_TEST_COPY)

    migrated.query("SELECT COUNT(*) FROM transactions").use { c ->
        assertThat(c.moveToFirst()).isTrue()
        assertThat(c.getInt(0)).isEqualTo(0)
    }
    migrated.query(
        "SELECT packageName, amountMinor, currency, rawText, disposition FROM captured_notifications"
    ).use { c ->
        assertThat(c.moveToFirst()).isTrue()
        assertThat(c.getString(0)).isEqualTo("my.com.gxsbank")
        assertThat(c.getLong(1)).isEqualTo(940L)
        assertThat(c.getString(2)).isEqualTo("MYR")
        assertThat(c.getString(3)).contains("ML TRADITIONAL")
        assertThat(c.getString(4)).isEqualTo("PENDING")
    }
}

@Test
fun migrate_8_to_9_keeps_review_literal_when_raw_text_is_null() {
    helper.createDatabase(TEST_DB, 8).use { db ->
        seedV8Category(db)
        insertV8Transaction(
            db = db,
            merchantRaw = "Shop (review)",
            sourceApp = "manual",
            rawText = null,
        )
    }

    val migrated = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9_TEST_COPY)

    migrated.query("SELECT COUNT(*) FROM transactions").use { c ->
        assertThat(c.moveToFirst()).isTrue()
        assertThat(c.getInt(0)).isEqualTo(1)
    }
    migrated.query("SELECT COUNT(*) FROM captured_notifications").use { c ->
        assertThat(c.moveToFirst()).isTrue()
        assertThat(c.getInt(0)).isEqualTo(0)
    }
}

@Test
fun migrate_8_to_9_creates_rejected_sources() {
    helper.createDatabase(TEST_DB, 8).close()
    val migrated = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9_TEST_COPY)
    migrated.execSQL(
        "INSERT INTO rejected_sources (packageName, rejectedAt) VALUES ('com.chat', 1770000000000)"
    )
    migrated.query("SELECT packageName FROM rejected_sources").use { c ->
        assertThat(c.moveToFirst()).isTrue()
        assertThat(c.getString(0)).isEqualTo("com.chat")
    }
}
```

Add small helpers in the test file to keep the raw SQL readable. Copy the production migration as `MIGRATION_8_9_TEST_COPY`, matching the repo's existing pattern for `MIGRATION_5_6_TEST_COPY`.

**Step 2: Implement entities**

Append to `Entities.kt`:

```kotlin
@Entity(
    tableName = "captured_notifications",
    indices = [
        Index("packageName"),
        Index("disposition"),
        Index("capturedAt"),
    ],
)
data class CapturedNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val postedAt: Instant,
    val amountMinor: Long,
    val currency: String,
    val rawText: String,
    val rewrittenText: String?,
    val disposition: CaptureDisposition,
    val promotedToTxId: Long?,
    val capturedAt: Instant,
)

enum class CaptureDisposition { PENDING, PROMOTED, NOISE }

@Entity(tableName = "rejected_sources")
data class RejectedSource(
    @PrimaryKey val packageName: String,
    val rejectedAt: Instant,
)
```

Add converters:

```kotlin
@TypeConverter
fun captureDispositionToString(value: CaptureDisposition?): String? = value?.name

@TypeConverter
fun stringToCaptureDisposition(value: String?): CaptureDisposition? =
    value?.let(CaptureDisposition::valueOf)
```

**Step 3: Implement DAOs**

`CapturedNotificationDao.kt`:

```kotlin
package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

@Dao
interface CapturedNotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: CapturedNotification): Long

    @Query("SELECT * FROM captured_notifications WHERE id = :id")
    suspend fun get(id: Long): CapturedNotification?

    @Query("SELECT * FROM captured_notifications ORDER BY postedAt DESC")
    fun observeAll(): Flow<List<CapturedNotification>>

    @Query("SELECT * FROM captured_notifications WHERE packageName = :packageName ORDER BY postedAt DESC")
    fun observeForPackage(packageName: String): Flow<List<CapturedNotification>>

    @Query(
        """
        SELECT COUNT(*) FROM captured_notifications
        WHERE disposition = 'PENDING'
          AND packageName NOT IN (SELECT packageName FROM rejected_sources)
        """
    )
    fun observeVisiblePendingCount(): Flow<Int>

    @Query("UPDATE captured_notifications SET disposition = 'NOISE' WHERE id = :id")
    suspend fun markNoise(id: Long)

    @Query(
        """
        UPDATE captured_notifications
        SET disposition = 'PROMOTED', promotedToTxId = :txId
        WHERE id = :id
        """
    )
    suspend fun markPromoted(id: Long, txId: Long)

    @Query(
        """
        UPDATE captured_notifications
        SET disposition = 'NOISE'
        WHERE packageName = :packageName AND disposition = 'PENDING'
        """
    )
    suspend fun markPendingNoiseForPackage(packageName: String)

    @Query(
        """
        DELETE FROM captured_notifications
        WHERE disposition IN ('PENDING', 'NOISE')
          AND packageName IN (SELECT packageName FROM rejected_sources)
          AND capturedAt < :cutoff
        """
    )
    suspend fun deleteRejectedBefore(cutoff: Instant): Int

    @Query(
        """
        SELECT packageName, COUNT(*) AS entryCount, MAX(capturedAt) AS lastCapturedAt
        FROM captured_notifications
        WHERE capturedAt >= :since
        GROUP BY packageName
        """
    )
    fun observePackageStatsSince(since: Instant): Flow<List<PoolPackageStats>>
}

data class PoolPackageStats(
    val packageName: String,
    val entryCount: Int,
    val lastCapturedAt: Instant,
)
```

`RejectedSourceDao.kt`:

```kotlin
package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RejectedSourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: RejectedSource)

    @Query("SELECT * FROM rejected_sources ORDER BY rejectedAt ASC")
    fun observeAll(): Flow<List<RejectedSource>>

    @Query("SELECT packageName FROM rejected_sources")
    fun observeAllPackageNames(): Flow<List<String>>

    @Query("SELECT packageName FROM rejected_sources")
    suspend fun getAllPackageNamesOnce(): List<String>

    @Query("DELETE FROM rejected_sources WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
```

**Step 4: Wire database and migration**

- Bump `TxDatabase` version from `8` to `9`.
- Add `CapturedNotification::class` and `RejectedSource::class` to `entities`.
- Add abstract DAO getters.
- Add Hilt providers in `DatabaseModule`.
- Add `MIGRATION_8_9` and include it in `.addMigrations(...)`.

Migration SQL:

```kotlin
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `captured_notifications` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `packageName` TEXT NOT NULL,
                `postedAt` INTEGER NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `currency` TEXT NOT NULL,
                `rawText` TEXT NOT NULL,
                `rewrittenText` TEXT,
                `disposition` TEXT NOT NULL,
                `promotedToTxId` INTEGER,
                `capturedAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_notifications_packageName` ON `captured_notifications`(`packageName`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_notifications_disposition` ON `captured_notifications`(`disposition`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_notifications_capturedAt` ON `captured_notifications`(`capturedAt`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `rejected_sources` (
                `packageName` TEXT NOT NULL,
                `rejectedAt` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO captured_notifications (
                packageName, postedAt, amountMinor, currency, rawText, rewrittenText,
                disposition, promotedToTxId, capturedAt
            )
            SELECT sourceApp, occurredAt, amountMinor, currency, rawText, NULL,
                   'PENDING', NULL, occurredAt
            FROM transactions
            WHERE merchantRaw LIKE '% (review)' AND rawText IS NOT NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            DELETE FROM transactions
            WHERE merchantRaw LIKE '% (review)' AND rawText IS NOT NULL
            """.trimIndent(),
        )
    }
}
```

**Step 5: Update test helper**

Add DAO accessors to `DbRule`:

```kotlin
val capturedNotificationDao: CapturedNotificationDao get() = db.capturedNotificationDao()
val rejectedSourceDao: RejectedSourceDao get() = db.rejectedSourceDao()
```

**Step 6: Build to generate schema**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: build passes and `app/schemas/cy.txtracker.data.TxDatabase/9.json` appears or updates.

Do not run `connectedAndroidTest`.

---

## Task 3: Add Repository Capture Pool Operations

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt`
- Test: `app/src/androidTest/java/cy/txtracker/data/CapturePoolRepositoryTest.kt`

**Step 1: Add androidTest coverage**

Create tests for:

- Inserting a pool row.
- Promoting a pool row inserts a transaction, marks the pool row `PROMOTED`, and tracks the package.
- Marking noise flips only that row.
- Rejecting a package marks pending rows from that package as noise and removes it from `approved_sources`.
- Moving to tracked deletes `rejected_sources` and inserts `approved_sources`.

These are `androidTest` because they use Room. Do not run them on device/emulator under current repo instructions.

**Step 2: Add repository DTOs**

Near `ReparseResult` in `TransactionRepository.kt`:

```kotlin
enum class PoolFilter { PENDING, NOISE, PROMOTED, ALL }

data class PromoteEdit(
    val merchantRaw: String,
    val amountMinor: Long,
    val currency: String,
    val occurredAt: Instant,
    val categoryId: Long?,
    val description: String?,
)

data class TrackedPackageRow(
    val packageName: String,
    val label: String,
    val status: PackageStatus,
    val isBuiltIn: Boolean,
    val poolEntryCountLast30Days: Int,
    val lastCapturedAt: Instant?,
)

enum class PackageStatus { TRACKED, REJECTED, UNTRACKED }
```

**Step 3: Inject DAOs**

Add constructor params:

```kotlin
private val capturedNotificationDao: CapturedNotificationDao,
private val rejectedSourceDao: RejectedSourceDao,
```

Hilt can supply them after Task 2 providers are in place.

**Step 4: Add read methods**

Add:

```kotlin
fun observePool(filter: PoolFilter, packageName: String? = null): Flow<List<CapturedNotification>> =
    combine(
        if (packageName == null) capturedNotificationDao.observeAll()
        else capturedNotificationDao.observeForPackage(packageName),
        rejectedSourceDao.observeAllPackageNames(),
    ) { rows, rejected ->
        val rejectedSet = rejected.toSet()
        rows.filter { row ->
            when (filter) {
                PoolFilter.PENDING ->
                    row.disposition == CaptureDisposition.PENDING && row.packageName !in rejectedSet
                PoolFilter.NOISE -> row.disposition == CaptureDisposition.NOISE
                PoolFilter.PROMOTED -> row.disposition == CaptureDisposition.PROMOTED
                PoolFilter.ALL -> true
            }
        }
    }

fun observePoolPendingCount(): Flow<Int> =
    capturedNotificationDao.observeVisiblePendingCount()
```

For package management, combine built-ins, approved, rejected, and pool stats:

```kotlin
fun observeTrackedPackages(): Flow<List<TrackedPackageRow>> =
    combine(
        approvedSourceDao.observeAllPackageNames(),
        rejectedSourceDao.observeAllPackageNames(),
        capturedNotificationDao.observePackageStatsSince(Clock.System.now() - 30.days),
    ) { approved, rejected, stats ->
        buildTrackedPackageRows(approved.toSet(), rejected.toSet(), stats)
    }
```

If `Clock.System.now() - 30.days` cannot live inside a cold flow cleanly, compute it in a `flow { emitAll(...) }` wrapper or accept a `since` parameter from the ViewModel.

**Step 5: Add write methods**

Add:

```kotlin
suspend fun insertCapturedNotification(
    packageName: String,
    postedAt: Instant,
    amountMinor: Long,
    currency: String,
    rawText: String,
    rewrittenText: String?,
    now: Instant = Clock.System.now(),
): Long? {
    val id = capturedNotificationDao.insert(
        CapturedNotification(
            packageName = packageName,
            postedAt = postedAt,
            amountMinor = amountMinor,
            currency = currency,
            rawText = rawText,
            rewrittenText = rewrittenText,
            disposition = CaptureDisposition.PENDING,
            promotedToTxId = null,
            capturedAt = now,
        ),
    )
    return id.takeIf { it >= 0 }
}

suspend fun markPoolEntryNoise(id: Long) {
    capturedNotificationDao.markNoise(id)
}

suspend fun rejectPackage(packageName: String, now: Instant = Clock.System.now()) =
    database.withTransaction {
        rejectedSourceDao.upsert(RejectedSource(packageName, now))
        approvedSourceDao.delete(packageName)
        capturedNotificationDao.markPendingNoiseForPackage(packageName)
    }

suspend fun trackPackage(packageName: String, now: Instant = Clock.System.now()) =
    database.withTransaction {
        rejectedSourceDao.delete(packageName)
        approvedSourceDao.insert(ApprovedSource(packageName, now))
    }

suspend fun unrejectPackage(packageName: String, now: Instant = Clock.System.now()) {
    trackPackage(packageName, now)
}
```

Promotion:

```kotlin
suspend fun promotePoolEntry(id: Long, edit: PromoteEdit, now: Instant = Clock.System.now()): Long? =
    database.withTransaction {
        val pool = capturedNotificationDao.get(id) ?: return@withTransaction null
        val merchant = edit.merchantRaw.trim()
        if (merchant.isEmpty()) return@withTransaction null

        val merchantNormalized = normalizeMerchant(merchant)
        val bucket = bucketOf(edit.occurredAt)
        val dedupeKey = computeDedupeKey(edit.amountMinor, merchantNormalized, edit.occurredAt, edit.currency)
        val needsCurrencyConfirmation = if (edit.currency == "MYR") {
            false
        } else {
            ensureTrackedCurrency(edit.currency, now)
            findActiveTrip(edit.currency, edit.occurredAt) == null
        }

        val rowId = transactionDao.insert(
            Transaction(
                amountMinor = edit.amountMinor,
                currency = edit.currency,
                merchantRaw = merchant,
                merchantNormalized = merchantNormalized,
                categoryId = edit.categoryId,
                description = edit.description?.trim()?.takeIf { it.isNotEmpty() },
                occurredAt = edit.occurredAt,
                timeBucket = bucket,
                sourceApp = pool.packageName,
                rawText = pool.rawText,
                direction = Direction.OUT,
                createdAt = now,
                notificationDedupeKey = dedupeKey,
                needsVerification = false,
                needsCurrencyConfirmation = needsCurrencyConfirmation,
            ),
        )
        val txId = rowId.takeIf { it >= 0 } ?: return@withTransaction null
        capturedNotificationDao.markPromoted(id, txId)
        trackPackage(pool.packageName, now)
        txId
    }
```

**Step 6: Build**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: compile passes.

---

## Task 4: Extract Capture Pipeline And Replace Permissive Extractor

**Files:**
- Create: `app/src/main/java/cy/txtracker/service/CapturePipeline.kt`
- Modify: `app/src/main/java/cy/txtracker/service/TxNotificationListener.kt`
- Delete: `app/src/main/java/cy/txtracker/parsing/PermissiveExtractor.kt`
- Delete: `app/src/test/java/cy/txtracker/parsing/PermissiveExtractorTest.kt`
- Test: `app/src/test/java/cy/txtracker/service/CapturePipelineTest.kt`

**Step 1: Write capture pipeline tests**

Cover:

- Heuristic success returns a transaction decision and not a pool decision.
- Heuristic miss with amount returns a pool decision for any package, including non-built-in package.
- Amount-less text drops.
- Rewritten text is used for parsing, while raw text is retained.

Skeleton:

```kotlin
class CapturePipelineTest {
    private val pipeline = CapturePipeline(HeuristicExtractor())
    private val now = Instant.parse("2026-05-25T12:00:00Z")

    @Test fun heuristic_success_wins_over_pool() {
        val decision = pipeline.decide(
            packageName = "com.chat",
            rawText = "Paid RM12.00 to Coffee Shop",
            rewrittenText = "Paid RM12.00 to Coffee Shop",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
        )
        assertThat(decision).isInstanceOf(CaptureDecision.Parsed::class.java)
    }

    @Test fun amount_only_text_goes_to_pool() {
        val decision = pipeline.decide(
            packageName = "com.chat",
            rawText = "Lunch yesterday RM50 split reminder",
            rewrittenText = "Lunch yesterday RM50 split reminder",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
        ) as CaptureDecision.Pooled
        assertThat(decision.amountMinor).isEqualTo(5000L)
        assertThat(decision.rawText).contains("Lunch")
    }
}
```

**Step 2: Implement `CapturePipeline`**

```kotlin
package cy.txtracker.service

import cy.txtracker.parsing.HeuristicExtractor
import cy.txtracker.parsing.NotificationAmountParser
import cy.txtracker.parsing.ParsedTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant

sealed interface CaptureDecision {
    data class Parsed(val parsed: ParsedTransaction) : CaptureDecision
    data class Pooled(
        val packageName: String,
        val postedAt: Instant,
        val amountMinor: Long,
        val currency: String,
        val rawText: String,
        val rewrittenText: String?,
        val capturedAt: Instant,
    ) : CaptureDecision
    data object Dropped : CaptureDecision
}

@Singleton
class CapturePipeline @Inject constructor(
    private val heuristicExtractor: HeuristicExtractor,
) {
    fun decide(
        packageName: String,
        rawText: String,
        rewrittenText: String,
        postedAt: Instant,
        symbolDefaults: Map<String, String>,
        capturedAt: Instant,
    ): CaptureDecision {
        val heuristic = heuristicExtractor.extract(
            text = rewrittenText,
            sourceApp = packageName,
            postedAt = postedAt,
            symbolDefaults = symbolDefaults,
        )?.copy(rawText = rawText)
        if (heuristic != null) return CaptureDecision.Parsed(heuristic)

        val amount = NotificationAmountParser.findFirst(rewrittenText, symbolDefaults)
            ?: return CaptureDecision.Dropped
        return CaptureDecision.Pooled(
            packageName = packageName,
            postedAt = postedAt,
            amountMinor = amount.amountMinor,
            currency = amount.currency,
            rawText = rawText,
            rewrittenText = rewrittenText.takeIf { it != rawText },
            capturedAt = capturedAt,
        )
    }
}
```

**Step 3: Update listener**

In `TxNotificationListener`:

- Remove injections for `PermissiveExtractor` and `CapturePrefs`.
- Remove `watchedPackages`, `watchedPackagesJob`, allowlist logic, and capture-all logging.
- Inject `CapturePipeline`.
- Keep the group-summary and text extraction guards.
- In the coroutine, build `symbolDefaults`, call `pipeline.decide(...)`, then:

```kotlin
when (val decision = pipeline.decide(...)) {
    is CaptureDecision.Parsed -> {
        val rowId = insert(decision.parsed, sbn.packageName, needsVerification = true)
        if (rowId != null) repository.trackPackage(sbn.packageName)
    }
    is CaptureDecision.Pooled -> {
        repository.insertCapturedNotification(
            packageName = decision.packageName,
            postedAt = decision.postedAt,
            amountMinor = decision.amountMinor,
            currency = decision.currency,
            rawText = decision.rawText,
            rewrittenText = decision.rewrittenText,
            now = decision.capturedAt,
        )
    }
    CaptureDecision.Dropped -> {
        Log.i(TAG, "No amount detected for ${sbn.packageName}. text='${preview ?: "<empty>"}'")
    }
}
```

Change private `insert` to return `Long?`.

**Step 4: Remove capture-all Settings dependency later**

Do not delete `CapturePrefs` in this task if Settings still references it. Remove it in Task 8 after the UI is updated.

**Step 5: Run tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "cy.txtracker.service.CapturePipelineTest" --tests "cy.txtracker.parsing.HeuristicExtractorTest"
```

Expected: pass.

---

## Task 5: Add Pool Retention Worker

**Files:**
- Create: `app/src/main/java/cy/txtracker/notify/PoolRetentionWorker.kt`
- Create: `app/src/main/java/cy/txtracker/notify/PoolRetentionScheduler.kt`
- Modify: `app/src/main/java/cy/txtracker/TxApp.kt`
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt`
- Test: `app/src/androidTest/java/cy/txtracker/notify/PoolRetentionWorkerTest.kt`

**Step 1: Add repository retention method**

```kotlin
suspend fun deleteRejectedPoolEntriesBefore(cutoff: Instant): Int =
    capturedNotificationDao.deleteRejectedBefore(cutoff)
```

**Step 2: Add worker**

```kotlin
@HiltWorker
class PoolRetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: TransactionRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        repository.deleteRejectedPoolEntriesBefore(Clock.System.now() - 30.days)
        return Result.success()
    }
}
```

**Step 3: Add scheduler**

Use the existing `NotificationScheduler` style:

```kotlin
@Singleton
class PoolRetentionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        val request = PeriodicWorkRequestBuilder<PoolRetentionWorker>(
            24, TimeUnit.HOURS,
            1, TimeUnit.HOURS,
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object { const val WORK_NAME = "pool-retention" }
}
```

Inject and call `poolRetentionScheduler.start()` in `TxApp.onCreate`.

**Step 4: Write androidTest worker coverage**

Use `androidx.work.testing` patterns already available in dependencies. Do not run device/emulator tests under current instructions.

**Step 5: Build**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: compile passes.

---

## Task 6: Add Pool Review Screen

**Files:**
- Create: `app/src/main/java/cy/txtracker/ui/settings/capture/PoolViewModel.kt`
- Create: `app/src/main/java/cy/txtracker/ui/settings/capture/PoolScreen.kt`
- Create: `app/src/main/java/cy/txtracker/ui/settings/capture/PromotePoolEntrySheet.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/AppRoute.kt`
- Test: `app/src/test/java/cy/txtracker/ui/settings/capture/PoolViewModelTest.kt`

**Step 1: Add ViewModel state**

```kotlin
data class PoolUiState(
    val filter: PoolFilter = PoolFilter.PENDING,
    val packageName: String? = null,
    val rows: List<PoolDayGroup> = emptyList(),
    val selectedEntry: CapturedNotification? = null,
)

data class PoolDayGroup(
    val date: LocalDate,
    val rows: List<CapturedNotification>,
)
```

`PoolViewModel`:

- Holds `_filter` and `_packageName`.
- Observes `repository.observePool(filter, packageName)`.
- Groups rows by `postedAt.toLocalDateTime(MalaysiaTimeZone).date`, sorted descending.
- Exposes `markNoise(id)`, `rejectPackage(packageName)`, and `promote(id, PromoteEdit)`.

**Step 2: Build screen layout**

`PoolScreen` should match existing settings screens:

- `Scaffold` with `TopAppBar`.
- Filter chips: `Pending`, `Noise`, `Promoted`, `All`.
- `LazyColumn` grouped by day.
- Row content:
  - time
  - `SourceLabels.label(packageName)` and package name in smaller text
  - right-aligned formatted amount
  - raw text, max two lines
  - rewritten text only when non-null
- Row tap opens action sheet.

Actions:

- `Promote to transaction` opens `PromotePoolEntrySheet`.
- `Mark as noise` calls ViewModel and dismisses.
- `Reject package` shows confirmation, then calls ViewModel.
- `View full text` toggles expanded local row state.

Use a generic formatter for non-MYR:

```kotlin
private fun formatAmount(amountMinor: Long, currency: String): String =
    if (currency == "MYR") formatMyr(amountMinor)
    else "$currency ${amountMinor / 100}.${(amountMinor % 100).toString().padStart(2, '0')}"
```

**Step 3: Add promote sheet**

Reuse patterns from `AddManualSheet`:

- Prefill amount/currency/occurredAt from the pool row.
- Merchant starts blank and is required.
- Category and description optional.
- Save builds `PromoteEdit` and calls `viewModel.promote(...)`.
- If repository returns null due to dedupe collision, show an inline error or snackbar and keep sheet open.

Keep v1 simple: amount/currency/date/time can be editable, but if time is tight, merchant/category/description fields are enough because spec says amount, currency, and occurredAt are prefilled from the row.

**Step 4: Wire route**

In `AppRoute.kt`:

```kotlin
const val SETTINGS_POOL = "settings/pool"
const val SETTINGS_POOL_PACKAGE = "settings/pool?package={packageName}"
```

Add navigation from Settings in Task 8. Add composables:

```kotlin
composable(Routes.SETTINGS_POOL) {
    PoolScreen(onBack = { nav.popBackStack() })
}
composable(Routes.SETTINGS_POOL_PACKAGE) { entry ->
    PoolScreen(
        packageName = entry.arguments?.getString("packageName"),
        onBack = { nav.popBackStack() },
    )
}
```

If query-parameter setup becomes awkward, use `settings/pool/{packageName}` and URL-encode package names.

**Step 5: Build**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: compile passes.

---

## Task 7: Add Tracked Apps Screen

**Files:**
- Create: `app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsViewModel.kt`
- Create: `app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsScreen.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/AppRoute.kt`
- Test: `app/src/test/java/cy/txtracker/ui/settings/capture/TrackedAppsViewModelTest.kt`

**Step 1: Add ViewModel state**

```kotlin
data class TrackedAppsUiState(
    val tracked: List<TrackedPackageRow> = emptyList(),
    val rejected: List<TrackedPackageRow> = emptyList(),
    val untracked: List<TrackedPackageRow> = emptyList(),
)
```

Map `repository.observeTrackedPackages()` into the three lists by `PackageStatus`.

**Step 2: Add screen**

Use a `Scaffold` + `LazyColumn`.

Sections:

- `Tracked`
- `Rejected`
- `Untracked`

Each section header includes count. Empty section renders `No packages.`.

Each row:

- `SourceLabels.label(packageName)`
- package name caption
- `built-in` assist chip when `isBuiltIn`
- trailing count of pool entries in last 30 days

Action sheet:

- Tracked: `Move to Rejected`, `View entries in pool`
- Rejected: `Move to Tracked`, `View entries in pool`
- Untracked: `Move to Tracked`, `Move to Rejected`, `View entries in pool`

`View entries in pool` calls `onPoolPackageClick(packageName)`.

**Step 3: Wire route**

In `AppRoute.kt`:

```kotlin
const val SETTINGS_TRACKED_APPS = "settings/tracked-apps"
```

Add a composable and a `SettingsScreen` callback:

```kotlin
TrackedAppsScreen(
    onBack = { nav.popBackStack() },
    onPoolPackageClick = { pkg -> nav.navigate("settings/pool?package=$pkg") },
)
```

URL-encode `pkg` if using query parameters.

**Step 4: Build**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: compile passes.

---

## Task 8: Replace Capture-All Settings UI With Pool Entries

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/AppRoute.kt`
- Modify: `app/src/main/java/cy/txtracker/service/CapturePrefs.kt`

**Step 1: Remove active capture-all state from SettingsViewModel**

Delete:

```kotlin
val captureAllPackages: StateFlow<Boolean> = capturePrefs.captureAllPackages
fun setCaptureAllPackages(value: Boolean) { capturePrefs.setCaptureAllPackages(value) }
```

Remove `CapturePrefs` injection if no other code needs it.

Add:

```kotlin
val poolPendingCount: StateFlow<Int> =
    repository.observePoolPendingCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = 0,
        )
```

**Step 2: Update SettingsScreen signature**

Add callbacks:

```kotlin
onNotificationPoolClick: () -> Unit,
onTrackedAppsClick: () -> Unit,
```

Collect:

```kotlin
val poolPendingCount by viewModel.poolPendingCount.collectAsState()
```

Delete `captureAllPackages` collection and the `Capture all packages` switch row.

Insert under the learning/notification settings area, near `Notification rewrites`:

```kotlin
ListItem(
    headlineContent = { Text("Notification pool ($poolPendingCount)") },
    supportingContent = { Text("Review captured notifications that weren't auto-tracked.") },
    modifier = Modifier.fillMaxWidth().clickableRow(onNotificationPoolClick),
)
HorizontalDivider()
ListItem(
    headlineContent = { Text("Tracked apps") },
    supportingContent = { Text("Manage which apps create transactions automatically.") },
    modifier = Modifier.fillMaxWidth().clickableRow(onTrackedAppsClick),
)
HorizontalDivider()
```

**Step 3: Wire AppRoute callbacks**

In `SettingsScreen(...)` call:

```kotlin
onNotificationPoolClick = { nav.navigate(Routes.SETTINGS_POOL) },
onTrackedAppsClick = { nav.navigate(Routes.SETTINGS_TRACKED_APPS) },
```

**Step 4: Leave prefs on disk but unused**

Do not delete the SharedPreferences key. If `CapturePrefs.kt` becomes unused, either:

- Delete the class if no injection references remain, or
- Leave it with a comment that it is retained only for legacy preference compatibility.

Prefer deletion only after `rg "CapturePrefs|captureAllPackages"` shows no production references.

**Step 5: Build**

Run:

```powershell
rg "captureAllPackages|Capture all packages|PermissiveExtractor"
.\gradlew.bat assembleDebug
```

Expected: `rg` has no production references to the removed capture-all/permissive pipeline, and build passes.

---

## Task 9: Add Home Currency Review Count And Auto-Hide

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeUiState.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt`
- Test: `app/src/test/java/cy/txtracker/ui/home/HomeViewModelTest.kt` if an existing VM-test harness exists; otherwise add focused pure helper tests only.

**Conflict warning:** before editing these files, inspect `git diff -- app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt` because the home-list agent may have already rewritten the filter rows.

**Step 1: Add state field**

In `HomeUiState`:

```kotlin
val currencyReviewCount: Int,
```

Add it to `empty()`, `buildState(...)`, and `buildCurrencyReviewState(...)`.

**Step 2: Compute count**

In the normal month state, count over the joined month rows:

```kotlin
val currencyReviewCount = joined.count { it.transaction.needsCurrencyConfirmation }
```

In `buildCurrencyReviewState`, use `transactions.size`.

**Step 3: Snap back when empty**

In the `flatMapLatest` state build path, if `filter == HomeFilter.CurrencyReview` and the observed currency-review list is empty, set `_filter.value = HomeFilter.All` and emit an empty/all state rather than leaving the UI stranded in an empty hidden filter.

Keep this beside any category snap-back logic from the home-list plan if it already exists.

**Step 4: Hide chip when count is zero**

If current `FilterRow` still exists, change:

```kotlin
item {
    FilterChip(
        selected = filter == HomeFilter.CurrencyReview,
        onClick = { onFilterChange(HomeFilter.CurrencyReview) },
        label = { Text("Currency review") },
    )
}
```

to:

```kotlin
if (currencyReviewCount > 0) {
    item {
        FilterChip(
            selected = filter == HomeFilter.CurrencyReview,
            onClick = { onFilterChange(HomeFilter.CurrencyReview) },
            label = { Text("Currency review ($currencyReviewCount)") },
        )
    }
}
```

If the home-list plan already replaced `FilterRow` with `StatusFilterRow`, apply the same `currencyReviewCount > 0` condition there instead.

**Step 5: Build**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: compile passes.

---

## Task 10: Update Backup/Export Boundaries

**Files:**
- Inspect: `app/src/main/java/cy/txtracker/export/Backup.kt`
- Inspect: `app/src/main/java/cy/txtracker/export/BackupExporter.kt`
- Inspect: `app/src/main/java/cy/txtracker/export/BackupImporter.kt`
- Test: existing backup unit tests under `app/src/test/java/cy/txtracker/export/`

**Step 1: Confirm pool entries are not exported**

The spec says pool entries are device-local and backup export is out of scope. Do not add `CapturedNotification` or `RejectedSource` to `Backup`.

**Step 2: Ensure compilation after DB constructor changes**

If exporter/importer tests instantiate `TransactionRepository`, update constructor calls for the new DAOs.

**Step 3: Run export tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "cy.txtracker.export.*"
```

Expected: pass.

---

## Task 11: Remove Obsolete Permissive Test Expectations

**Files:**
- Delete: `app/src/test/java/cy/txtracker/parsing/PermissiveExtractorTest.kt`
- Inspect: `app/src/test/java/cy/txtracker/parsing/*`
- Inspect: `app/src/test/java/cy/txtracker/service/*`

**Step 1: Search for stale behavior**

Run:

```powershell
rg "\(review\)|PermissiveExtractor|captureAllPackages|Capture all packages|bypassAllowlist"
```

**Step 2: Update tests**

Replace permissive transaction expectations with pool expectations in `CapturePipelineTest` and repository tests.

Keep migration tests asserting old `(review)` rows move into the pool.

**Step 3: Run unit tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: pass.

---

## Task 12: Final Verification

**Files:**
- All touched files.

**Step 1: Check worktree**

Run:

```powershell
git status --short
```

Expected: only intentional implementation files are modified, plus any unrelated pre-existing files such as `.claude/settings.local.json`.

**Step 2: Search for removed runtime concepts**

Run:

```powershell
rg "PermissiveExtractor|captureAllPackages|Capture all packages|bypassAllowlist"
```

Expected: no production references. It is acceptable for `CapturePrefs` legacy comments or old docs/specs to appear.

**Step 3: Run local verification**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Expected: both pass.

Do not run emulator/device tests under current repository instruction.

**Step 4: Manual review checklist**

- New DB version is `9`.
- New schema file exists at `app/schemas/cy.txtracker.data.TxDatabase/9.json`.
- Listener no longer drops packages based on allowlist.
- Heuristic success inserts transaction and tracks package.
- Heuristic miss with amount inserts pool row, not transaction.
- Pool pending count excludes rejected packages.
- Rejecting a package hides current pending entries by marking them `NOISE`.
- Promoting a pool row creates a transaction with `needsVerification = false`.
- Settings no longer shows `Capture all packages`.
- Home currency-review chip only appears with count > 0.
- Home-list-layout changes from the other agent are preserved.

---

## Rollback Notes

If implementation conflicts become too large, land the feature in this order:

1. Data model, DAOs, migration, parser extraction.
2. Listener writes pool rows but no UI beyond Settings count.
3. Pool review screen.
4. Tracked apps screen.
5. Home currency-review chip cleanup.

This keeps the database and ingest pipeline coherent before UI polish, and it isolates the most likely concurrent conflict (`HomeScreen.kt`) until last.
