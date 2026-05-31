# Date-range CSV export — design

**Date:** 2026-05-31
**Status:** Approved, ready for implementation plan

## Problem

CSV export today exports **all transactions** for a chosen currency, with no way to
restrict to a date range. The export path is:

- UI: `ExportCsvChooserSheet` (`SettingsScreen.kt`) → buttons "Export MYR",
  "Export `<currency>`", "Export all currencies (zip)".
- VM: `SettingsViewModel.exportCsv(currency)` / `exportAllZip()`.
- Exporter: `CsvExporter.exportCsv(currency)` / `exportAllCurrenciesZip()`.
- Data: `TransactionRepository.getAllTransactionsOnceForCurrency(currency)` →
  `TransactionDao.getAllForCurrency` (`SELECT * ... WHERE currency = :currency`, no date clause).

A date-floor query already exists (`TransactionDao.getAllFrom(cutoffStart)`), but it is used
only by cloud-sync upload, not by export.

## Goal

Let the user optionally restrict a CSV export to a start→end date range. Leaving the range
blank preserves today's exact "export everything" behavior.

## Decisions (from brainstorming)

1. **Integration:** an *optional* date-range control on the existing chooser sheet. Blank =
   all-time (unchanged). A picked range applies to whichever export button is tapped
   (MYR / per-currency / all-currencies zip). Fully backward-compatible.
2. **Picker UI:** Material3 `DateRangePicker` (calendar, tap start then end) in a dialog, with
   a Clear action to reset to all-time.
3. **Empty range:** single-currency export always produces a file, even header-only. No special
   "nothing to export" handling for the direct per-currency export.
4. **Filtering location:** in-memory (Approach A). Reuse the existing
   `getAllTransactionsOnceForCurrency` query and filter the list in `CsvExporter`. Chosen over a
   new SQL query because at personal-expense-tracker scale the perf cost is negligible and it
   keeps the entire feature — bound math and filtering — as pure JVM logic that is unit-testable
   under `testDebugUnitTest` (instrumented/emulator tests are not run in this project).

## Date semantics (correctness-critical)

Two timezones meet in this feature:

- **Material3 `DateRangePicker` works in UTC.** Its `selectedStartDateMillis` /
  `selectedEndDateMillis` are UTC-midnight epoch millis. Convert each to the calendar date the
  user actually tapped with:
  `Instant.fromEpochMilliseconds(m).toLocalDateTime(TimeZone.UTC).date`.
- **Export interprets those dates in Malaysia time**, matching how `buildCsv` already groups
  rows into days via `cy.txtracker.domain.MalaysiaTimeZone`:
  - `start = startDate.atStartOfDayIn(MalaysiaTimeZone)`
  - `endExclusive = (endDate + 1 day).atStartOfDayIn(MalaysiaTimeZone)`
  - keep `occurredAt >= start && occurredAt < endExclusive`.
- **Net effect:** selecting Jan 1 → Mar 31 exports every transaction whose **Malaysia-local
  day** is in Jan 1–Mar 31 inclusive. MYT has no DST, so there are no spring/fall edge cases.
- If the user picks only a start date (no end), `end` defaults to `start` (single-day export).

## Components & changes

### a. New value type + pure helpers — `CsvExporter.kt` (pure-helpers section)

```kotlin
/** Inclusive start/end, interpreted as Malaysia-local calendar days. */
data class ExportDateRange(val start: LocalDate, val end: LocalDate)

/** range → [start-of-start-day, start-of-(end+1)-day) instant bounds in Malaysia time. Pure. */
fun malaysiaDateRangeBounds(range: ExportDateRange): Pair<Instant, Instant>

/** null range → list unchanged; else keep occurredAt in [start, endExclusive). Pure. */
fun filterByRange(transactions: List<Transaction>, range: ExportDateRange?): List<Transaction>
```

Both helpers are pure and unit-testable, with no Android/Room dependency.

### b. `CsvExporter`

- `exportCsv(currency: String, range: ExportDateRange? = null): Uri`
  → `getAllTransactionsOnceForCurrency(currency)` → `filterByRange(_, range)` → `writeCsv`.
- `exportAllCurrenciesZip(range: ExportDateRange? = null): Uri`
  → same filter applied per currency; **retains the existing "skip a currency with zero rows"
  behavior** (now: zero rows *after* filtering).
- Filename encodes the range when present, e.g.
  `transactions-MYR-2026-01-01_to_2026-03-31-<ts>.csv`. All-time naming is unchanged.
- Legacy `export()` stays all-time, untouched.

### c. `SettingsViewModel`

- `exportCsv(currency: String, range: ExportDateRange?)` and `exportAllZip(range: ExportDateRange?)`
  thread the range to the exporter. The VM holds no picker state.

### d. UI — `SettingsScreen.kt` / `ExportCsvChooserSheet`

- A "Date range" row at the top of the sheet shows `All time` by default, or
  `2026-01-01 → 2026-03-31` once set, with a Clear (✕) affordance.
- Tapping the row opens a `DatePickerDialog` hosting `DateRangePicker`
  (`rememberDateRangePickerState()`). On confirm, convert the selected UTC-midnight millis to
  `LocalDate`s (see Date semantics) and build an `ExportDateRange`.
- Range state lives in the sheet: `remember { mutableStateOf<ExportDateRange?>(null) }`.
- Export callbacks gain the range:
  `onExportCurrency: (String, ExportDateRange?) -> Unit`, `onExportAllZip: (ExportDateRange?) -> Unit`.

## Behavioral defaults (confirmed)

1. **Zip skips empty currencies** even with a range applied (consistent with current behavior);
   the direct per-currency export still yields a header-only file when empty.
2. **Range is ephemeral** — it resets to All time each time the sheet is opened (the sheet
   closes on export). The "same range across multiple currencies" use case is served by the
   all-currencies zip button.
3. **Range encoded in filename** for self-describing exports.

## Testing

New JVM unit test: `app/src/test/java/cy/txtracker/export/FilterByRangeTest.kt`
(runs under `testDebugUnitTest`; no emulator):

- **Bound math / boundaries:** a tx at `endDate 23:59 MYT` is included; a tx at
  `(endDate + 1) 00:00 MYT` is excluded; a tx just before `start` is excluded.
- **Cross-midnight (MYT vs UTC):** a tx stored as `Jan 1 23:30 MYT` (= `Jan 1 15:30 UTC`) is
  included in a range ending Jan 1 — proving filtering is by Malaysia day, not UTC day.
- **Null range** returns the input list unchanged.

`buildCsv` is unchanged, so `BuildCsvTest` remains valid. UI and VM wiring are verified by the
compile gate (`compileDebugKotlin` / `assembleDebug`).

## Out of scope

- No new SQL query / DAO method / schema change (Approach B was rejected).
- No persistence of the selected range across app restarts or sheet re-opens.
- No quick-preset chips (This month / Last month / etc.); the calendar range picker only.
