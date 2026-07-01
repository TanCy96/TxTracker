# Per-trip CSV export (MYR-only Settings export) — design

**Date:** 2026-07-01
**Status:** Approved for planning

## Problem

The Settings "Export CSV" chooser lists **every tracked currency** and offers an "export all
currencies (zip)". Because a currency becomes tracked the moment you open a trip in it and is
never retired, both the chooser list and the zip grow without bound — every trip you've ever
taken shows up in every export. The user wants exports to stop surfacing all historical
currencies.

## Goal

Make the Settings CSV export your **MYR home ledger only**, and move foreign-currency export
to a **per-trip action on the Foreign tab**, so you export the trip you're looking at and
never scroll past currencies from old trips.

## Requirements (agreed)

1. **Settings → Export CSV = MYR-only**, with the existing date-range picker. No per-currency
   list, no "all currencies" option.
2. **Foreign tab → "Export this trip"**: exports one CSV scoped to the current trip's currency
   and its whole window `[startAt, endAt]` (open-ended trips → `[startAt, today]`). No extra
   date picker — the trip is the scope.
3. **Remove the multi-currency zip path** entirely (dead once its UI is gone). Full-history
   safekeeping remains in the JSON backup, which already captures all currencies/trips.
4. **Parked (non-trip) foreign rows** — those in Home's "Currency review", not inside any trip
   — are out of scope for CSV export (ignored). They are meant to be pulled into a trip first.

## Chosen approach (Option A)

Reuse the existing single-currency `CsvExporter.exportCsv(currency, range)` for both paths:
Settings passes `"MYR"`; the Foreign tab passes the trip's currency + its window as an
`ExportDateRange`. Delete the now-unused all-currencies zip machinery. This is the smallest
change, adds no new CSV format, and matches the trip model built in the trip-scoped-categories
feature.

Rejected: a currency multi-select in the chooser, or an "archive currency" lifecycle — both
add machinery to keep a Settings-side currency list that this design removes outright.

## Changes

### 1. `CsvExporter` (`app/src/main/java/cy/txtracker/export/CsvExporter.kt`)
- **Remove** `exportAllCurrenciesZip(range)`, the `zipFileName(range)` helper, and the
  `java.util.zip` usage. `exportCsv(currency, range)`, `buildCsv`, `writeCsv`, `filterByRange`,
  `ExportDateRange`, and the date-bounds helpers are unchanged.
- **Add** a pure helper (top-level, unit-testable):
  ```kotlin
  fun tripExportRange(startAt: Instant, endAt: Instant?, today: LocalDate): ExportDateRange
  ```
  Returns `ExportDateRange(startAt.toLocalDateTime(MalaysiaTimeZone).date,
  (endAt?.toLocalDateTime(MalaysiaTimeZone)?.date ?: today))`. If a closed trip's `endAt` maps
  to a date before `startAt`'s date (shouldn't happen), clamp `end = start`.

### 2. Settings export UI
- `SettingsViewModel`: **remove** `exportAllZip(...)` and the `ExportStatus.ZipReady` variant.
  Keep `exportCsv(currency, range)` (already exists) and `ExportStatus.Ready`.
- `SettingsScreen`: the export row now opens a **date-range-only** chooser (all-time vs a
  range) and calls `viewModel.exportCsv("MYR", range)`. Remove the per-currency buttons, the
  "export all zip" button, the `trackedCurrencies` input to the chooser, and the `ZipReady`
  share branch. Rename `ExportCsvChooserSheet` → a simpler `ExportRangeChooserSheet` (or strip
  it in place) that no longer takes currencies.

### 3. Foreign tab export
- `ForeignViewModel`: inject `CsvExporter`. Add:
  ```kotlin
  fun exportCurrentTrip()   // uses the loaded trip's currency + tripExportRange(...)
  ```
  Expose a one-shot export event mirroring Settings: a `StateFlow<ExportEvent?>` with
  `Ready(uri: String)` / `Error(message)` (or reuse the export status shape). `today` is
  `Clock.System.now().toLocalDateTime(MalaysiaTimeZone).date`.
- `ForeignRoute`/`ForeignScreen`: add an **"Export this trip"** action for the current trip
  (in the same top-bar/overflow group as the "Manage categories" action added in the
  trip-scoped-categories feature), enabled only when a trip is loaded. On a `Ready(uri)` event,
  fire the share.
- **Share helper:** extract the `ACTION_SEND` CSV-share logic currently inline in
  `SettingsScreen` into a small reusable function (e.g. `ui/common/shareCsv(context, uri)`), and
  call it from both `SettingsScreen` and `ForeignScreen` so the intent construction isn't
  duplicated.

## Testing

**JVM unit tests (primary gate — no device):**
- `tripExportRange`: closed trip → `[startAt.date, endAt.date]`; open-ended trip (`endAt = null`)
  → `[startAt.date, today]`; a trip whose window is a single day → `start == end`; MYT-local
  date mapping is correct at day boundaries.
- Existing `BuildCsvTest` (buildCsv) and range-filter tests remain green (unchanged behavior).

**Compile-gated / manual (per no-device-test policy):**
- Compose UI wiring (Settings chooser simplification, Foreign export action, share helper)
  verified by `compileDebugKotlin`.
- Manual: Settings export produces MYR only; Foreign "Export this trip" produces a CSV
  windowed to that trip; sharing works from both.

## Out of scope (YAGNI)
- Bulk "all currencies" export (use JSON backup).
- Currency multi-select or an archive/active lifecycle for currencies.
- A separate date-range sub-picker on the trip export.
- Exporting parked (non-trip) foreign rows.

## Open questions for spec review
- Confirm **removing** the zip path entirely (vs. keeping it dormant behind a hidden toggle).
- Confirm extracting a shared `shareCsv` helper (vs. leaving Settings' share inline and adding a
  small parallel one on the Foreign screen).
