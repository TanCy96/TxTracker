# Reimbursed-by-others Share — Design

**Date:** 2026-06-01
**Target branch:** `main` (DB schema v11 → v12, backup v8 → v9).
**Status:** Designed; not yet implemented.

A generic, per-transaction way to reduce a transaction's **effective** amount when other
people return their part of what you paid. You pay the full amount up front; others pay you
back; the returned portion stops counting as *your* spending. There is **no pool and no
deposit ledger** — this is deliberately simpler than SL Debit.

---

## 0. Relationship to SL Debit (READ FIRST)

This feature is **independent of SL Debit** and is built on `main`. SL Debit lives only on
`feature/share-debit` and is not referenced here.

The two mechanisms are designed to **coexist on the branch without being coupled in code**:

- On `main`, a transaction's net is `amountMinor − COALESCE(reimbursedMinor, 0)`.
- On `feature/share-debit`, the net is `amountMinor − COALESCE(slShareMinor, 0)`.
- When `main` is merged **into** `feature/share-debit` (the one-way flow mandated by the SL
  Debit branch policy), the two netting expressions conflict. The conflict is resolved the
  way that policy already requires — *keep SL Debit intact **and** take the main change* —
  producing `amountMinor − COALESCE(slShareMinor, 0) − COALESCE(reimbursedMinor, 0)`. The
  "a transaction can be both SL-shared and reimbursed" behavior emerges there for free.
- The v11→v12 migration and backup v8→v9 bump in this spec collide with SL Debit's own
  v12 / v9. **Re-sequencing them is the branch's documented merge burden** (SL Debit spec
  §0 and §9), not main's concern. On main these are simply the next sequential versions.

The **combined-ceiling validation** (`slShareMinor + reimbursedMinor ≤ amountMinor`) is
branch-only logic added during merge resolution. On `main`, validation is just
`0 < reimbursedMinor ≤ amountMinor` — there is no `slShareMinor` to combine with.

---

## 1. Purpose

- Mark a spending transaction as **partially reimbursed by others** (e.g. you paid a RM100
  dinner, a friend returned RM50 → the transaction counts as RM50 of *your* spending).
- The original `amountMinor` is **never mutated**; the reimbursed portion is stored
  separately and subtracted only when computing what you spent.
- Works for **any currency** (you might split a foreign-currency bill), unlike SL Debit
  which is MYR-only.

### Key design decision

Mirror the SL Debit "Option A": the reduction is **derived at aggregation/display/export
time** from a single nullable column. No negative rows, no second source of truth, no pool.

---

## 2. Data model

Schema migration **v11 → v12**.

### 2.1 `Transaction.reimbursedMinor: Long?` (new nullable column)

```kotlin
/**
 * The portion of this transaction that others have reimbursed, in minor units.
 * null = not reimbursed. The original amountMinor is never reduced.
 * Invariant (enforced by repository/UI, not the schema): 0 < reimbursedMinor <= amountMinor.
 * Only ever set on direction = OUT rows. Any currency. Independent of SL Debit's slShareMinor.
 */
val reimbursedMinor: Long? = null
```

Migration: `ALTER TABLE transactions ADD COLUMN reimbursedMinor INTEGER DEFAULT NULL`.
No backfill. Register `MIGRATION_11_12` in `DatabaseModule.addMigrations(...)` and bump
`TxDatabase version = 12`. Export the new schema JSON (`app/schemas/.../12.json`).

### 2.2 Domain helpers (`domain/Share.kt`, new file)

JVM-pure, no Android/Room deps, directly unit-testable:

```kotlin
/** A reimbursed amount is valid when it is in (0, amountMinor] — i.e. 1..amountMinor. */
fun isValidReimbursedMinor(reimbursedMinor: Long, amountMinor: Long): Boolean =
    reimbursedMinor in 1L..amountMinor
```

(The branch will add a combined-ceiling overload during merge resolution; main does not
need it.)

---

## 3. Aggregation & totals — net out the reimbursed portion everywhere a net is computed

### 3.1 `TransactionDao` (Home totals)

```sql
-- observeCategoryTotalsBetween
SELECT categoryId, SUM(amountMinor - COALESCE(reimbursedMinor, 0)) AS totalMinor ...
-- observeTotalBetween
SELECT COALESCE(SUM(amountMinor - COALESCE(reimbursedMinor, 0)), 0) ...
```

(Scope unchanged: still `direction = 'OUT' AND currency = 'MYR'`.) Headline total and the
per-category breakdown both show the net, staying mutually consistent.

### 3.2 `InsightsAggregator.chartAmountMinor` (all charts, incl. per-currency/foreign)

```kotlin
internal fun Transaction.chartAmountMinor(): Long = amountMinor - (reimbursedMinor ?: 0L)
```

This single merge-point nets **every** chart sum, so foreign-currency reimbursements are
handled without further changes.

### 3.4 Other spend-total surfaces

Two further spend totals must net the share for consistency (found during final review):

- `notify/SummaryWorker` — the daily/weekly notification's headline total and top-by-category
  sums (its budget line already uses the netted `observeTotalBetween`).
- `ui/home/HomeScreen` `DayHeader` — the per-day group-header total (shared by the Foreign
  tab), so it matches the netted per-row amounts beneath it.

### 3.3 New DAO write

```kotlin
@Query("UPDATE transactions SET reimbursedMinor = :reimbursedMinor WHERE id = :id")
suspend fun updateReimbursed(id: Long, reimbursedMinor: Long?)
```

Plus a `TransactionRepository` wrapper following the existing `updateFundingSource` pattern.

---

## 4. Per-transaction UI

### 4.1 "Reimbursed by others" section (Edit sheet + Add Manual sheet)

Mirrors the structure of an editable amount section but with these differences from the
SL Debit share block:

- **Shown for any currency** (not gated to MYR).
- **No percent prefill.** Toggle on → empty amount field → user types the returned amount.
- Validated with `isValidReimbursedMinor(parsed, tx.amountMinor)`. Toggling off clears it
  to `null`.
- The amount field uses the **currency-aware formatter / parser** for the row's currency
  (not a hardcoded `RM`), since foreign rows are eligible.
- Edit sheet: new `onReimbursedChange: (Long?) -> Unit` callback →
  `EditTransactionViewModel.setReimbursed(id, reimbursedMinor)` → repository → `updateReimbursed`.
- Add Manual sheet: a `reimbursedText` field in `AddManualUiState`; parsed and passed to
  the inserted `Transaction.reimbursedMinor` at save time (only when `> 0` and valid).

### 4.2 Row display (`RowAmount` in `HomeScreen`, and the Foreign-tab row renderer)

When `reimbursedMinor != null`, switch to the three-number layout (same as SL Debit's
shared-row style):

- **Net** `amountFormatter(amountMinor − reimbursedMinor)` — emphasized (`SemiBold`).
- **Original** `amountFormatter(amountMinor)` — `onSurfaceVariant`, strikethrough.
- **Returned** `−${amountFormatter(reimbursedMinor)} Reimbursed` — new accent color
  (§4.3), to read distinctly from SL Debit's green.

Non-reimbursed rows render exactly as today. Apply the same net rendering to the **Foreign
tab** transaction rows (mirror `RowAmount`), since foreign rows can be reimbursed.

### 4.3 Theme color (`ui/theme/Color.kt`)

```kotlin
/** Accent for a reimbursed-by-others portion on a transaction row ("money coming back",
 *  distinct from SL Debit green). */
val ReimbursedAccent = Color(0xFF1565C0) // blue/teal — reads as different from SL green
```

(Wire into `Theme.kt` if the project surfaces semantic colors there.)

---

## 5. CSV export — inline subtraction only

`CsvExporter` / `buildAmountCell`. **No new column** and **no Source-label change** —
reimbursement is not a funding bucket and has no pool to track.

The category cell must show the per-transaction subtraction so the net is correct:

```
=100.00-50.00          # single reimbursed tx in the category that day
=100.00-50.00+30.00    # plus a non-reimbursed RM30 tx in the same category/day
```

Implementation: widen `buildAmountCell` from `List<Long>` (flat amounts) to a list of
per-transaction terms (e.g. `List<Pair<Long, Long?>>` of `amount` + `reimbursed`, or a
small term type), emitting `amount` or `amount-reimbursed` per transaction. Update the two
call sites (the per-category cell at the day's category filter, and any day-total cell) to
pass reimbursed alongside the amount. The cell still evaluates to the net.

---

## 6. Backup / cloud sync

Backup **v8 → v9** (`Backup.CURRENT_VERSION = 9`):

- `BackupTransaction` gains `reimbursedMinor: Long? = null`.
- `BackupExporter` serializes it; `BackupImporter` restores it.
- Import stays additive and back-compatible: older backups (v8 and earlier) parse with the
  new field defaulting to `null`. No new tables.

---

## 7. Testing (compile + `testDebugUnitTest` only; no emulator)

Unit:
- `isValidReimbursedMinor` boundary cases (`0` invalid, `1`, `amount`, `amount+1` invalid).
- Net category-total & headline SQL with a reimbursed row (DAO test).
- `chartAmountMinor` nets reimbursement, including a **foreign-currency** row.
- `buildCsv`: chained subtraction cell (`=100.00-50.00`, mixed with non-reimbursed terms);
  confirm no new column / no Source-label change.

Migration / round-trip (unit-level, matching existing migration-test style):
- v11 → v12 migration (column add, existing rows `reimbursedMinor = NULL`).
- Backup v8 → v9 round-trip and v8-import-into-v9 back-compat.

---

## 8. Touch-point summary (files to change)

- `data/Entities.kt` — `Transaction.reimbursedMinor`.
- `data/TxDatabase.kt` — `version = 12`; `app/schemas/.../12.json`.
- `di/DatabaseModule.kt` — `MIGRATION_11_12` + registration.
- `data/TransactionDao.kt` — net SQL ×2 + `updateReimbursed`.
- `data/TransactionRepository.kt` — `updateReimbursed` wrapper.
- `domain/Share.kt` (new) — `isValidReimbursedMinor`.
- `ui/insights/InsightsAggregator.kt` — `chartAmountMinor` nets reimbursed.
- `ui/edit/EditTransactionSheet.kt` + `EditTransactionViewModel.kt` — reimbursed section + `setReimbursed`.
- `ui/manual/AddManualSheet.kt` + `AddManualViewModel.kt` (+ `AddManualUiState`) — reimbursed field at insert.
- `ui/home/HomeScreen.kt` — `RowAmount` net display **and** the `DayHeader` per-day total netting.
- `ui/foreign/*` — net row display + trip totals are inherited (Foreign reuses `TransactionRow`/`DayHeader`).
- `notify/SummaryWorker.kt` — net the notification headline + top-by-category totals.
- `ui/theme/Color.kt` (+ `Theme.kt`) — `ReimbursedAccent`.
- `export/CsvExporter.kt` — `buildAmountCell` term widening + call sites.
- `export/Backup.kt` (`CURRENT_VERSION = 9`, `BackupTransaction`), `BackupExporter.kt`, `BackupImporter`.

---

## 9. Explicitly out of scope (YAGNI)

- Tracking *who* reimbursed, or any note/label on the reimbursement (amount only).
- Split-evenly / percent helpers (free-typed amount only).
- Reimbursing inflows (OUT rows only).
- Any pool, ledger, deposit, or balance surface for reimbursements.
- A dedicated CSV "Reimbursed" column or Source-label synthesis.
- Multiple reimbursement entries per transaction (single aggregate amount).
