# Date-range CSV Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Commits:** This project's owner runs `git commit` themselves. Each task ends with a **commit checkpoint** that states the suggested message — do NOT auto-commit; hand off to the user (or wait for explicit instruction) at each checkpoint.

**Goal:** Add an optional start→end date-range filter to CSV export; a blank range preserves today's exact "export everything" behavior.

**Architecture:** In-memory filtering (Approach A). A new pure value type `ExportDateRange` plus two pure helpers (`malaysiaDateRangeBounds`, `filterByRange`) live in `CsvExporter.kt`. The exporter reuses the existing `getAllTransactionsOnceForCurrency` query and filters the list before building the CSV. The range is selected in the existing export chooser sheet via a Material3 `DateRangePicker` and threaded through the ViewModel. No new SQL query, DAO method, or schema change.

**Tech Stack:** Kotlin, kotlinx-datetime, Jetpack Compose + Material3 (BOM 2024.10.01), Hilt, JUnit + Truth.

**Date semantics (read once, applies throughout):** `Transaction.occurredAt` is a `kotlinx.datetime.Instant`. The CSV groups days in `cy.txtracker.domain.MalaysiaTimeZone` (UTC+8, no DST). A selected range is interpreted as Malaysia-local calendar days: `start = startDate @ 00:00 MYT`, `endExclusive = (endDate + 1 day) @ 00:00 MYT`, filter keeps `occurredAt >= start && occurredAt < endExclusive`. Material3's `DateRangePicker` reports selections as **UTC-midnight** epoch millis, so its millis are converted to a `LocalDate` via `TimeZone.UTC` (the calendar date the user tapped), then re-interpreted in MYT by the export helpers.

---

## Task 1: Pure helpers — `ExportDateRange`, `malaysiaDateRangeBounds`, `filterByRange` (TDD)

**Files:**
- Create: `app/src/test/java/cy/txtracker/export/FilterByRangeTest.kt`
- Modify: `app/src/main/java/cy/txtracker/export/CsvExporter.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/cy/txtracker/export/FilterByRangeTest.kt`:

```kotlin
package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Direction
import cy.txtracker.data.Transaction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test

class FilterByRangeTest {

    private fun tx(occurredAt: Instant) = Transaction(
        id = 0,
        amountMinor = 1000,
        currency = "MYR",
        merchantRaw = "M",
        merchantNormalized = "M",
        categoryId = null,
        description = null,
        occurredAt = occurredAt,
        timeBucket = TimeBucket.MIDDAY,
        sourceApp = "manual",
        rawText = null,
        direction = Direction.OUT,
        createdAt = occurredAt,
        notificationDedupeKey = "k-$occurredAt",
        fundingSourceId = null,
    )

    private val january = ExportDateRange(LocalDate(2026, 1, 1), LocalDate(2026, 1, 31))

    @Test
    fun bounds_are_malaysia_start_of_day_and_next_day_after_end() {
        val (start, endExclusive) = malaysiaDateRangeBounds(january)
        // 2026-01-01 00:00 +08:00 == 2025-12-31 16:00 UTC
        assertThat(start).isEqualTo(Instant.parse("2025-12-31T16:00:00Z"))
        // 2026-02-01 00:00 +08:00 == 2026-01-31 16:00 UTC
        assertThat(endExclusive).isEqualTo(Instant.parse("2026-01-31T16:00:00Z"))
    }

    @Test
    fun tx_exactly_at_start_is_included() {
        val t = tx(Instant.parse("2025-12-31T16:00:00Z")) // 2026-01-01 00:00 MYT
        assertThat(filterByRange(listOf(t), january)).containsExactly(t)
    }

    @Test
    fun tx_late_on_end_day_is_included() {
        val t = tx(Instant.parse("2026-01-31T15:30:00Z")) // 2026-01-31 23:30 MYT
        assertThat(filterByRange(listOf(t), january)).containsExactly(t)
    }

    @Test
    fun tx_at_next_malaysia_midnight_is_excluded() {
        val t = tx(Instant.parse("2026-01-31T16:00:00Z")) // 2026-02-01 00:00 MYT == endExclusive
        assertThat(filterByRange(listOf(t), january)).isEmpty()
    }

    @Test
    fun tx_just_before_start_is_excluded() {
        val t = tx(Instant.parse("2025-12-31T15:00:00Z")) // 2025-12-31 23:00 MYT
        assertThat(filterByRange(listOf(t), january)).isEmpty()
    }

    @Test
    fun filtered_by_malaysia_day_not_utc_day() {
        // 2026-01-31 17:00 UTC is still Jan 31 in UTC, but 2026-02-01 01:00 in MYT → February.
        // A January range must EXCLUDE it; proves we filter by Malaysia day, not UTC day.
        val t = tx(Instant.parse("2026-01-31T17:00:00Z"))
        assertThat(filterByRange(listOf(t), january)).isEmpty()
    }

    @Test
    fun single_day_range_includes_that_whole_malaysia_day() {
        val day = ExportDateRange(LocalDate(2026, 1, 1), LocalDate(2026, 1, 1))
        val t = tx(Instant.parse("2026-01-01T15:30:00Z")) // 2026-01-01 23:30 MYT
        assertThat(filterByRange(listOf(t), day)).containsExactly(t)
    }

    @Test
    fun null_range_returns_input_unchanged() {
        val txs = listOf(tx(Instant.parse("2020-06-15T00:00:00Z")))
        assertThat(filterByRange(txs, null)).isEqualTo(txs)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew testDebugUnitTest --tests "cy.txtracker.export.FilterByRangeTest"`
Expected: FAIL — compilation error, `unresolved reference: ExportDateRange` (and `malaysiaDateRangeBounds`, `filterByRange`).

- [ ] **Step 3: Implement the helpers**

In `app/src/main/java/cy/txtracker/export/CsvExporter.kt`, add these imports to the existing import block (alongside the current `import kotlinx.datetime.LocalDate` and `import kotlinx.datetime.toLocalDateTime`):

```kotlin
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
```

Then, in the pure-helpers section (the part of the file below the `CsvExporter` class — e.g. directly above the `fun writeCsv(...)` declaration), add:

```kotlin
/**
 * Optional CSV export date filter. [start] and [end] are inclusive and interpreted as
 * Malaysia-local calendar days, matching how [buildCsv] groups rows by day.
 */
data class ExportDateRange(val start: LocalDate, val end: LocalDate)

/**
 * Converts an [ExportDateRange] to its instant bounds: `[start-of-start-day,
 * start-of-(end+1)-day)` in [MalaysiaTimeZone]. The upper bound is exclusive so the entire
 * [end] day is included. Pure — no I/O. MYT has no DST, so the bounds are unambiguous.
 */
fun malaysiaDateRangeBounds(range: ExportDateRange): Pair<Instant, Instant> {
    val start = range.start.atStartOfDayIn(MalaysiaTimeZone)
    val endExclusive = range.end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(MalaysiaTimeZone)
    return start to endExclusive
}

/**
 * Returns [transactions] filtered to [range]; a null range returns the list unchanged
 * (the all-time export path). Keeps rows whose `occurredAt` is in `[start, endExclusive)`.
 */
fun filterByRange(
    transactions: List<Transaction>,
    range: ExportDateRange?,
): List<Transaction> {
    if (range == null) return transactions
    val (start, endExclusive) = malaysiaDateRangeBounds(range)
    return transactions.filter { it.occurredAt >= start && it.occurredAt < endExclusive }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew testDebugUnitTest --tests "cy.txtracker.export.FilterByRangeTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit checkpoint** (owner runs)

Suggested message:
```
Export: add ExportDateRange + pure date-range filter helpers
```
Files: `app/src/main/java/cy/txtracker/export/CsvExporter.kt`, `app/src/test/java/cy/txtracker/export/FilterByRangeTest.kt`

---

## Task 2: Thread `range` through `CsvExporter` export methods + filename

**Files:**
- Modify: `app/src/main/java/cy/txtracker/export/CsvExporter.kt`

- [ ] **Step 1: Add the `range` parameter to `exportCsv` and filter before writing**

Replace the existing `exportCsv` function (currently `CsvExporter.kt:45-53`):

```kotlin
    suspend fun exportCsv(currency: String): Uri {
        val transactions = repository.getAllTransactionsOnceForCurrency(currency)
        val categories = repository.getAllCategoriesOnce()
        val fundingSourcesById = repository.observeFundingSources().first().associateBy { it.id }
        val dir = exportDir()
        val file = File(dir, "transactions-$currency-${System.currentTimeMillis()}.csv")
        file.outputStream().use { writeCsv(transactions, categories, fundingSourcesById, it) }
        return uriFor(file)
    }
```

with (note the new `range` param defaulting to null, the `filterByRange` call, and `csvFileName`):

```kotlin
    suspend fun exportCsv(currency: String, range: ExportDateRange? = null): Uri {
        val transactions = filterByRange(
            repository.getAllTransactionsOnceForCurrency(currency),
            range,
        )
        val categories = repository.getAllCategoriesOnce()
        val fundingSourcesById = repository.observeFundingSources().first().associateBy { it.id }
        val dir = exportDir()
        val file = File(dir, csvFileName(currency, range))
        file.outputStream().use { writeCsv(transactions, categories, fundingSourcesById, it) }
        return uriFor(file)
    }
```

- [ ] **Step 2: Add the `range` parameter to `exportAllCurrenciesZip` and filter per currency**

Replace the existing `exportAllCurrenciesZip` function (currently `CsvExporter.kt:60-78`):

```kotlin
    suspend fun exportAllCurrenciesZip(): Uri {
        val categories = repository.getAllCategoriesOnce()
        val fundingSourcesById = repository.observeFundingSources().first().associateBy { it.id }
        val trackedCodes = repository.observeTrackedCurrencies().first().map { it.code }
        val codes = (listOf("MYR") + trackedCodes).distinct()

        val dir = exportDir()
        val zipFile = File(dir, "transactions-all-${System.currentTimeMillis()}.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            for (code in codes) {
                val rows = repository.getAllTransactionsOnceForCurrency(code)
                if (rows.isEmpty()) continue
                zip.putNextEntry(java.util.zip.ZipEntry("transactions-$code.csv"))
                writeCsv(rows, categories, fundingSourcesById, zip)
                zip.closeEntry()
            }
        }
        return uriFor(zipFile)
    }
```

with (filter each currency's rows; the existing "skip empty currency" behavior now means "empty after filtering"):

```kotlin
    suspend fun exportAllCurrenciesZip(range: ExportDateRange? = null): Uri {
        val categories = repository.getAllCategoriesOnce()
        val fundingSourcesById = repository.observeFundingSources().first().associateBy { it.id }
        val trackedCodes = repository.observeTrackedCurrencies().first().map { it.code }
        val codes = (listOf("MYR") + trackedCodes).distinct()

        val dir = exportDir()
        val zipFile = File(dir, zipFileName(range))
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            for (code in codes) {
                val rows = filterByRange(repository.getAllTransactionsOnceForCurrency(code), range)
                if (rows.isEmpty()) continue
                zip.putNextEntry(java.util.zip.ZipEntry("transactions-$code.csv"))
                writeCsv(rows, categories, fundingSourcesById, zip)
                zip.closeEntry()
            }
        }
        return uriFor(zipFile)
    }
```

- [ ] **Step 3: Add the filename helpers**

In the private-helpers area of the `CsvExporter` class (e.g. directly above the existing `private fun exportDir()` at `CsvExporter.kt:84`), add:

```kotlin
    private fun csvFileName(currency: String, range: ExportDateRange?): String {
        val suffix = if (range == null) "" else "-${range.start}_to_${range.end}"
        return "transactions-$currency$suffix-${System.currentTimeMillis()}.csv"
    }

    private fun zipFileName(range: ExportDateRange?): String {
        val suffix = if (range == null) "" else "-${range.start}_to_${range.end}"
        return "transactions-all$suffix-${System.currentTimeMillis()}.zip"
    }
```

(The legacy `export(): Uri = exportCsv("MYR")` at `CsvExporter.kt:82` is unchanged — it uses the `range = null` default, preserving all-time behavior.)

- [ ] **Step 4: Verify it compiles**

Run: `.\gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (No new unit test: the filtering is covered by Task 1; these methods only add file/zip I/O and filename formatting around the already-tested `filterByRange`.)

- [ ] **Step 5: Commit checkpoint** (owner runs)

Suggested message:
```
Export: thread optional date range through CsvExporter + filenames
```
Files: `app/src/main/java/cy/txtracker/export/CsvExporter.kt`

---

## Task 3: Thread `range` through `SettingsViewModel`

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add the import**

Add to the import block (alongside the existing `import cy.txtracker.export.CsvExporter`):

```kotlin
import cy.txtracker.export.ExportDateRange
```

- [ ] **Step 2: Add the `range` parameter to `exportCsv`**

Replace the existing `exportCsv` function (currently `SettingsViewModel.kt:154-165`):

```kotlin
    fun exportCsv(currency: String) {
        if (_exportStatus.value is ExportStatus.Running) return
        _exportStatus.value = ExportStatus.Running
        viewModelScope.launch {
            try {
                val uri = csvExporter.exportCsv(currency)
                _exportStatus.value = ExportStatus.Ready(uri.toString())
            } catch (t: Throwable) {
                _exportStatus.value = ExportStatus.Error(t.message ?: "Export failed")
            }
        }
    }
```

with:

```kotlin
    fun exportCsv(currency: String, range: ExportDateRange? = null) {
        if (_exportStatus.value is ExportStatus.Running) return
        _exportStatus.value = ExportStatus.Running
        viewModelScope.launch {
            try {
                val uri = csvExporter.exportCsv(currency, range)
                _exportStatus.value = ExportStatus.Ready(uri.toString())
            } catch (t: Throwable) {
                _exportStatus.value = ExportStatus.Error(t.message ?: "Export failed")
            }
        }
    }
```

- [ ] **Step 3: Add the `range` parameter to `exportAllZip`**

Replace the existing `exportAllZip` function (currently `SettingsViewModel.kt:168-179`):

```kotlin
    fun exportAllZip() {
        if (_exportStatus.value is ExportStatus.Running) return
        _exportStatus.value = ExportStatus.Running
        viewModelScope.launch {
            try {
                val uri = csvExporter.exportAllCurrenciesZip()
                _exportStatus.value = ExportStatus.ZipReady(uri.toString())
            } catch (t: Throwable) {
                _exportStatus.value = ExportStatus.Error(t.message ?: "Export failed")
            }
        }
    }
```

with:

```kotlin
    fun exportAllZip(range: ExportDateRange? = null) {
        if (_exportStatus.value is ExportStatus.Running) return
        _exportStatus.value = ExportStatus.Running
        viewModelScope.launch {
            try {
                val uri = csvExporter.exportAllCurrenciesZip(range)
                _exportStatus.value = ExportStatus.ZipReady(uri.toString())
            } catch (t: Throwable) {
                _exportStatus.value = ExportStatus.Error(t.message ?: "Export failed")
            }
        }
    }
```

(The legacy `export()` at `SettingsViewModel.kt:140-151` is unchanged — still all-time.)

- [ ] **Step 4: Verify it compiles**

Run: `.\gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit checkpoint** (owner runs)

Suggested message:
```
Settings VM: pass optional export date range to CsvExporter
```
Files: `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt`

---

## Task 4: Date-range picker UI in `ExportCsvChooserSheet`

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add imports**

Add these to the import block in `SettingsScreen.kt` (the Material3 imports go alphabetically among the existing `androidx.compose.material3.*` lines; the kotlinx-datetime + export imports go after the existing `cy.txtracker.*` imports):

```kotlin
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import cy.txtracker.export.ExportDateRange
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
```

- [ ] **Step 2: Update the call site to pass the range through the callbacks**

Replace the existing chooser invocation (currently `SettingsScreen.kt:418-431`):

```kotlin
        if (showExportChooser) {
            ExportCsvChooserSheet(
                trackedCurrencies = trackedCurrencies,
                onExportCurrency = { currency ->
                    showExportChooser = false
                    viewModel.exportCsv(currency)
                },
                onExportAllZip = {
                    showExportChooser = false
                    viewModel.exportAllZip()
                },
                onDismiss = { showExportChooser = false },
            )
        }
```

with (callbacks now receive the selected range):

```kotlin
        if (showExportChooser) {
            ExportCsvChooserSheet(
                trackedCurrencies = trackedCurrencies,
                onExportCurrency = { currency, range ->
                    showExportChooser = false
                    viewModel.exportCsv(currency, range)
                },
                onExportAllZip = { range ->
                    showExportChooser = false
                    viewModel.exportAllZip(range)
                },
                onDismiss = { showExportChooser = false },
            )
        }
```

- [ ] **Step 3: Replace the `ExportCsvChooserSheet` composable with the range-aware version**

Replace the entire existing `ExportCsvChooserSheet` (currently `SettingsScreen.kt:540-581`, from the `/**` doc comment through its closing `}`):

```kotlin
/**
 * Bottom-sheet chooser for the CSV export action. Shows:
 *  - An optional "Date range" filter (blank = all time) applied to whichever export is tapped
 *  - "Export MYR" (always)
 *  - "Export <code>" for each tracked currency
 *  - "Export all currencies (zip)" when more than one currency is available
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportCsvChooserSheet(
    trackedCurrencies: List<TrackedCurrency>,
    onExportCurrency: (String, ExportDateRange?) -> Unit,
    onExportAllZip: (ExportDateRange?) -> Unit,
    onDismiss: () -> Unit,
) {
    var range by remember { mutableStateOf<ExportDateRange?>(null) }
    var showRangePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = "Export to CSV",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // Optional date-range filter. Blank = all time (today's default behavior).
            ListItem(
                headlineContent = { Text("Date range") },
                supportingContent = {
                    Text(range?.let { "${it.start} → ${it.end}" } ?: "All time")
                },
                trailingContent = {
                    if (range != null) {
                        TextButton(onClick = { range = null }) { Text("Clear") }
                    }
                },
                modifier = Modifier.fillMaxWidth().clickable { showRangePicker = true },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ListItem(
                headlineContent = { Text("Export MYR") },
                modifier = Modifier.fillMaxWidth().clickable { onExportCurrency("MYR", range) },
            )
            for (tc in trackedCurrencies) {
                ListItem(
                    headlineContent = { Text("Export ${tc.code}") },
                    modifier = Modifier.fillMaxWidth().clickable { onExportCurrency(tc.code, range) },
                )
            }
            if (trackedCurrencies.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ListItem(
                    headlineContent = { Text("Export all currencies (zip)") },
                    supportingContent = { Text("One CSV per currency in a single zip file.") },
                    modifier = Modifier.fillMaxWidth().clickable { onExportAllZip(range) },
                )
            }
        }
    }

    if (showRangePicker) {
        ExportRangePickerDialog(
            initial = range,
            onConfirm = {
                range = it
                showRangePicker = false
            },
            onDismiss = { showRangePicker = false },
        )
    }
}

/**
 * Material3 date-range picker dialog. Material reports selections as UTC-midnight epoch millis,
 * so we read the tapped calendar date via [TimeZone.UTC]; the exporter re-interprets those dates
 * in Malaysia time. If only a start is picked, the end defaults to the start (single-day export).
 * Confirming with no selection clears the filter (null = all time).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportRangePickerDialog(
    initial: ExportDateRange?,
    onConfirm: (ExportDateRange?) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.toUtcMidnightMillis(),
        initialSelectedEndDateMillis = initial?.end?.toUtcMidnightMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val start = state.selectedStartDateMillis?.toUtcLocalDate()
                if (start == null) {
                    onConfirm(null)
                } else {
                    val end = state.selectedEndDateMillis?.toUtcLocalDate() ?: start
                    onConfirm(ExportDateRange(start, end))
                }
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

/** Epoch millis from Material's UTC-based picker → the calendar date the user tapped. */
private fun Long.toUtcLocalDate(): LocalDate =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date

/** A calendar date → UTC-midnight epoch millis, to seed the picker's initial selection. */
private fun LocalDate.toUtcMidnightMillis(): Long =
    this.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
```

- [ ] **Step 4: Verify it compiles and assembles**

Run: `.\gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. (Compose UI rendering is not unit-tested in this project; instrumented/emulator tests are out of scope — see manual smoke in Task 5.)

- [ ] **Step 5: Commit checkpoint** (owner runs)

Suggested message:
```
Settings UI: optional date-range picker in CSV export chooser
```
Files: `app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt`

---

## Task 5: Full verification gate

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit-test suite**

Run: `.\gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `FilterByRangeTest` (8) and the existing `BuildCsvTest` all pass.

- [ ] **Step 2: Confirm a clean debug build**

Run: `.\gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke test (owner, on device/emulator at their discretion)**

Since the UI is not covered by automated tests here, verify by hand:
1. Settings → "Export to CSV" → the sheet shows a **Date range** row reading "All time".
2. Tap it → the Material range picker opens; pick a start and end → the row shows `2026-01-01 → 2026-01-31` with a **Clear** button.
3. Tap **Export MYR** → the shared CSV is named `transactions-MYR-2026-01-01_to_2026-01-31-<ts>.csv` and contains only rows whose Malaysia-local day is in range.
4. Reopen the sheet, tap **Clear** (or reopen — range resets to "All time"), tap **Export MYR** → all-time CSV named `transactions-MYR-<ts>.csv` (no range suffix), matching the original behavior.
5. With a range set and >1 currency, **Export all currencies (zip)** produces `transactions-all-2026-01-01_to_2026-01-31-<ts>.zip` containing one CSV per currency that has rows in range.

- [ ] **Step 4: Final commit checkpoint** (owner runs, if any verification-only changes were needed)

No code changes expected in this task. If steps surfaced a fix, commit it with a descriptive message.

---

## Self-review notes (already applied)

- **Spec coverage:** integration as optional filter (Task 4), Material range picker + Clear (Task 4), header-only file for empty single-currency export — emerges naturally because `exportCsv` always writes a file (Task 2); zip skips empty currencies (Task 2); in-memory filtering (Tasks 1–2); MYT-day semantics with UTC-picker conversion (Tasks 1 & 4); range in filename (Task 2); ephemeral range state in the sheet (Task 4); unit tests for bounds/boundaries/cross-midnight/null (Task 1). All spec sections map to a task.
- **Type consistency:** `ExportDateRange(start, end)` with `LocalDate` fields is defined once (Task 1) and used identically in Tasks 2–4. `exportCsv(currency, range)` / `exportAllCurrenciesZip(range)` / `exportAllZip(range)` signatures match across exporter, VM, and UI call site. Callback types `(String, ExportDateRange?)` and `(ExportDateRange?)` match between the composable definition and its call site.
- **No placeholders:** every code step shows complete code; every run step states the exact command and expected result.
