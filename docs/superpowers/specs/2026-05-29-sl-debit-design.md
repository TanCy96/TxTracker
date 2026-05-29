# SL Debit — Design

**Date:** 2026-05-29
**Status:** Approved (brainstorming complete; pending implementation plan)
**Scope:** Personal-use feature. This document is the reference of record for the
SL Debit branch so future features that touch transactions, totals, CSV export, or
backup can reason about the conflicts this branch introduces.

---

## 1. Purpose

A single, renameable **SL Debit account** acts as a prepaid pool that a third party
("SL") funds. The user:

- **Logs deposits** manually (e.g. "RM500 into SL Debit") — top-ups that raise the pool.
- **Shares** selected transactions: ticking *Share with SL Debit* assigns part of the
  transaction (default 40%, editable) to SL. The share is drawn down from the pool and
  subtracted from the user's own spending.
- **Sees the running balance** somewhere on the UI (deposits minus shares used).

Accounting intent: SL transfers money into the user's bank in advance (deposits). As the
user makes shared purchases, SL's share of each is treated as money that effectively came
**in** via bank — recorded against the `DEBIT_BANK` funding bucket as a **negative**
(inflow) amount in exports.

### Key design decision (Option A)

The negative `DEBIT_BANK` lines are **synthesized at aggregation/export time only**. They
are **never stored as real `Transaction` rows**. This keeps the transaction list clean,
keeps a single source of truth, avoids introducing negative `amountMinor` anywhere in the
app, and makes the share editable inline on the owning transaction. The cost is that
aggregation code (Home totals, CSV) must special-case `slShareMinor`.

---

## 2. Data model

Schema migration **v11 → v12**.

### 2.1 `Transaction.slShareMinor: Long?` (new nullable column)

```kotlin
/**
 * The SL Debit share of this transaction, in minor units. null = not shared.
 * Invariant (enforced by repository/UI, not schema): 0 < slShareMinor <= amountMinor.
 * Only ever set on MYR, direction = OUT rows. The synthesized DEBIT_BANK negative
 * inflow line in CSV/aggregation is derived from this field; it is never a real row.
 */
val slShareMinor: Long? = null
```

Migration adds the column with default `NULL`. No backfill needed.

### 2.2 `sl_debit_account` table — singleton

```kotlin
@Entity(tableName = "sl_debit_account")
data class SlDebitAccount(
    @PrimaryKey val id: Long = 1,          // singleton; always id = 1
    val displayName: String,               // default "SL Debit"
    val defaultSharePercent: Int,          // default 40; used to prefill the share field
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

Seeded by the migration with `displayName = "SL Debit"`, `defaultSharePercent = 40`.

### 2.3 `sl_debit_deposit` table — deposit ledger

```kotlin
@Entity(tableName = "sl_debit_deposit", indices = [Index("occurredAt")])
data class SlDebitDeposit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,                 // positive top-up amount
    val occurredAt: Instant,               // the date the deposit happened (user-chosen)
    val note: String? = null,
    val createdAt: Instant,
)
```

Deposits are **editable and deletable** in Settings.

### 2.4 Derived balance

```
balanceMinor = SUM(sl_debit_deposit.amountMinor)
             - SUM(transactions.slShareMinor WHERE slShareMinor IS NOT NULL)
```

Nothing persists the balance; it is always computed. The share sum spans **all** dates
(the pool is lifetime, not month-scoped), matching the deposit sum.

---

## 3. Aggregation & totals

Both Home aggregation queries in `TransactionDao` change to net out the share:

```sql
-- observeCategoryTotalsBetween
SELECT categoryId, SUM(amountMinor - COALESCE(slShareMinor, 0)) AS totalMinor
FROM transactions
WHERE occurredAt >= :start AND occurredAt < :end
  AND direction = 'OUT' AND currency = 'MYR'
GROUP BY categoryId

-- observeTotalBetween
SELECT COALESCE(SUM(amountMinor - COALESCE(slShareMinor, 0)), 0)
FROM transactions
WHERE occurredAt >= :start AND occurredAt < :end
  AND direction = 'OUT' AND currency = 'MYR'
```

Result: the Home **headline total** and the **per-category breakdown** both show the
**net** amount (RM100 − RM40 = RM60), staying mutually consistent.

**Funding-bucket chips/counts on Home stay keyed on the real `fundingSourceId` only.**
The synthesized `DEBIT_BANK` negative is **export-only** — it does NOT add a phantom
`DEBIT_BANK` chip or alter any Home bucket total. (Confirmed.)

---

## 4. Per-transaction UI

### 4.1 Share toggle (Edit sheet + Add Manual sheet)

- A `Share with SL Debit` toggle, shown **only for MYR transactions**.
- When enabled: an amount field prefilled to `round(amountMinor × defaultSharePercent / 100)`,
  freely editable. Validated to the half-open-ish range `(0, amountMinor]`. Disabling the
  toggle clears `slShareMinor` back to `null`.
- Edit sheet wiring follows the existing `onFundingSourceChange`-style callback →
  `EditTransactionViewModel` → repository update pattern.
- Add Manual sheet sets `slShareMinor` at insert time.

### 4.2 Row & header display (shared rows only)

A shared transaction shows three numbers in distinct theme colors:

- Original `RM100.00` — muted / de-emphasized (e.g. strikethrough or `onSurfaceVariant`).
- Net `RM60.00` — primary emphasis (this is "what you actually paid").
- `−RM40.00 SL` — a "returned money" accent (tertiary / green-ish).

New semantic colors added to `ui/theme/Color.kt` + `Theme.kt`. **Non-shared rows render
exactly as today** — no visual change.

---

## 5. SL Debit balance surface

### 5.1 Home card

A compact balance card near the top of Home: `SL Debit · RM x,xxx.xx`. Tapping it opens
the Settings management screen. Always visible (independent of month navigation, since the
balance is lifetime).

### 5.2 Settings → SL Debit screen

Mirrors the existing `FundingSourcesScreen` / `FundingSourcesViewModel` patterns:

- Rename account (`displayName`).
- Edit `defaultSharePercent`.
- Show current balance.
- **Log a deposit**: amount + date + optional note.
- List of deposits, each **editable and deletable**.

---

## 6. CSV export

`CsvExporter` / `buildCsv` changes. Existing columns:
`date, description, Source, <each category>, Unverified`. Add **one** new column.

### 6.1 New `SL Debit` column

Per day (one-row-per-day model), a spreadsheet formula combining that day's deposits
(positive) and that day's shares (negative):

```
=500.00-40.00-12.50
```

(deposit RM500, two shared txns RM40 and RM12.50 that day). Evaluates to net pool movement
for the day. Empty when the day has neither deposits nor shares.

### 6.2 Category column with subtraction inline

For a shared transaction, its category cell shows the subtraction inline (confirmed):

```
=100.00-40.00                 # single shared tx in the category that day
=100.00-40.00+50.00           # plus a non-shared RM50 tx in the same category/day
```

The cell still evaluates to the **net**. This extends the existing `buildAmountCell`
formula style (which already emits `=a+b+c` for multi-tx days). The builder must now emit,
per transaction, `amount` or `amount-share` as the term.

### 6.3 Source column

On days containing any shared transaction, the `Debit/Transfer` bucket label is added to
the day's distinct Source label set, **alongside** the real funding labels. Example: a
credit-card transaction that is also shared contributes `Credit Card / Debit/Transfer` to
that day's Source cell. (Confirmed — it augments, never replaces, existing source labels.)
Labels remain de-duplicated and ordered by `CANONICAL_KIND_ORDER`.

### 6.4 Note on the "negative recorded in CSV"

The requirement that "CSV export will see this negative amount recorded" is satisfied by
§6.1 (the `SL Debit` column carries the negative shares) together with §6.2 (the category
cell shows the subtraction). There is no separate per-row negative DEBIT_BANK line in the
day-grouped CSV.

---

## 7. Backup / cloud sync

Backup **v8 → v9** (`Backup.CURRENT_VERSION`):

- `BackupTransaction` gains `slShareMinor: Long? = null`.
- `Backup` gains `slDebitAccount: BackupSlDebitAccount? = null` and
  `slDebitDeposits: List<BackupSlDebitDeposit> = emptyList()`.
- Import is additive and back-compatible: older backups (v8 and earlier) parse with the new
  fields defaulting to null/empty, leaving SL Debit unconfigured.
- `BackupExporter` / `BackupImporter` serialize and restore the new table contents.

---

## 8. Testing

Per standing prefs: **compile + `testDebugUnitTest` only**; no emulator / `connectedDebugAndroidTest`.

Unit tests:
- Balance math (deposits − shares), including empty and negative-edge cases.
- Share validation + percent rounding (e.g. 40% of RM100.01 → RM40.00).
- `buildCsv`: category subtraction cell, `SL Debit` column formula, Source label augmentation.
- Net category-total and headline-total SQL (via DAO test).

Migration / round-trip (Room migration tests run as unit-level where the existing suite
already does — match `MigrationV10ToV11Test` style):
- v11 → v12 migration (column add + table create + account seed).
- Backup v8 → v9 round-trip and v8-import-into-v9 back-compat.

---

## 9. Conflict surface (for future features)

This branch modifies the following shared points — future work should expect conflicts here:

- `Transaction` entity (new column) + `TxDatabase` version + a new migration.
- `TransactionDao.observeCategoryTotalsBetween` / `observeTotalBetween` (net math).
- `CsvExporter.buildCsv` + `buildAmountCell` (new column, subtraction terms, Source label).
- `Backup` wire format + version + `BackupExporter`/`BackupImporter`.
- `EditTransactionSheet` / `EditTransactionViewModel`, `AddManualSheet` / `AddManualViewModel`
  (share toggle).
- `HomeScreen` / `HomeUiState` / `HomeViewModel` (balance card + per-row share display).
- New: `SlDebitDao`, `SlDebitAccount`, `SlDebitDeposit`, Settings SL Debit screen + VM,
  theme colors.

---

## 10. Explicitly out of scope (YAGNI)

- Multiple share accounts (only one SL Debit account; renameable).
- Sharing non-MYR / foreign-currency transactions.
- Sharing captured transactions before they exist as rows (share is set on the row, after
  capture, via the Edit sheet — captured rows are eligible once persisted).
- Per-transaction custom percentages persisted separately (the default % lives on the
  account; per-tx the *amount* is what's stored, not a per-tx percent).
- Auto-matching real DEBIT_BANK inflow notifications to SL deposits.
