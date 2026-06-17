# Tracked-app rename, GX routing fix, and multi-select — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users rename tracked apps, make GX (and similar) notifications reach the home page reliably, and add multi-select approve/reject on the home and notification-pool screens.

**Architecture:** Two new Room tables (`custom_source_labels`, `auto_promote_sources`) added in one migration. A new heuristic-parser shape handles GX's verb-less "to X is successful" form, and a per-app auto-promote flag is a generic fallback in `CapturePipeline`. Multi-select is standard Material 3 selection mode driven by new ViewModel state and batch repository/DAO methods.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room, Hilt, Coroutines/Flow. Tests: JUnit4 + Truth + MockK + `kotlinx-coroutines-test`.

**Conventions for this plan:**
- Build/verify is **compile + JVM unit tests only**. Never boot an emulator or run `connectedAndroidTest`.
  - Unit tests: `./gradlew :app:testDebugUnitTest --tests "<FQN>"`
  - Production compile gate: `./gradlew :app:compileDebugKotlin`
  - androidTest compile gate (no run): `./gradlew :app:compileDebugAndroidTestKotlin`
- Commit after each task completes (user runs commits per task completion). Commit on the current branch; never create a branch.

---

## File Structure

**New files:**
- `app/src/main/java/cy/txtracker/data/CustomSourceLabelDao.kt` — DAO for the rename table.
- `app/src/main/java/cy/txtracker/data/AutoPromoteSourceDao.kt` — DAO for the auto-promote table.
- `app/src/androidTest/java/cy/txtracker/data/MigrationV14ToV15Test.kt` — migration test (compile-checked; run on device later).
- `app/src/test/java/cy/txtracker/data/CustomLabelAndAutoPromoteTest.kt` — repo-level tests for rename + auto-promote wiring.
- `app/src/test/java/cy/txtracker/data/BatchTransactionOpsTest.kt` — repo-level tests for batch confirm/delete/restore/noise/promote.
- `app/src/test/java/cy/txtracker/ui/home/HomeSelectionTest.kt` — Home selection-state VM tests.
- `app/src/test/java/cy/txtracker/ui/settings/capture/PoolSelectionTest.kt` — Pool selection-state VM tests.

**Modified files:**
- `app/src/main/java/cy/txtracker/data/Entities.kt` — add `CustomSourceLabel`, `AutoPromoteSource` entities.
- `app/src/main/java/cy/txtracker/data/TxDatabase.kt` — register entities + DAOs, bump version 14→15.
- `app/src/main/java/cy/txtracker/di/DatabaseModule.kt` — `MIGRATION_14_15`, register it, provide new DAOs.
- `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` — constructor params + rename/auto-promote/batch methods + `buildTrackedPackageRows`/`observeTrackedPackages` overlay + `TrackedPackageRow.autoPromote`.
- `app/src/main/java/cy/txtracker/data/TransactionDao.kt` — batch queries.
- `app/src/main/java/cy/txtracker/data/CapturedNotificationDao.kt` — batch noise query.
- `app/src/main/java/cy/txtracker/parsing/HeuristicExtractor.kt` — transfer-success shape.
- `app/src/main/java/cy/txtracker/service/CapturePipeline.kt` — `isAutoPromote` param.
- `app/src/main/java/cy/txtracker/service/TxNotificationListener.kt` — pass `isAutoPromote`.
- `app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsViewModel.kt` — rename + auto-promote actions.
- `app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsScreen.kt` — Rename dialog + auto-promote toggle.
- `app/src/main/java/cy/txtracker/ui/settings/capture/PoolViewModel.kt` — `labelFor` + selection + batch actions.
- `app/src/main/java/cy/txtracker/ui/settings/capture/PoolScreen.kt` — label resolver + selection CAB.
- `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt` — selection state + batch actions.
- `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt` — selection CAB + selectable rows.
- 7 repo-test factories (listed in Task 1).

---

## Task 1: Schema + DI foundation for both new tables

Adds the two tables, DAOs, one migration, DI wiring, and repository constructor params. The DAOs are injected but not yet used functionally — later tasks wire behavior. Every commit stays green.

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/Entities.kt`
- Create: `app/src/main/java/cy/txtracker/data/CustomSourceLabelDao.kt`
- Create: `app/src/main/java/cy/txtracker/data/AutoPromoteSourceDao.kt`
- Modify: `app/src/main/java/cy/txtracker/data/TxDatabase.kt`
- Modify: `app/src/main/java/cy/txtracker/di/DatabaseModule.kt`
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt:70-92` (constructor)
- Create: `app/src/androidTest/java/cy/txtracker/data/MigrationV14ToV15Test.kt`
- Modify (test factories): `UndefinedMerchantGuardTest.kt`, `RestoreTransactionTest.kt`, `PromotePoolEntryTest.kt`, `IsPackageRejectedTest.kt`, `FundingSourceMergeTest.kt`, `CategoryBackfillTest.kt`, `CapturedNotificationDedupTest.kt` (all under `app/src/test/java/cy/txtracker/data/`)

- [ ] **Step 1: Add the two entities to `Entities.kt`**

Append near the other source tables (e.g. after `RejectedSource`):

```kotlin
/** User-supplied display name override for a tracked app, keyed by package. */
@Entity(tableName = "custom_source_labels")
data class CustomSourceLabel(
    @PrimaryKey val packageName: String,
    val label: String,
    val updatedAt: Instant,
)

/**
 * Packages the user has opted into "auto-add to home even when details can't be read".
 * Presence = enabled. See CapturePipeline.decide().
 */
@Entity(tableName = "auto_promote_sources")
data class AutoPromoteSource(
    @PrimaryKey val packageName: String,
    val enabledAt: Instant,
)
```

(`Entities.kt` already imports `androidx.room.Entity`, `androidx.room.PrimaryKey`, and `kotlinx.datetime.Instant` — confirm and add if missing.)

- [ ] **Step 2: Create `CustomSourceLabelDao.kt`**

```kotlin
package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomSourceLabelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CustomSourceLabel)

    @Query("DELETE FROM custom_source_labels WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT * FROM custom_source_labels")
    fun observeAll(): Flow<List<CustomSourceLabel>>
}
```

- [ ] **Step 3: Create `AutoPromoteSourceDao.kt`**

```kotlin
package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoPromoteSourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: AutoPromoteSource)

    @Query("DELETE FROM auto_promote_sources WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT EXISTS(SELECT 1 FROM auto_promote_sources WHERE packageName = :packageName)")
    suspend fun isAutoPromote(packageName: String): Boolean

    @Query("SELECT packageName FROM auto_promote_sources")
    fun observeAllPackageNames(): Flow<List<String>>
}
```

- [ ] **Step 4: Register entities + DAOs in `TxDatabase.kt` and bump version**

Change `version = 14` to `version = 15`. Add to the `entities` array: `CustomSourceLabel::class,` and `AutoPromoteSource::class,`. Add abstract accessors:

```kotlin
abstract fun customSourceLabelDao(): CustomSourceLabelDao
abstract fun autoPromoteSourceDao(): AutoPromoteSourceDao
```

- [ ] **Step 5: Add `MIGRATION_14_15` and register it in `DatabaseModule.kt`**

Add to the `.addMigrations(...)` list after `MIGRATION_13_14,`:

```kotlin
MIGRATION_14_15,
```

Add the migration definition at the bottom of the file (matching the existing `private val MIGRATION_*` style):

```kotlin
/**
 * v15 adds two source-config tables:
 *   - `custom_source_labels`: user rename overrides for tracked apps.
 *   - `auto_promote_sources`: packages opted into auto-adding amount-only notifications
 *     to home (CapturePipeline auto-promote).
 * Both are additive; no backfill. Schema mirrors what Room generates for
 * [cy.txtracker.data.CustomSourceLabel] and [cy.txtracker.data.AutoPromoteSource].
 */
private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `custom_source_labels` (
                `packageName` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `auto_promote_sources` (
                `packageName` TEXT NOT NULL,
                `enabledAt` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`)
            )
            """.trimIndent(),
        )
    }
}
```

Add the two `@Provides` to `DatabaseModule` (near the other DAO providers):

```kotlin
@Provides
fun provideCustomSourceLabelDao(db: TxDatabase): CustomSourceLabelDao =
    db.customSourceLabelDao()

@Provides
fun provideAutoPromoteSourceDao(db: TxDatabase): AutoPromoteSourceDao =
    db.autoPromoteSourceDao()
```

(Add the imports `cy.txtracker.data.CustomSourceLabelDao` and `cy.txtracker.data.AutoPromoteSourceDao` to `DatabaseModule.kt`.)

- [ ] **Step 6: Add the two DAOs to `TransactionRepository`'s constructor**

In `TransactionRepository.kt`, after `private val reimbursementEntryDao: ReimbursementEntryDao,` (line 86) add:

```kotlin
    private val customSourceLabelDao: CustomSourceLabelDao,
    private val autoPromoteSourceDao: AutoPromoteSourceDao,
```

- [ ] **Step 7: Fix the 7 repo-test factories so they compile**

Each of these files constructs `TransactionRepository(...)` with named args. In every one, add these two lines alongside the other DAO mocks (e.g. right after the `reimbursementEntryDao = mockk(relaxed = true),` line):

```kotlin
        customSourceLabelDao = mockk(relaxed = true),
        autoPromoteSourceDao = mockk(relaxed = true),
```

Files: `UndefinedMerchantGuardTest.kt`, `RestoreTransactionTest.kt`, `PromotePoolEntryTest.kt`, `IsPackageRejectedTest.kt`, `FundingSourceMergeTest.kt`, `CategoryBackfillTest.kt`, `CapturedNotificationDedupTest.kt`.

- [ ] **Step 8: Write the migration test (compile-checked, device-run deferred)**

Create `MigrationV14ToV15Test.kt`, mirroring `MigrationV13ToV14Test.kt`'s structure (open the existing file to copy the `MigrationTestHelper` setup boilerplate exactly — same `helper`, `TEST_DB`, and `runMigrationsAndValidate` usage):

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
class MigrationV14ToV15Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate14To15_createsBothTables() {
        helper.createDatabase(TEST_DB, 14).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, MIGRATION_14_15)

        // Insert a row into each new table to prove it exists and accepts the schema.
        db.execSQL(
            "INSERT INTO custom_source_labels(packageName, label, updatedAt) VALUES('p', 'Nice Name', 1)",
        )
        db.execSQL(
            "INSERT INTO auto_promote_sources(packageName, enabledAt) VALUES('p', 1)",
        )
        val c1 = db.query("SELECT label FROM custom_source_labels WHERE packageName = 'p'")
        c1.use { assertThat(it.moveToFirst()).isTrue() }
        val c2 = db.query("SELECT COUNT(*) FROM auto_promote_sources")
        c2.use { assertThat(it.moveToFirst()).isTrue() }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test-14-15"
    }
}
```

If the copied `MIGRATION_14_15` symbol is `private` in `DatabaseModule.kt`, the test cannot see it. Check `MigrationV13ToV14Test`: if it references `MIGRATION_13_14` directly, the migrations are package-visible (not private) — in that case remove the `private` modifier from `MIGRATION_14_15` (and note the other migrations' visibility to match). If the existing tests instead pass the real database builder, mirror whatever they do. Match the existing test exactly rather than inventing a new pattern.

- [ ] **Step 9: Compile-gate production + unit tests + androidTest**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (the 7 edited factories compile; no behavior changed).

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL (migration test compiles).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/cy/txtracker/data app/src/main/java/cy/txtracker/di app/src/androidTest/java/cy/txtracker/data/MigrationV14ToV15Test.kt app/src/test/java/cy/txtracker/data
git commit -m "DB: add custom_source_labels and auto_promote_sources tables (v15)"
```

---

## Task 2: Repository — rename methods + label overlay

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` (`observeTrackedPackages` ~261, `buildTrackedPackageRows` ~1659, new methods)
- Test: `app/src/test/java/cy/txtracker/data/CustomLabelAndAutoPromoteTest.kt`

- [ ] **Step 1: Write the failing test**

Create `CustomLabelAndAutoPromoteTest.kt`. (Auto-promote cases are added in Task 6; this file starts with the rename cases.)

```kotlin
package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.capture
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class CustomLabelAndAutoPromoteTest {

    private val now = Instant.parse("2026-06-17T12:00:00Z")
    private val customLabelDao = mockk<CustomSourceLabelDao>(relaxed = true)

    @Test
    fun renameTrackedApp_upserts_row_with_trimmed_label() = runTest {
        val captured = slot<CustomSourceLabel>()
        val repo = makeRepo()
        repo.renameTrackedApp("my.com.gxsbank", "  My GX  ", now)
        coVerify { customLabelDao.upsert(capture(captured)) }
        assertThat(captured.captured.packageName).isEqualTo("my.com.gxsbank")
        assertThat(captured.captured.label).isEqualTo("My GX")
    }

    @Test
    fun renameTrackedApp_blank_label_clears_override() = runTest {
        val repo = makeRepo()
        repo.renameTrackedApp("my.com.gxsbank", "   ", now)
        coVerify { customLabelDao.delete("my.com.gxsbank") }
        coVerify(exactly = 0) { customLabelDao.upsert(any()) }
    }

    private fun makeRepo(): TransactionRepository = TransactionRepository(
        database = mockk(relaxed = true),
        transactionDao = mockk(relaxed = true),
        categoryDao = mockk(relaxed = true),
        merchantMappingDao = mockk(relaxed = true),
        descriptionMappingDao = mockk(relaxed = true),
        merchantNoteDao = mockk(relaxed = true),
        userFacingSourceDao = mockk(relaxed = true),
        approvedSourceDao = mockk(relaxed = true),
        capturedNotificationDao = mockk(relaxed = true),
        rejectedSourceDao = mockk(relaxed = true),
        trackedCurrencyDao = mockk(relaxed = true),
        tripWindowDao = mockk(relaxed = true),
        packageTextRewriteDao = mockk(relaxed = true),
        fundingSourceDao = mockk(relaxed = true),
        slDebitDao = mockk(relaxed = true),
        reimbursementEntryDao = mockk(relaxed = true),
        customSourceLabelDao = customLabelDao,
        autoPromoteSourceDao = mockk(relaxed = true),
        categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
        descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk<FundingSourceClassifier>(relaxed = true),
    )
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.data.CustomLabelAndAutoPromoteTest"`
Expected: FAIL — `renameTrackedApp` is unresolved (does not compile yet).

- [ ] **Step 3: Add the repository methods + label overlay**

In `TransactionRepository.kt`, add methods (place near the other source methods, e.g. after `unrejectPackage`):

```kotlin
suspend fun renameTrackedApp(
    packageName: String,
    label: String,
    now: Instant = Clock.System.now(),
) {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) {
        customSourceLabelDao.delete(packageName)
    } else {
        customSourceLabelDao.upsert(CustomSourceLabel(packageName, trimmed, now))
    }
}

fun observeCustomLabels(): Flow<Map<String, String>> =
    customSourceLabelDao.observeAll().map { rows -> rows.associate { it.packageName to it.label } }
```

Change `observeTrackedPackages()` to combine the custom-label flow as a 4th source. Replace the existing `combine(...)` with the 4-arg form:

```kotlin
fun observeTrackedPackages(): Flow<List<TrackedPackageRow>> =
    combine(
        approvedSourceDao.observeAllPackageNames(),
        rejectedSourceDao.observeAllPackageNames(),
        flow {
            emitAll(capturedNotificationDao.observePackageStatsSince(Clock.System.now() - 30.days))
        },
        customSourceLabelDao.observeAll(),
    ) { approved, rejected, stats, labels ->
        buildTrackedPackageRows(
            approved = approved.toSet(),
            rejected = rejected.toSet(),
            stats = stats,
            customLabels = labels.associate { it.packageName to it.label },
        )
    }
```

Update the top-level `buildTrackedPackageRows` (line 1659) to take and apply the overrides:

```kotlin
private fun buildTrackedPackageRows(
    approved: Set<String>,
    rejected: Set<String>,
    stats: List<PoolPackageStats>,
    customLabels: Map<String, String>,
): List<TrackedPackageRow> {
    val statsByPackage = stats.associateBy { it.packageName }
    val packages = SourcePackages.PERMISSIVE_PACKAGES + approved + rejected + statsByPackage.keys
    return packages
        .map { packageName ->
            val stat = statsByPackage[packageName]
            val isBuiltIn = packageName in SourcePackages.PERMISSIVE_PACKAGES
            val status = when {
                packageName in rejected -> PackageStatus.REJECTED
                isBuiltIn || packageName in approved -> PackageStatus.TRACKED
                else -> PackageStatus.UNTRACKED
            }
            TrackedPackageRow(
                packageName = packageName,
                label = customLabels[packageName] ?: SourceLabels.label(packageName),
                status = status,
                isBuiltIn = isBuiltIn,
                poolEntryCountLast30Days = stat?.entryCount ?: 0,
                lastCapturedAt = stat?.lastCapturedAt,
            )
        }
        .sortedWith(compareBy<TrackedPackageRow> { it.status.ordinal }.thenBy { it.label })
}
```

(Confirm `map`, `combine`, `flow`, `emitAll`, `30.days` imports already exist in the file — they're used by the current `observeTrackedPackages`.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.data.CustomLabelAndAutoPromoteTest"`
Expected: PASS (both rename tests).

- [ ] **Step 5: Compile-gate and commit**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/cy/txtracker/data/TransactionRepository.kt app/src/test/java/cy/txtracker/data/CustomLabelAndAutoPromoteTest.kt
git commit -m "Repo: custom tracked-app labels + overlay on tracked rows"
```

---

## Task 3: Tracked Apps screen — Rename action + dialog

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsScreen.kt`

(UI-only; verified by compile. No unit test — the rename logic is already covered in Task 2.)

- [ ] **Step 1: Add the rename action to the ViewModel**

In `TrackedAppsViewModel.kt`, add after `reject(...)`:

```kotlin
fun rename(packageName: String, label: String) {
    viewModelScope.launch { repository.renameTrackedApp(packageName, label) }
}
```

- [ ] **Step 2: Add a Rename dialog and wire the action in the screen**

In `TrackedAppsScreen.kt`:

1. Add a `renameTarget` state next to `selected` in `TrackedAppsScreen`:

```kotlin
var renameTarget by remember { mutableStateOf<TrackedPackageRow?>(null) }
```

2. Pass an `onRename` lambda into `PackageActionSheet` (added in step 3) that sets `renameTarget = row` and clears `selected`:

```kotlin
PackageActionSheet(
    row = row,
    onTrack = { viewModel.track(row.packageName); selected = null },
    onReject = { viewModel.reject(row.packageName); selected = null },
    onViewPool = { selected = null; onPoolPackageClick(row.packageName) },
    onRename = { selected = null; renameTarget = row },
    onDismiss = { selected = null },
)
```

3. Render the dialog after the action sheet block:

```kotlin
renameTarget?.let { row ->
    RenameAppDialog(
        currentLabel = row.label,
        onConfirm = { newLabel ->
            viewModel.rename(row.packageName, newLabel)
            renameTarget = null
        },
        onDismiss = { renameTarget = null },
    )
}
```

4. Add the dialog composable and the `onRename` param. Add these imports: `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.OutlinedTextField`, `androidx.compose.material3.TextButton`, `androidx.compose.runtime.mutableStateOf` (already present), `androidx.compose.runtime.saveable.rememberSaveable`.

```kotlin
@Composable
private fun RenameAppDialog(
    currentLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(currentLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename app") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Display name") },
                supportingText = { Text("Leave blank to use the default name.") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

5. Update `PackageActionSheet`'s signature to accept `onRename: () -> Unit` and add a Rename row inside the `Column` (before "View entries in pool"):

```kotlin
ListItem(headlineContent = { Text("Rename") }, modifier = Modifier.clickable { onRename() })
```

- [ ] **Step 3: Compile-gate and commit**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsViewModel.kt app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsScreen.kt
git commit -m "Tracked apps: rename action + dialog"
```

---

## Task 4: Pool screen — use custom labels

The Pool screen calls `SourceLabels.label(...)` directly in three places. Route them through the ViewModel so renames apply here too.

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/settings/capture/PoolViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/settings/capture/PoolScreen.kt`

- [ ] **Step 1: Expose a label resolver from `PoolViewModel`**

Add a `customLabels` flow folded into `PoolUiState`. Add a field to `PoolUiState`:

```kotlin
data class PoolUiState(
    val filter: PoolFilter = PoolFilter.PENDING,
    val packageName: String? = null,
    val rows: List<PoolDayGroup> = emptyList(),
    val customLabels: Map<String, String> = emptyMap(),
) {
    fun labelFor(packageName: String): String =
        customLabels[packageName] ?: cy.txtracker.parsing.SourceLabels.label(packageName)
}
```

Combine the custom-label flow into the existing `state`. Replace the `state` builder so the inner `flatMapLatest` result is combined with `repository.observeCustomLabels()`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
val state: StateFlow<PoolUiState> =
    combine(filter, packageName) { f, pkg -> f to pkg }
        .flatMapLatest { (f, pkg) ->
            combine(
                repository.observePool(f, pkg),
                repository.observeCustomLabels(),
            ) { rows, labels ->
                PoolUiState(
                    filter = f,
                    packageName = pkg,
                    rows = rows
                        .groupBy { it.postedAt.toLocalDateTime(MalaysiaTimeZone).date }
                        .toSortedMap(reverseOrder())
                        .map { (date, list) -> PoolDayGroup(date, list) },
                    customLabels = labels,
                )
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            PoolUiState(),
        )
```

- [ ] **Step 2: Use `state.labelFor(...)` in `PoolScreen.kt`**

Replace the three `SourceLabels.label(row.packageName)` call sites:

1. In the reject dialog text (line ~153): `${state.labelFor(row.packageName)}`.
2. In `PoolRow` headline (line ~197): pass the label in. Change `PoolRow`'s signature to accept `label: String` and use `Text(label)`; at the call site pass `label = state.labelFor(row.packageName)`.
3. In `PoolActionSheet` (line ~239): change its signature to accept `label: String` and use it; at the call site (`actionRow?.let { ... }`) pass `label = state.labelFor(row.packageName)`.

Remove the now-unused `import cy.txtracker.parsing.SourceLabels` if no references remain.

- [ ] **Step 3: Compile-gate and commit**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/cy/txtracker/ui/settings/capture/PoolViewModel.kt app/src/main/java/cy/txtracker/ui/settings/capture/PoolScreen.kt
git commit -m "Pool: render custom tracked-app labels"
```

---

## Task 5: Heuristic parser — GX transfer-success shape

**Files:**
- Modify: `app/src/main/java/cy/txtracker/parsing/HeuristicExtractor.kt`
- Test: `app/src/test/java/cy/txtracker/parsing/HeuristicExtractorTest.kt`

- [ ] **Step 1: Add failing tests for the GX samples**

Append to `HeuristicExtractorTest.kt`:

```kotlin
@Test
fun handles_gx_transfer_success_with_no_out_verb() {
    // GX Bank: "RM<amt> to <RECIPIENT> is successful" — has a recipient anchor but NO
    // out-verb, so the OUT_VERB gate used to reject it and the entry fell to the pool.
    val r = extractor.extract("RM4.00 to CHEE NYOK LAN is successful", "my.com.gxsbank", now)!!
    assertThat(r.amountMinor).isEqualTo(400L)
    assertThat(r.currency).isEqualTo("MYR")
    assertThat(r.merchantRaw).isEqualTo("CHEE NYOK LAN")
    assertThat(r.direction).isEqualTo(Direction.OUT)
}

@Test
fun handles_gx_transfer_success_hyphenated_recipient() {
    val r = extractor.extract("RM14.50 to AA PHARMACY-SEA PAR is successful", "my.com.gxsbank", now)!!
    assertThat(r.amountMinor).isEqualTo(1450L)
    assertThat(r.merchantRaw).isEqualTo("AA PHARMACY-SEA PAR")
}

@Test
fun transfer_success_shape_does_not_match_promo_without_recipient() {
    // Promo text that mentions an amount + "successful" but is not a "to <X>" transfer.
    assertThat(
        extractor.extract("RM5.00 cashback claim is successful", "anything", now),
    ).isNull()
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.parsing.HeuristicExtractorTest"`
Expected: the three new tests FAIL (`extract(...)` returns null → `!!` throws for the first two; third passes incidentally but keep it).

- [ ] **Step 3: Add the transfer-success shape**

In `HeuristicExtractor.kt`, add the pattern to the `companion object` (next to `CARD_SPEND_PATTERN`):

```kotlin
// Transfer-success shape (full-string head match): "RM<amt> to <RECIPIENT> is success(ful)".
// GX Bank and similar confirmation-style pushes have a `to <recipient>` anchor but no
// out-verb, so the OUT_VERB gate rejects them. The amount-led head + "is successful" tail
// is specific enough to exclude promo noise, so we accept it without requiring a verb.
private val TRANSFER_SUCCESS_PATTERN = Regex(
    """^RM\s*[\d,]+(?:\.\d{2})?\s+to\s+(?<merchant>.+?)\s+is\s+success(?:ful)?\b""",
    RegexOption.IGNORE_CASE,
)
```

In `resolveMerchant()`, try it right after the card-spend pattern and before the `OUT_VERB` gate:

```kotlin
private fun resolveMerchant(text: String): String? {
    CARD_SPEND_PATTERN.matchEntire(text)?.groups?.get("merchant")?.value?.trim()?.let {
        if (it.isNotEmpty()) return it
    }

    TRANSFER_SUCCESS_PATTERN.find(text)?.groups?.get("merchant")?.value?.trim()?.let {
        if (it.isNotEmpty()) return it
    }

    if (!OUT_VERB.containsMatchIn(text)) return null
    // ... unchanged recipient-pattern loop + UNDEFINED_MERCHANT fallback ...
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.parsing.HeuristicExtractorTest"`
Expected: PASS (all tests, including the pre-existing ones — the new shape only fires on `^RM… to … is success…`, so `rejects_text_without_outgoing_verb` and the promo test still return null).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/parsing/HeuristicExtractor.kt app/src/test/java/cy/txtracker/parsing/HeuristicExtractorTest.kt
git commit -m "Parser: accept GX 'RM<amt> to <recipient> is successful' transfers"
```

---

## Task 6: Auto-promote — pipeline, repository, listener, Tracked Apps toggle

**Files:**
- Modify: `app/src/main/java/cy/txtracker/service/CapturePipeline.kt`
- Modify: `app/src/main/java/cy/txtracker/service/TxNotificationListener.kt`
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` (`TrackedPackageRow`, `observeTrackedPackages`, `buildTrackedPackageRows`, new methods)
- Modify: `app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsScreen.kt`
- Test: `app/src/test/java/cy/txtracker/service/CapturePipelineTest.kt`, `app/src/test/java/cy/txtracker/data/CustomLabelAndAutoPromoteTest.kt`

- [ ] **Step 1: Write failing pipeline tests**

Append to `CapturePipelineTest.kt`:

```kotlin
@Test
fun auto_promote_package_amount_only_text_goes_to_home_as_undefined_merchant() {
    val decision = pipeline.decide(
        packageName = "my.com.gxsbank",
        rawText = "Your balance updated. RM50.00 something",
        rewrittenText = "Your balance updated. RM50.00 something",
        postedAt = now,
        symbolDefaults = emptyMap(),
        capturedAt = now,
        isAutoPromote = true,
    )
    assertThat(decision).isInstanceOf(CaptureDecision.Parsed::class.java)
    val parsed = (decision as CaptureDecision.Parsed).parsed
    assertThat(parsed.amountMinor).isEqualTo(5000L)
    assertThat(parsed.merchantRaw).isEqualTo(cy.txtracker.data.UNDEFINED_MERCHANT)
    assertThat(parsed.direction).isEqualTo(cy.txtracker.data.Direction.OUT)
}

@Test
fun auto_promote_does_not_override_rejected_package() {
    val decision = pipeline.decide(
        packageName = "my.com.gxsbank",
        rawText = "Your balance updated. RM50.00 something",
        rewrittenText = "Your balance updated. RM50.00 something",
        postedAt = now,
        symbolDefaults = emptyMap(),
        capturedAt = now,
        isAutoPromote = true,
        isRejected = true,
    )
    assertThat(decision).isInstanceOf(CaptureDecision.Pooled::class.java)
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.service.CapturePipelineTest"`
Expected: FAIL — `isAutoPromote` is not a parameter of `decide`.

- [ ] **Step 3: Add `isAutoPromote` to `CapturePipeline.decide`**

In `CapturePipeline.kt` add imports `cy.txtracker.data.Direction`, `cy.txtracker.data.UNDEFINED_MERCHANT`, `cy.txtracker.parsing.ParsedTransaction` (ParsedTransaction is already imported). Add the parameter and branch:

```kotlin
fun decide(
    packageName: String,
    rawText: String,
    rewrittenText: String,
    postedAt: Instant,
    symbolDefaults: Map<String, String>,
    capturedAt: Instant,
    isRejected: Boolean = false,
    isAutoPromote: Boolean = false,
): CaptureDecision {
    // ... unchanged heuristic block ...

    val amount = NotificationAmountParser.findFirst(rewrittenText, symbolDefaults)
        ?: return CaptureDecision.Dropped

    // Trusted package: a confident parse failed, but the user asked us to surface anything
    // with an amount on home. Land it as a PENDING row with an UNDEFINED merchant for them
    // to label. Rejected packages never auto-promote — rejection wins.
    if (isAutoPromote && !isRejected) {
        return CaptureDecision.Parsed(
            ParsedTransaction(
                amountMinor = amount.amountMinor,
                currency = amount.currency,
                merchantRaw = UNDEFINED_MERCHANT,
                occurredAt = postedAt,
                sourceApp = packageName,
                rawText = rawText,
                direction = Direction.OUT,
            ),
        )
    }

    return CaptureDecision.Pooled(
        // ... unchanged ...
    )
}
```

- [ ] **Step 4: Run pipeline tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.service.CapturePipelineTest"`
Expected: PASS (new + existing).

- [ ] **Step 5: Pass `isAutoPromote` from the listener**

In `TxNotificationListener.kt`, in `onNotificationPosted`'s coroutine, after `val isRejected = ...`:

```kotlin
val isAutoPromote = repository.isAutoPromote(sbn.packageName)
```

Add `isAutoPromote = isAutoPromote,` to the `capturePipeline.decide(...)` call.

- [ ] **Step 6: Add repository auto-promote methods + tracked-row flag**

In `TransactionRepository.kt`:

```kotlin
suspend fun isAutoPromote(packageName: String): Boolean =
    autoPromoteSourceDao.isAutoPromote(packageName)

suspend fun setAutoPromote(
    packageName: String,
    enabled: Boolean,
    now: Instant = Clock.System.now(),
) {
    if (enabled) autoPromoteSourceDao.insert(AutoPromoteSource(packageName, now))
    else autoPromoteSourceDao.delete(packageName)
}
```

Add `val autoPromote: Boolean` to `TrackedPackageRow` (after `lastCapturedAt`):

```kotlin
data class TrackedPackageRow(
    val packageName: String,
    val label: String,
    val status: PackageStatus,
    val isBuiltIn: Boolean,
    val poolEntryCountLast30Days: Int,
    val lastCapturedAt: Instant?,
    val autoPromote: Boolean = false,
)
```

Extend `observeTrackedPackages()` to combine the auto-promote flow as a 5th source (the 5-arg `combine` overload):

```kotlin
fun observeTrackedPackages(): Flow<List<TrackedPackageRow>> =
    combine(
        approvedSourceDao.observeAllPackageNames(),
        rejectedSourceDao.observeAllPackageNames(),
        flow {
            emitAll(capturedNotificationDao.observePackageStatsSince(Clock.System.now() - 30.days))
        },
        customSourceLabelDao.observeAll(),
        autoPromoteSourceDao.observeAllPackageNames(),
    ) { approved, rejected, stats, labels, autoPromote ->
        buildTrackedPackageRows(
            approved = approved.toSet(),
            rejected = rejected.toSet(),
            stats = stats,
            customLabels = labels.associate { it.packageName to it.label },
            autoPromote = autoPromote.toSet(),
        )
    }
```

Update `buildTrackedPackageRows` to take `autoPromote: Set<String>` and set `autoPromote = packageName in autoPromote` on each row.

- [ ] **Step 7: Add an auto-promote repo test**

Append to `CustomLabelAndAutoPromoteTest.kt`:

```kotlin
@Test
fun setAutoPromote_true_inserts_and_false_deletes() = runTest {
    val dao = mockk<AutoPromoteSourceDao>(relaxed = true)
    val repo = makeRepoWith(autoPromote = dao)
    repo.setAutoPromote("my.com.gxsbank", true, now)
    coVerify { dao.insert(match { it.packageName == "my.com.gxsbank" }) }
    repo.setAutoPromote("my.com.gxsbank", false, now)
    coVerify { dao.delete("my.com.gxsbank") }
}
```

Add a `makeRepoWith(autoPromote: AutoPromoteSourceDao)` helper that mirrors `makeRepo()` but injects the given `autoPromoteSourceDao`. (Copy `makeRepo()`'s body and replace the `autoPromoteSourceDao = mockk(relaxed = true),` line with `autoPromoteSourceDao = autoPromote,`.) Add `import io.mockk.coVerify` if not present.

- [ ] **Step 8: Add the auto-promote toggle to the Tracked Apps screen**

In `TrackedAppsViewModel.kt`:

```kotlin
fun setAutoPromote(packageName: String, enabled: Boolean) {
    viewModelScope.launch { repository.setAutoPromote(packageName, enabled) }
}
```

In `TrackedAppsScreen.kt`, inside `PackageActionSheet`, for `PackageStatus.TRACKED` only, add a toggle row. Add params `autoPromote: Boolean` and `onAutoPromoteChange: (Boolean) -> Unit` to `PackageActionSheet`, and import `androidx.compose.material3.Switch`. Render below the status actions:

```kotlin
if (row.status == PackageStatus.TRACKED) {
    ListItem(
        headlineContent = { Text("Auto-add to home") },
        supportingContent = { Text("Add to home even when details can't be read.") },
        trailingContent = {
            Switch(checked = autoPromote, onCheckedChange = onAutoPromoteChange)
        },
    )
}
```

Wire it at the call site:

```kotlin
PackageActionSheet(
    row = row,
    // ... existing lambdas ...
    autoPromote = row.autoPromote,
    onAutoPromoteChange = { enabled -> viewModel.setAutoPromote(row.packageName, enabled) },
    // onRename, onDismiss ...
)
```

- [ ] **Step 9: Run tests + compile-gate**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.data.CustomLabelAndAutoPromoteTest" --tests "cy.txtracker.service.CapturePipelineTest"`
Expected: PASS.

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/cy/txtracker/service app/src/main/java/cy/txtracker/data/TransactionRepository.kt app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsViewModel.kt app/src/main/java/cy/txtracker/ui/settings/capture/TrackedAppsScreen.kt app/src/test/java/cy/txtracker/data/CustomLabelAndAutoPromoteTest.kt app/src/test/java/cy/txtracker/service/CapturePipelineTest.kt
git commit -m "Routing: per-app auto-promote to home for trusted sources"
```

---

## Task 7: Batch repository + DAO operations

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/TransactionDao.kt`
- Modify: `app/src/main/java/cy/txtracker/data/CapturedNotificationDao.kt`
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt`
- Test: `app/src/test/java/cy/txtracker/data/BatchTransactionOpsTest.kt`

- [ ] **Step 1: Write failing tests**

Create `BatchTransactionOpsTest.kt`:

```kotlin
package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.parsing.FundingSourceClassifier
import cy.txtracker.parsing.HeuristicExtractor
import cy.txtracker.parsing.ParsedTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class BatchTransactionOpsTest {

    private val now = Instant.parse("2026-06-17T12:00:00Z")
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val approvedDao = mockk<ApprovedSourceDao>(relaxed = true)
    private val capturedDao = mockk<CapturedNotificationDao>(relaxed = true)
    private val reimbursementDao = mockk<ReimbursementEntryDao>(relaxed = true)
    private val heuristic = mockk<HeuristicExtractor>(relaxed = true)

    @Test
    fun confirmTransactions_clears_flag_and_approves_each_distinct_non_manual_source() = runTest {
        coEvery { txDao.distinctSourceAppsForIds(listOf(1L, 2L)) } returns
            listOf("my.com.gxsbank", MANUAL_SOURCE_APP)
        val repo = makeRepo()
        repo.confirmTransactions(listOf(1L, 2L), now)
        coVerify { txDao.clearNeedsVerification(listOf(1L, 2L)) }
        coVerify(exactly = 1) { approvedDao.insert(match { it.packageName == "my.com.gxsbank" }) }
        coVerify(exactly = 0) { approvedDao.insert(match { it.packageName == MANUAL_SOURCE_APP }) }
    }

    @Test
    fun deleteTransactionsWithSnapshot_returns_snapshots_and_deletes() = runTest {
        val tx = Transaction(
            id = 5L, amountMinor = 100L, currency = "MYR", merchantRaw = "X",
            merchantNormalized = "X", categoryId = null, description = null,
            occurredAt = now, timeBucket = TimeBucket.AFTERNOON, sourceApp = "p",
            rawText = null, direction = Direction.OUT, createdAt = now,
            notificationDedupeKey = "k",
        )
        coEvery { txDao.getById(5L) } returns tx
        coEvery { reimbursementDao.getForTransaction(5L) } returns emptyList()
        val repo = makeRepo()
        val snapshots = repo.deleteTransactionsBody(listOf(5L))
        assertThat(snapshots).hasSize(1)
        assertThat(snapshots[0].transaction.id).isEqualTo(5L)
        coVerify { txDao.delete(5L) }
    }

    @Test
    fun promotePoolEntries_uses_heuristic_merchant_when_resolved_else_undefined() = runTest {
        val pending = CapturedNotification(
            id = 9L, packageName = "my.com.gxsbank", postedAt = now, amountMinor = 400L,
            currency = "MYR", rawText = "RM4.00 to CHEE NYOK LAN is successful",
            rewrittenText = null, disposition = CaptureDisposition.PENDING,
            promotedToTxId = null, capturedAt = now, dedupeKey = "d9",
        )
        coEvery { capturedDao.get(9L) } returns pending
        coEvery { txDao.insert(any()) } returns 77L
        coEvery {
            heuristic.extract("RM4.00 to CHEE NYOK LAN is successful", "my.com.gxsbank", now)
        } returns ParsedTransaction(
            amountMinor = 400L, currency = "MYR", merchantRaw = "CHEE NYOK LAN",
            occurredAt = now, sourceApp = "my.com.gxsbank",
            rawText = "RM4.00 to CHEE NYOK LAN is successful", direction = Direction.OUT,
        )
        val repo = makeRepo()
        repo.promotePoolEntriesBody(listOf(9L), now)
        coVerify {
            txDao.insert(match { it.merchantRaw == "CHEE NYOK LAN" && it.needsVerification })
        }
        coVerify { capturedDao.markPromoted(9L, 77L) }
    }

    private fun makeRepo(): TransactionRepository = TransactionRepository(
        database = mockk(relaxed = true),
        transactionDao = txDao,
        categoryDao = mockk(relaxed = true),
        merchantMappingDao = mockk(relaxed = true),
        descriptionMappingDao = mockk(relaxed = true),
        merchantNoteDao = mockk(relaxed = true),
        userFacingSourceDao = mockk(relaxed = true),
        approvedSourceDao = approvedDao,
        capturedNotificationDao = capturedDao,
        rejectedSourceDao = mockk(relaxed = true),
        trackedCurrencyDao = mockk(relaxed = true),
        tripWindowDao = mockk(relaxed = true),
        packageTextRewriteDao = mockk(relaxed = true),
        fundingSourceDao = mockk(relaxed = true),
        slDebitDao = mockk(relaxed = true),
        reimbursementEntryDao = reimbursementDao,
        customSourceLabelDao = mockk(relaxed = true),
        autoPromoteSourceDao = mockk(relaxed = true),
        categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
        descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
        heuristicExtractor = heuristic,
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk<FundingSourceClassifier>().also {
            coEvery { it.classify(any(), any(), any()) } returns 1L
        },
    )
}
```

(If `TimeBucket.AFTERNOON` is not a real enum constant, use any valid `TimeBucket` value — open `Entities.kt` to confirm the enum's constants.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.data.BatchTransactionOpsTest"`
Expected: FAIL — `distinctSourceAppsForIds`, `clearNeedsVerification`, `deleteTransactionsBody`, `promotePoolEntriesBody` unresolved.

- [ ] **Step 3: Add the DAO batch queries**

In `TransactionDao.kt`:

```kotlin
@Query("SELECT DISTINCT sourceApp FROM transactions WHERE id IN (:ids)")
suspend fun distinctSourceAppsForIds(ids: List<Long>): List<String>

@Query("UPDATE transactions SET needsVerification = 0 WHERE id IN (:ids)")
suspend fun clearNeedsVerification(ids: List<Long>)
```

In `CapturedNotificationDao.kt`:

```kotlin
@Query("UPDATE captured_notifications SET disposition = 'NOISE' WHERE id IN (:ids)")
suspend fun markNoiseBatch(ids: List<Long>)
```

- [ ] **Step 4: Add the repository batch methods**

In `TransactionRepository.kt`. Import `cy.txtracker.ui.edit.DeletedTransaction`.

```kotlin
suspend fun confirmTransactions(ids: List<Long>, now: Instant = Clock.System.now()) {
    if (ids.isEmpty()) return
    database.withTransaction {
        transactionDao.distinctSourceAppsForIds(ids)
            .filter { it != MANUAL_SOURCE_APP }
            .forEach { approvedSourceDao.insert(ApprovedSource(it, now)) }
        transactionDao.clearNeedsVerification(ids)
    }
}

suspend fun deleteTransactions(ids: List<Long>): List<DeletedTransaction> =
    database.withTransaction { deleteTransactionsBody(ids) }

internal suspend fun deleteTransactionsBody(ids: List<Long>): List<DeletedTransaction> =
    ids.mapNotNull { id ->
        val tx = transactionDao.getById(id) ?: return@mapNotNull null
        val reimbursements = reimbursementEntryDao.getForTransaction(id)
        transactionDao.delete(id)
        DeletedTransaction(tx, reimbursements)
    }

suspend fun restoreTransactions(snapshots: List<DeletedTransaction>) =
    database.withTransaction {
        snapshots.forEach { restoreTransactionBody(it.transaction, it.reimbursements) }
    }

suspend fun markPoolEntriesNoise(ids: List<Long>) {
    if (ids.isEmpty()) return
    capturedNotificationDao.markNoiseBatch(ids)
}

suspend fun promotePoolEntries(ids: List<Long>, now: Instant = Clock.System.now()): Int =
    database.withTransaction { promotePoolEntriesBody(ids, now) }

/**
 * Body of [promotePoolEntries], extracted for unit tests (avoids mocking `withTransaction`).
 * For each pending entry, re-runs the heuristic on its raw text to recover a merchant; falls
 * back to UNDEFINED_MERCHANT. Promotes as needsVerification=true so the row lands on home
 * PENDING for the user to label. Returns the count promoted.
 */
internal suspend fun promotePoolEntriesBody(ids: List<Long>, now: Instant): Int {
    var promoted = 0
    for (id in ids) {
        val pool = capturedNotificationDao.get(id) ?: continue
        val parsed = heuristicExtractor.extract(
            text = pool.rewrittenText ?: pool.rawText,
            sourceApp = pool.packageName,
            postedAt = pool.postedAt,
        )
        val merchant = parsed?.merchantRaw?.takeIf { it.isNotBlank() } ?: UNDEFINED_MERCHANT
        val merchantNormalized = normalizeMerchant(merchant)
        val dedupeKey = computeDedupeKey(
            amountMinor = pool.amountMinor,
            merchantNormalized = merchantNormalized,
            occurredAt = pool.postedAt,
            currency = pool.currency,
        )
        val rowId = transactionDao.insert(
            Transaction(
                amountMinor = pool.amountMinor,
                currency = pool.currency,
                merchantRaw = merchant,
                merchantNormalized = merchantNormalized,
                categoryId = null,
                description = null,
                occurredAt = pool.postedAt,
                timeBucket = bucketOf(pool.postedAt),
                sourceApp = pool.packageName,
                rawText = pool.rawText,
                direction = Direction.OUT,
                createdAt = now,
                notificationDedupeKey = dedupeKey,
                needsVerification = true,
            ),
        )
        val txId = rowId.takeIf { it >= 0 } ?: continue
        capturedNotificationDao.markPromoted(id, txId)
        rejectedSourceDao.delete(pool.packageName)
        approvedSourceDao.insert(ApprovedSource(pool.packageName, now))
        promoted++
    }
    return promoted
}
```

(`normalizeMerchant`, `computeDedupeKey`, and `bucketOf` are existing private helpers used by `promotePoolEntryBody` — reuse them. Confirm their names by reading `promotePoolEntryBody` at `TransactionRepository.kt:617`.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.data.BatchTransactionOpsTest"`
Expected: PASS.

- [ ] **Step 6: Compile-gate and commit**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/cy/txtracker/data app/src/test/java/cy/txtracker/data/BatchTransactionOpsTest.kt
git commit -m "Repo: batch confirm/delete/restore/noise/promote operations"
```

---

## Task 8: Home page multi-select

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt`
- Test: `app/src/test/java/cy/txtracker/ui/home/HomeSelectionTest.kt`

Selection state is kept in dedicated flows (not folded into the large `HomeUiState` combine) to avoid disturbing existing state assembly.

- [ ] **Step 1: Write failing ViewModel tests**

Create `HomeSelectionTest.kt`:

```kotlin
package cy.txtracker.ui.home

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.TransactionRepository
import cy.txtracker.notify.DeeplinkBus
import cy.txtracker.service.CurrencyPrefs
import cy.txtracker.service.FeatureFlags
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HomeSelectionTest {

    private val repository = mockk<TransactionRepository>(relaxed = true)

    private fun vm(): HomeViewModel {
        every { repository.observeCurrencyReviewTransactions() } returns flowOf(emptyList())
        every { repository.observeAllCategories() } returns flowOf(emptyList())
        every { repository.observeFundingSources() } returns flowOf(emptyList())
        every { repository.observeSlDebitBalance() } returns flowOf(0L)
        every { repository.observeSlDebitAccount() } returns flowOf(null)
        every { repository.observeMerchantNotes() } returns flowOf(emptyList())
        val prefs = mockk<CurrencyPrefs>(relaxed = true).also {
            every { it.dismissed } returns MutableStateFlow(emptySet())
        }
        val flags = mockk<FeatureFlags>(relaxed = true).also {
            every { it.slDebitUnlocked } returns MutableStateFlow(false)
        }
        val bus = mockk<DeeplinkBus>(relaxed = true).also {
            every { it.forHome } returns flowOf()
        }
        return HomeViewModel(repository, prefs, bus, flags)
    }

    @Test
    fun enter_toggle_clear_selection_transitions() = runTest {
        val m = vm()
        m.enterSelection(1L)
        assertThat(m.selectionMode.value).isTrue()
        assertThat(m.selectedIds.value).containsExactly(1L)
        m.toggleSelect(2L)
        assertThat(m.selectedIds.value).containsExactly(1L, 2L)
        m.toggleSelect(1L)
        assertThat(m.selectedIds.value).containsExactly(2L)
        m.clearSelection()
        assertThat(m.selectionMode.value).isFalse()
        assertThat(m.selectedIds.value).isEmpty()
    }

    @Test
    fun confirmSelected_calls_repo_and_clears() = runTest {
        val m = vm()
        m.enterSelection(3L)
        m.toggleSelect(4L)
        m.confirmSelected()
        coVerify { repository.confirmTransactions(match { it.toSet() == setOf(3L, 4L) }) }
        assertThat(m.selectionMode.value).isFalse()
    }

    @Test
    fun deleteSelected_passes_snapshots_to_callback_and_clears() = runTest {
        val m = vm()
        m.enterSelection(5L)
        var got: List<cy.txtracker.ui.edit.DeletedTransaction>? = null
        m.deleteSelected { snapshots -> got = snapshots }
        coVerify { repository.deleteTransactions(listOf(5L)) }
        assertThat(m.selectionMode.value).isFalse()
        // callback fired (snapshots may be empty given the relaxed mock returns emptyList)
        assertThat(got).isNotNull()
    }
}
```

(Match the exact repository accessor names this VM uses — see `HomeViewModel.kt` lines 46-83. If any `observe*` accessor differs, stub the one the constructor actually calls.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.ui.home.HomeSelectionTest"`
Expected: FAIL — `selectionMode`, `selectedIds`, `enterSelection`, etc. unresolved.

- [ ] **Step 3: Add selection state + actions to `HomeViewModel`**

Add fields and methods (imports `kotlinx.coroutines.flow.asStateFlow` already-used patterns; add if missing):

```kotlin
private val _selectionMode = MutableStateFlow(false)
val selectionMode: StateFlow<Boolean> = _selectionMode
private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
val selectedIds: StateFlow<Set<Long>> = _selectedIds

fun enterSelection(id: Long) {
    _selectionMode.value = true
    _selectedIds.value = setOf(id)
}

fun toggleSelect(id: Long) {
    _selectedIds.update { if (id in it) it - id else it + id }
}

fun clearSelection() {
    _selectionMode.value = false
    _selectedIds.value = emptySet()
}

fun confirmSelected() {
    val ids = _selectedIds.value.toList()
    if (ids.isEmpty()) { clearSelection(); return }
    viewModelScope.launch { repository.confirmTransactions(ids) }
    clearSelection()
}

fun deleteSelected(onDeleted: (List<DeletedTransaction>) -> Unit) {
    val ids = _selectedIds.value.toList()
    if (ids.isEmpty()) { clearSelection(); return }
    viewModelScope.launch {
        val snapshots = repository.deleteTransactions(ids)
        onDeleted(snapshots)
    }
    clearSelection()
}
```

- [ ] **Step 4: Run VM tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.ui.home.HomeSelectionTest"`
Expected: PASS.

- [ ] **Step 5: Wire selection UI into `HomeRoute`/`HomeScreen`**

In `HomeRoute`: collect the new flows and pass them down; handle the batch-delete undo snackbar.

```kotlin
val selectionMode by viewModel.selectionMode.collectAsState()
val selectedIds by viewModel.selectedIds.collectAsState()
```

Pass to `HomeScreen`:

```kotlin
selectionMode = selectionMode,
selectedIds = selectedIds,
onRowLongPress = { tx -> viewModel.enterSelection(tx.id) },
onRowToggle = { tx -> viewModel.toggleSelect(tx.id) },
onClearSelection = viewModel::clearSelection,
onConfirmSelected = viewModel::confirmSelected,
onDeleteSelected = {
    viewModel.deleteSelected { snapshots ->
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "${snapshots.size} deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreTransactionsBatch(snapshots)
            }
        }
    }
},
```

Add `restoreTransactionsBatch` to `HomeViewModel`:

```kotlin
fun restoreTransactionsBatch(snapshots: List<DeletedTransaction>) {
    viewModelScope.launch { repository.restoreTransactions(snapshots) }
}
```

In `HomeScreen`, add the new params:

```kotlin
selectionMode: Boolean = false,
selectedIds: Set<Long> = emptySet(),
onRowLongPress: (Transaction) -> Unit = {},
onRowToggle: (Transaction) -> Unit = {},
onClearSelection: () -> Unit = {},
onConfirmSelected: () -> Unit = {},
onDeleteSelected: () -> Unit = {},
```

Replace the `topBar` so it shows a contextual action bar when `selectionMode`:

```kotlin
topBar = {
    if (selectionMode) {
        TopAppBar(
            title = { Text("${selectedIds.size} selected") },
            navigationIcon = {
                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                }
            },
            actions = {
                IconButton(onClick = onConfirmSelected, enabled = selectedIds.isNotEmpty()) {
                    Icon(Icons.Filled.Check, contentDescription = "Confirm selected")
                }
                IconButton(onClick = onDeleteSelected, enabled = selectedIds.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                }
            },
        )
    } else {
        CenterAlignedTopAppBar(
            // ... existing month nav + settings ...
        )
    }
},
```

Add imports: `androidx.compose.material3.TopAppBar`, `androidx.compose.material.icons.filled.Close`, `androidx.compose.material.icons.filled.Check`, `androidx.compose.material.icons.filled.Delete`.

Thread selection into the list. Change `TransactionList` to accept and forward `selectionMode`, `selectedIds`, `onRowLongPress`, `onRowToggle`; pass them from `HomeScreen` (`TransactionList(days = ..., selectionMode = selectionMode, selectedIds = selectedIds, onRowLongPress = onRowLongPress, onRowToggle = onRowToggle, onTransactionClick = onTransactionClick, ...)`).

In `TransactionList`, forward to each `TransactionRow`:

```kotlin
items(group.transactions, key = { it.transaction.id }) { row ->
    TransactionRow(
        row = row,
        note = notesByMerchant[row.transaction.merchantNormalized],
        amountFormatter = amountFormatter,
        selectionMode = selectionMode,
        selected = row.transaction.id in selectedIds,
        onClick = {
            if (selectionMode) onRowToggle(row.transaction)
            else onTransactionClick(row.transaction)
        },
        onLongClick = { onRowLongPress(row.transaction) },
    )
}
```

Update `TransactionRow` to support long-press + a selected highlight + a leading checkbox in selection mode. Replace its `Surface(onClick = onClick, ...)` with a combined-clickable surface and add a `Checkbox`. Add imports: `androidx.compose.foundation.combinedClickable`, `androidx.compose.foundation.ExperimentalFoundationApi`, `androidx.compose.material3.Checkbox`.

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TransactionRow(
    row: TransactionWithCategory,
    note: String?,
    amountFormatter: (Long) -> String,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Surface(
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            // The existing Column(...) body, unchanged, goes here (wrap it so it takes weight(1f)).
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp)) {
                // ... existing row content (merchant/time/note, RowAmount, chips, hint) ...
            }
        }
    }
}
```

(Preserve the existing inner content exactly — only wrap it with the checkbox row and selectable background.)

- [ ] **Step 6: Compile-gate and commit**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.ui.home.HomeSelectionTest"`
Expected: PASS.

```bash
git add app/src/main/java/cy/txtracker/ui/home app/src/test/java/cy/txtracker/ui/home/HomeSelectionTest.kt
git commit -m "Home: multi-select confirm/delete with contextual action bar"
```

---

## Task 9: Notification pool multi-select

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/settings/capture/PoolViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/settings/capture/PoolScreen.kt`
- Test: `app/src/test/java/cy/txtracker/ui/settings/capture/PoolSelectionTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Create `PoolSelectionTest.kt`:

```kotlin
package cy.txtracker.ui.settings.capture

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.TransactionRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PoolSelectionTest {

    private val repository = mockk<TransactionRepository>(relaxed = true)

    private fun vm(): PoolViewModel {
        every { repository.observeAllCategories() } returns flowOf(emptyList())
        every { repository.observePool(any(), any()) } returns flowOf(emptyList())
        every { repository.observeCustomLabels() } returns flowOf(emptyMap())
        return PoolViewModel(repository)
    }

    @Test
    fun enter_toggle_clear_selection() = runTest {
        val m = vm()
        m.enterSelection(1L)
        assertThat(m.selectionMode.value).isTrue()
        m.toggleSelect(2L)
        assertThat(m.selectedIds.value).containsExactly(1L, 2L)
        m.clearSelection()
        assertThat(m.selectionMode.value).isFalse()
        assertThat(m.selectedIds.value).isEmpty()
    }

    @Test
    fun approveSelected_promotes_and_clears() = runTest {
        val m = vm()
        m.enterSelection(3L)
        m.approveSelected()
        coVerify { repository.promotePoolEntries(listOf(3L)) }
        assertThat(m.selectionMode.value).isFalse()
    }

    @Test
    fun rejectSelected_marks_noise_and_clears() = runTest {
        val m = vm()
        m.enterSelection(4L)
        m.rejectSelected()
        coVerify { repository.markPoolEntriesNoise(listOf(4L)) }
        assertThat(m.selectionMode.value).isFalse()
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.ui.settings.capture.PoolSelectionTest"`
Expected: FAIL — selection members unresolved.

- [ ] **Step 3: Add selection state + actions to `PoolViewModel`**

```kotlin
private val _selectionMode = MutableStateFlow(false)
val selectionMode: StateFlow<Boolean> = _selectionMode
private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
val selectedIds: StateFlow<Set<Long>> = _selectedIds

fun enterSelection(id: Long) {
    _selectionMode.value = true
    _selectedIds.value = setOf(id)
}

fun toggleSelect(id: Long) {
    _selectedIds.update { if (id in it) it - id else it + id }
}

fun clearSelection() {
    _selectionMode.value = false
    _selectedIds.value = emptySet()
}

fun approveSelected() {
    val ids = _selectedIds.value.toList()
    if (ids.isEmpty()) { clearSelection(); return }
    viewModelScope.launch { repository.promotePoolEntries(ids) }
    clearSelection()
}

fun rejectSelected() {
    val ids = _selectedIds.value.toList()
    if (ids.isEmpty()) { clearSelection(); return }
    viewModelScope.launch { repository.markPoolEntriesNoise(ids) }
    clearSelection()
}
```

- [ ] **Step 4: Run VM tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.ui.settings.capture.PoolSelectionTest"`
Expected: PASS.

- [ ] **Step 5: Wire selection UI into `PoolScreen`**

Collect the new flows in `PoolScreen`:

```kotlin
val selectionMode by viewModel.selectionMode.collectAsState()
val selectedIds by viewModel.selectedIds.collectAsState()
```

Replace the `TopAppBar` with a contextual bar when `selectionMode`:

```kotlin
topBar = {
    if (selectionMode) {
        TopAppBar(
            title = { Text("${selectedIds.size} selected") },
            navigationIcon = {
                IconButton(onClick = viewModel::clearSelection) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                }
            },
            actions = {
                IconButton(onClick = viewModel::approveSelected, enabled = selectedIds.isNotEmpty()) {
                    Icon(Icons.Filled.Check, contentDescription = "Approve selected")
                }
                IconButton(onClick = viewModel::rejectSelected, enabled = selectedIds.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = "Reject selected")
                }
            },
        )
    } else {
        TopAppBar(
            title = { Text(if (packageName == null) "Notification pool" else "Pool: $packageName") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }
},
```

Add imports: `androidx.compose.material.icons.filled.Close`, `androidx.compose.material.icons.filled.Check`, `androidx.compose.material.icons.filled.Delete`.

Make rows selectable. In the `items(group.rows, ...)` block, pass selection through to `PoolRow`:

```kotlin
PoolRow(
    row = row,
    label = state.labelFor(row.packageName),
    expanded = row.id in expandedIds,
    onToggleExpanded = { /* unchanged */ },
    selectionMode = selectionMode,
    selected = row.id in selectedIds,
    onClick = {
        if (selectionMode) viewModel.toggleSelect(row.id) else actionRow = row
    },
    onLongClick = { viewModel.enterSelection(row.id) },
)
```

Update `PoolRow` to accept `label: String`, `selectionMode: Boolean`, `selected: Boolean`, `onLongClick: () -> Unit`, render a leading `Checkbox` in selection mode and a selected background, and use `combinedClickable`. Add imports: `androidx.compose.foundation.combinedClickable`, `androidx.compose.foundation.ExperimentalFoundationApi`, `androidx.compose.material3.Checkbox`.

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PoolRow(
    row: CapturedNotification,
    label: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        leadingContent = if (selectionMode) {
            { Checkbox(checked = selected, onCheckedChange = { onClick() }) }
        } else null,
        headlineContent = {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(label)
                Text(formatAmount(row.amountMinor, row.currency))
            }
        },
        supportingContent = {
            // ... existing supporting content unchanged ...
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}
```

- [ ] **Step 6: Compile-gate and commit**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (full suite green).

```bash
git add app/src/main/java/cy/txtracker/ui/settings/capture/PoolViewModel.kt app/src/main/java/cy/txtracker/ui/settings/capture/PoolScreen.kt app/src/test/java/cy/txtracker/ui/settings/capture/PoolSelectionTest.kt
git commit -m "Pool: multi-select approve/reject with contextual action bar"
```

---

## Final verification

- [ ] Run the full unit-test suite: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL.
- [ ] Compile production + androidTest: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL.
- [ ] Manual device smoke test (when a device is available; not part of the automated gate): rename an app and confirm it shows on Tracked Apps + Pool; toggle auto-promote on GX; trigger a GX "RM… to … is successful" notification and confirm it lands on home; multi-select confirm/delete on home and approve/reject on pool.

---

## Notes / decisions baked in
- One Room migration (`14→15`) creates both tables; no backfill.
- GX gets BOTH fixes: the parser shape (real merchant) and the generic auto-promote toggle (fallback for unseen formats).
- Pool batch-approve re-runs the (now GX-aware) heuristic per entry and lands rows as `needsVerification=true` (PENDING on home), merchant = resolved-or-`UNDEFINED_MERCHANT`.
- Home batch-confirm replicates the single-confirm `ApprovedSource` side effect for each distinct non-manual source.
- Selection state lives in dedicated ViewModel flows, not inside the existing `HomeUiState`/`PoolUiState` combine pipelines.
