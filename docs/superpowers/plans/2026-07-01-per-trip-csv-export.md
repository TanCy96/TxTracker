# Per-trip CSV Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Settings "Export CSV" MYR-only, add a per-trip CSV export on the Foreign tab (whole trip window), and delete the all-currencies zip path.

**Architecture:** Reuse the existing single-currency `CsvExporter.exportCsv(currency, range)` for both paths — Settings passes `"MYR"`; the Foreign tab passes the current trip's currency + its window as an `ExportDateRange` computed by a new pure `tripExportRange(...)`. A shared `shareCsv(context, uri)` helper fires the `ACTION_SEND` intent from both screens. The now-unused zip machinery is removed.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, kotlinx-datetime, JUnit4 + Truth (JVM unit tests).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-01-per-trip-csv-export-design.md` (authoritative).
- Settings CSV export = MYR-only (with date-range picker). No per-currency list, no all-currencies zip.
- Foreign per-trip export = the trip's whole window `[startAt, endAt]`; open-ended trips (`endAt == null`) run through **today** (Malaysia local date). No separate date picker on trip export.
- Dates are Malaysia-local (`cy.txtracker.domain.MalaysiaTimeZone`); the app is single-currency-per-file MYR-style (amounts unlabeled) — unchanged.
- **Testing limits:** JVM unit tests (`./gradlew testDebugUnitTest`) + compile gates (`./gradlew compileDebugKotlin`) only. NEVER device/instrumented/connected tests or an emulator.
- Work on the CURRENT branch (`main`); never create branches/worktrees. Stage only the files each task changes (never `git add -A` — unrelated untracked files exist).
- Every commit message ends with: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Environment: Windows; Bash tool is Git Bash. Do not `cd`; use `./gradlew`.

---

## File Structure

- `app/src/main/java/cy/txtracker/export/CsvExporter.kt` — add `tripExportRange(...)`; remove `exportAllCurrenciesZip`, `zipFileName`, zip imports.
- `app/src/main/java/cy/txtracker/ui/common/ShareCsv.kt` — **new**: `shareCsv(context, uri)` (+ the existing zip/json share can stay where they are; this helper covers CSV).
- `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt` — remove `exportAllZip` + `ExportStatus.ZipReady`.
- `app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt` — MYR-only chooser; use `shareCsv`; drop the `ZipReady` branch and the all-zip UI.
- `app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt` — inject `CsvExporter`; `exportCurrentTrip()` + export event.
- `app/src/main/java/cy/txtracker/ui/foreign/ForeignRoute.kt` — "Export this trip" action + share via `shareCsv`.
- Tests: `app/src/test/java/cy/txtracker/export/TripExportRangeTest.kt` (**new**).

---

## Task 1: `tripExportRange` pure helper

**Files:**
- Modify: `app/src/main/java/cy/txtracker/export/CsvExporter.kt` (add a top-level fun near `malaysiaDateRangeBounds`)
- Test: `app/src/test/java/cy/txtracker/export/TripExportRangeTest.kt` (**new**)

**Interfaces:**
- Produces: `fun tripExportRange(startAt: Instant, endAt: Instant?, today: LocalDate): ExportDateRange`

- [ ] **Step 1: Write the failing test**

```kotlin
package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test

class TripExportRangeTest {
    // 2026-07-01T02:00:00Z is 2026-07-01 10:00 in Malaysia (UTC+8).
    private val start = Instant.parse("2026-07-01T02:00:00Z")

    @Test
    fun closed_trip_uses_start_and_end_dates() {
        val end = Instant.parse("2026-07-10T02:00:00Z")
        val r = tripExportRange(start, end, today = LocalDate(2026, 12, 31))
        assertThat(r.start).isEqualTo(LocalDate(2026, 7, 1))
        assertThat(r.end).isEqualTo(LocalDate(2026, 7, 10))
    }

    @Test
    fun open_ended_trip_uses_today_as_end() {
        val r = tripExportRange(start, endAt = null, today = LocalDate(2026, 7, 5))
        assertThat(r.start).isEqualTo(LocalDate(2026, 7, 1))
        assertThat(r.end).isEqualTo(LocalDate(2026, 7, 5))
    }

    @Test
    fun single_day_trip_start_equals_end() {
        val end = Instant.parse("2026-07-01T14:00:00Z") // same MYT day as start
        val r = tripExportRange(start, end, today = LocalDate(2026, 12, 31))
        assertThat(r.start).isEqualTo(LocalDate(2026, 7, 1))
        assertThat(r.end).isEqualTo(LocalDate(2026, 7, 1))
    }

    @Test
    fun end_before_start_is_clamped_to_start() {
        val end = Instant.parse("2026-06-20T02:00:00Z")
        val r = tripExportRange(start, end, today = LocalDate(2026, 12, 31))
        assertThat(r.start).isEqualTo(LocalDate(2026, 7, 1))
        assertThat(r.end).isEqualTo(LocalDate(2026, 7, 1))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.export.TripExportRangeTest" --console=plain`
Expected: FAIL — `tripExportRange` unresolved.

- [ ] **Step 3: Implement the helper**

Add to `CsvExporter.kt` (top-level, alongside `malaysiaDateRangeBounds`; `MalaysiaTimeZone` is already imported):

```kotlin
/**
 * The [ExportDateRange] covering a trip's whole window in Malaysia-local calendar days.
 * Open-ended trips (endAt == null) run through [today]. If the computed end precedes the
 * start (malformed window), it is clamped to the start so the range stays valid.
 */
fun tripExportRange(startAt: Instant, endAt: Instant?, today: LocalDate): ExportDateRange {
    val startDate = startAt.toLocalDateTime(MalaysiaTimeZone).date
    val endDate = endAt?.toLocalDateTime(MalaysiaTimeZone)?.date ?: today
    return ExportDateRange(startDate, if (endDate < startDate) startDate else endDate)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.export.TripExportRangeTest" --console=plain`
Expected: PASS (4/4).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/export/CsvExporter.kt \
        app/src/test/java/cy/txtracker/export/TripExportRangeTest.kt
git commit -m "Export: add tripExportRange helper for per-trip CSV window"
```
(append the Co-Authored-By trailer)

---

## Task 2: Shared `shareCsv` helper

**Files:**
- Create: `app/src/main/java/cy/txtracker/ui/common/ShareCsv.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt` (the `ExportStatus.Ready` branch → call `shareCsv`)

**Interfaces:**
- Produces: `fun shareCsv(context: android.content.Context, uri: android.net.Uri)` — fires `ACTION_SEND` with `type = "text/csv"`, `EXTRA_STREAM = uri`, `FLAG_GRANT_READ_URI_PERMISSION`, wrapped in `Intent.createChooser(intent, "Export transactions")`, `context.startActivity(...)`.

This task has no unit test (Android `Intent`/`Context` UI glue); it is a behavior-preserving refactor verified by `compileDebugKotlin`. The current inline `Ready` branch in `SettingsScreen` (in `LaunchedEffect(exportStatus)`) already does exactly this — extract it verbatim.

- [ ] **Step 1: Create the helper**

```kotlin
package cy.txtracker.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Fires an ACTION_SEND chooser to share a CSV file [uri]. Shared by Settings + Foreign export. */
fun shareCsv(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export transactions"))
}
```

- [ ] **Step 2: Use it in SettingsScreen's `Ready` branch**

In `SettingsScreen.kt`, `LaunchedEffect(exportStatus)`, replace the `is SettingsViewModel.ExportStatus.Ready ->` block's inline intent construction with:

```kotlin
is SettingsViewModel.ExportStatus.Ready -> {
    shareCsv(context, Uri.parse(s.uri))
    viewModel.consumeStatus()
}
```
Add `import cy.txtracker.ui.common.shareCsv`. (Leave the `ZipReady` branch for now — Task 3 removes it.)

- [ ] **Step 3: Compile-gate**

Run: `./gradlew compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/common/ShareCsv.kt \
        app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt
git commit -m "Export: extract shared shareCsv() intent helper"
```

---

## Task 3: Settings export MYR-only; remove the all-currencies zip path

**Files:**
- Modify: `app/src/main/java/cy/txtracker/export/CsvExporter.kt` (remove `exportAllCurrenciesZip`, `zipFileName`, `java.util.zip` imports)
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt` (remove `exportAllZip`, `ExportStatus.ZipReady`)
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt` (MYR-only chooser; remove `ZipReady` branch + all-zip UI)

**Interfaces:**
- Consumes: `exportCsv("MYR", range)` (unchanged), `SettingsViewModel.ExportStatus.Ready/Running/Error/Idle`.
- Removes: `CsvExporter.exportAllCurrenciesZip`, `SettingsViewModel.exportAllZip`, `ExportStatus.ZipReady`.

This is a removal + UI-simplification task (no new logic → no new unit test; existing `BuildCsvTest`/`FilterByRangeTest` must stay green, and `compileDebugKotlin` + full `testDebugUnitTest` gate it).

- [ ] **Step 1: Remove zip from `CsvExporter`**

Delete the `exportAllCurrenciesZip(...)` function (currently ~lines 71–97) and the `private fun zipFileName(...)`. Remove any now-unused `java.util.zip` references (they are used only inside `exportAllCurrenciesZip`). Keep `exportCsv`, `export()`, `csvFileName`, `exportDir`, `uriFor`, and all pure helpers.

- [ ] **Step 2: Remove `exportAllZip` + `ZipReady` from `SettingsViewModel`**

Delete `fun exportAllZip(range)`. In the `ExportStatus` sealed type, delete the `ZipReady` variant. Keep `Idle`, `Running`, `Ready`, `Error`.

- [ ] **Step 3: Simplify the Settings export UI**

In `SettingsScreen.kt`:
- Delete the `is SettingsViewModel.ExportStatus.ZipReady ->` branch in `LaunchedEffect(exportStatus)`.
- Change the `showExportChooser` block so the sheet no longer takes `trackedCurrencies` or `onExportAllZip`. Rename `ExportCsvChooserSheet` → `ExportRangeChooserSheet(onExport: (ExportDateRange?) -> Unit, onDismiss: () -> Unit)` and strip it to: the existing date-range selector (all-time vs a start/end range) plus a single **"Export"** button that calls `onExport(range)`. Remove the per-currency buttons and the "export all" button.
- Wire it:
```kotlin
if (showExportChooser) {
    ExportRangeChooserSheet(
        onExport = { range ->
            showExportChooser = false
            viewModel.exportCsv("MYR", range)
        },
        onDismiss = { showExportChooser = false },
    )
}
```
- Remove the now-unused `trackedCurrencies` collection if nothing else on the screen uses it (grep `trackedCurrencies` in `SettingsScreen.kt` first; the Foreign-currencies settings row may use it elsewhere — if so, leave that usage).

- [ ] **Step 4: Gate**

Run: `./gradlew testDebugUnitTest --console=plain` then `./gradlew compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL; existing export tests still pass. Fix any dangling reference the removals surface (there should be none beyond the three files).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/export/CsvExporter.kt \
        app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt \
        app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt
git commit -m "Export: Settings CSV is MYR-only; remove all-currencies zip"
```

---

## Task 4: Foreign tab "Export this trip"

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/foreign/ForeignRoute.kt`
- Test: `app/src/test/java/cy/txtracker/ui/foreign/ForeignExportTest.kt` (**new**)

**Interfaces:**
- Consumes: `tripExportRange(startAt, endAt, today)` (Task 1); `CsvExporter.exportCsv(currency, range)`; `shareCsv(context, uri)` (Task 2).
- Produces: `ForeignViewModel.exportCurrentTrip()`, and `ForeignViewModel.exportEvent: StateFlow<ForeignExport?>` where `sealed interface ForeignExport { data class Ready(val uri: String); data class Error(val message: String) }`, plus `fun consumeExportEvent()`.

- [ ] **Step 1: Write the failing test**

The picker-scope tests in `app/src/test/java/cy/txtracker/ui/foreign/ForeignCategoriesTest.kt` show the ForeignViewModel mockk + Turbine setup — mirror it. Write `ForeignExportTest`:
- Stub one loaded trip (`TripWindow(id=7, currency="USD", startAt=t0, endAt=null, createdAt=t0)`) and the flows the VM needs (as in ForeignCategoriesTest).
- Mock `csvExporter.exportCsv("USD", any())` to return a fake `Uri` (mockk).
- Call `viewModel.exportCurrentTrip()`; assert `exportEvent` emits `ForeignExport.Ready(<uri string>)` and that `csvExporter.exportCsv("USD", <range with start = t0's MYT date>)` was called (`coVerify`).

Write it concretely against ForeignCategoriesTest's patterns (inject a mocked `CsvExporter` into the VM constructor).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.ui.foreign.ForeignExportTest" --console=plain`
Expected: FAIL — `exportCurrentTrip`/`exportEvent`/`CsvExporter` ctor param unresolved.

- [ ] **Step 3: Implement in `ForeignViewModel`**

- Add `private val csvExporter: CsvExporter` to the constructor (Hilt provides it — it's `@Singleton @Inject`; no module change needed).
- Add:
```kotlin
sealed interface ForeignExport {
    data class Ready(val uri: String) : ForeignExport
    data class Error(val message: String) : ForeignExport
}

private val _exportEvent = MutableStateFlow<ForeignExport?>(null)
val exportEvent: StateFlow<ForeignExport?> = _exportEvent.asStateFlow()
fun consumeExportEvent() { _exportEvent.value = null }

fun exportCurrentTrip() {
    val loaded = state.value as? ForeignUiState.Loaded ?: return
    val trip = loaded.trip
    val today = Clock.System.now().toLocalDateTime(MalaysiaTimeZone).date
    viewModelScope.launch {
        _exportEvent.value = try {
            val range = tripExportRange(trip.startAt, trip.endAt, today)
            val uri = csvExporter.exportCsv(trip.currency, range)
            ForeignExport.Ready(uri.toString())
        } catch (t: Throwable) {
            ForeignExport.Error(t.message ?: "Export failed")
        }
    }
}
```
(`TripDescriptor` already carries `currency`, `startAt`, `endAt` — confirm the field names in `ForeignViewModel`'s `TripDescriptor` and use them. Import `Clock`, `tripExportRange`, `ForeignUiState`, `MalaysiaTimeZone` as needed.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "cy.txtracker.ui.foreign.ForeignExportTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Wire the Foreign UI**

In `ForeignRoute.kt`: add an **"Export this trip"** action alongside the existing "Manage categories" action for the current trip (same top-bar/overflow group, enabled only when `state is ForeignUiState.Loaded`), calling `viewModel.exportCurrentTrip()`. Collect `exportEvent` and, on `Ready`, call `shareCsv(context, Uri.parse(it.uri))` then `viewModel.consumeExportEvent()`; on `Error`, surface it the way the screen already surfaces messages (or a `Toast`). Mirror the `LaunchedEffect(exportStatus)` pattern from `SettingsScreen`. Add imports: `cy.txtracker.ui.common.shareCsv`, `android.net.Uri`, and a `LocalContext.current`.

- [ ] **Step 6: Gate the UI**

Run: `./gradlew testDebugUnitTest --console=plain` then `./gradlew compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt \
        app/src/main/java/cy/txtracker/ui/foreign/ForeignRoute.kt \
        app/src/test/java/cy/txtracker/ui/foreign/ForeignExportTest.kt
git commit -m "Foreign: add Export this trip (windowed CSV) + share"
```

---

## Task 5: Final gate

- [ ] **Step 1: Full unit suite + compile**

Run: `./gradlew testDebugUnitTest compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 2: Manual checklist for the user** (per no-device-test policy — do not run):
  - Settings → Export to CSV offers a date range and produces an MYR-only CSV (no currency list, no zip).
  - Foreign tab on a trip → "Export this trip" produces a CSV windowed to that trip's currency + dates; open-ended trip runs through today.
  - Sharing works from both Settings and the Foreign tab.

---

## Self-review notes (author)

- **Spec coverage:** Settings MYR-only (Task 3), per-trip export whole window (Tasks 1+4), remove zip (Task 3), shared share helper (Task 2), parked rows ignored (implicit — trip export windows by the trip, and there is no all-currency path). All spec sections map to a task.
- **Type consistency:** `tripExportRange(startAt: Instant, endAt: Instant?, today: LocalDate): ExportDateRange`; `ForeignExport.Ready(uri: String)`; `shareCsv(context, uri)` — names used identically across tasks.
- **Removals are safe:** `exportAllCurrenciesZip` is called only by `SettingsViewModel.exportAllZip`; `ZipReady` only by that VM method + the one `SettingsScreen` branch — all removed together in Task 3.
