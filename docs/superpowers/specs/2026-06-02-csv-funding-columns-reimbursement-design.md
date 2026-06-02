# Funding-source CSV columns + multi-entry reimbursements — Design

**Date:** 2026-06-02
**Target branch:** `main`. DB schema **v12 → v13**, backup **v9 → v10**.
**Status:** Designed; not yet implemented.
**Builds on:** the reimbursed-by-others share already shipped on `main`
(`docs/superpowers/specs/2026-06-01-reimbursed-share-design.md`).

Two coupled CSV-export improvements:

1. **Funding-source columns.** Replace the free-text `Source` column with four fixed
   bucket-kind columns that each carry the *amount* funded from that bucket.
2. **Multi-entry reimbursements.** Let a transaction be reimbursed by several people, each
   entry recording an amount and the **funding bucket the money landed in**. In CSV those
   land as **negative numbers in the destination funding column** — mirroring how SL Debit
   records negative shares in the `DEBIT_BANK` column on `feature/share-debit`.

---

## 0. Relationship to SL Debit (READ FIRST)

This feature is built on `main`. SL Debit lives only on `feature/share-debit`.

Replacing the `Source` column with bucket-kind columns **will conflict** with the branch,
which (a) synthesizes a `DEBIT_BANK` entry into the `Source` cell for SL-shared days and
(b) appends a dedicated `SL Debit` column. Resolving that conflict — e.g. routing
`slShareMinor` negatives into the new `Debit/Transfer` bucket column, or keeping the
separate `SL Debit` column alongside the bucket columns — is the **branch's documented
merge burden** under the SL Debit branch policy (main merges *into* the branch, SL Debit is
preserved on conflict). It is **not** main's concern in this spec.

---

## 1. Purpose & accounting model

- **Funding columns** answer "how did money flow through each account/bucket?". A
  transaction's **gross** `amountMinor` is added (positive) to its linked funding source's
  bucket column. A reimbursement received is subtracted (negative) from the bucket it landed
  in.
- **Category columns are unchanged** — still **net** (`amountMinor − Σ reimbursements`),
  inline-subtracted as today.
- Both lenses reconcile: **sum-of-category-columns = sum-of-funding-columns = true net
  spend** (modulo transactions with no linked funding source — see §5.3).

Worked example — a RM100 dinner paid by credit card, Person A returns RM10 via bank
transfer, Person B returns RM12 via TnG e-wallet, all on the dinner's day:

| date | … | Dining | … | Credit Card | E-Wallet | Debit/Transfer | Cash |
|------|---|--------|---|-------------|----------|----------------|------|
| 2026-06-02 | | `=100.00-10.00-12.00` | | `100.00` | `-12.00` | `-10.00` | |

`sum(funding) = 100 − 12 − 10 = 78`; `Dining = 78`. Consistent.

---

## 2. Data model — Approach A (child table + cached sum)

Schema migration **v12 → v13**.

### 2.1 New entity `ReimbursementEntry` (`data/Entities.kt`)

```kotlin
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
    /** Portion this person returned, in minor units. > 0. */
    val amountMinor: Long,
    /** Funding bucket the money landed in (which CSV funding column gets the negative). */
    val destinationKind: FundingSourceKind,
    /** Optional free-text label for who reimbursed. In-app only; never emitted to CSV. */
    val personLabel: String? = null,
    /** For stable ordering in the edit sheet and CSV term order. */
    val createdAt: Instant,
)
```

`destinationKind` persists via the existing `FundingSourceKind` Room type-converter (same one
the `funding_sources` table uses).

### 2.2 `Transaction.reimbursedMinor` retained as a **cached sum**

`reimbursedMinor` stays on `Transaction` and continues to mean "total others reimbursed". It
is now **maintained as `Σ entries.amountMinor`** (null when there are no entries), recomputed
by the repository on every entry add/update/delete. This keeps **every existing netting
surface untouched**:

- `TransactionDao.observeCategoryTotalsBetween` / `observeTotalBetween`
  (`amountMinor − COALESCE(reimbursedMinor, 0)`).
- `InsightsAggregator.chartAmountMinor`.
- `notify/SummaryWorker` headline + top-by-category.
- `ui/home/HomeScreen` `DayHeader` + `RowAmount`; `ui/foreign/*` rows/totals.

### 2.3 Migration `MIGRATION_12_13` (`di/DatabaseModule.kt`)

```sql
CREATE TABLE reimbursement_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    transactionId INTEGER NOT NULL,
    amountMinor INTEGER NOT NULL,
    destinationKind TEXT NOT NULL,
    personLabel TEXT,
    createdAt INTEGER NOT NULL,
    FOREIGN KEY(transactionId) REFERENCES transactions(id) ON DELETE CASCADE
);
CREATE INDEX index_reimbursement_entries_transactionId
    ON reimbursement_entries(transactionId);
```

**Backfill:** for each transaction with `reimbursedMinor > 0`, insert one entry
`(transactionId, amountMinor = reimbursedMinor, destinationKind = 'DEBIT_BANK',
personLabel = NULL, createdAt = transaction.occurredAt)`. `reimbursedMinor` is left as-is
(it already equals the new sum). **Default destination `DEBIT_BANK` is a deliberate guess
for legacy rows** (bank transfer being the common reimbursement channel); confirmed during
brainstorming.

Register `MIGRATION_12_13` in `DatabaseModule.addMigrations(...)`, bump
`TxDatabase version = 13`, export `app/schemas/.../13.json`.

### 2.4 DAO (`data/TransactionDao.kt` or a new `ReimbursementEntryDao`)

```kotlin
@Insert suspend fun insertReimbursementEntry(entry: ReimbursementEntry): Long
@Update suspend fun updateReimbursementEntry(entry: ReimbursementEntry)
@Delete suspend fun deleteReimbursementEntry(entry: ReimbursementEntry)

@Query("SELECT * FROM reimbursement_entries WHERE transactionId = :txId ORDER BY createdAt, id")
fun observeReimbursementEntries(txId: Long): Flow<List<ReimbursementEntry>>

@Query("SELECT * FROM reimbursement_entries WHERE transactionId = :txId ORDER BY createdAt, id")
suspend fun getReimbursementEntries(txId: Long): List<ReimbursementEntry>

@Query("SELECT COALESCE(SUM(amountMinor), 0) FROM reimbursement_entries WHERE transactionId = :txId")
suspend fun reimbursedTotal(txId: Long): Long
```

The existing `updateReimbursed(id, reimbursedMinor)` query is kept and reused to write the
cached sum.

### 2.5 Repository (`data/TransactionRepository.kt`)

Replace the single `setReimbursed`-style wrapper with entry CRUD, each of which recomputes
and writes the cached total in one transaction-scoped step:

```kotlin
suspend fun addReimbursementEntry(txId, amountMinor, destinationKind, personLabel?)
suspend fun updateReimbursementEntry(entry)
suspend fun deleteReimbursementEntry(entry)
// each → recompute: val total = dao.reimbursedTotal(txId)
//                    dao.updateReimbursed(txId, total.takeIf { it > 0 })
fun observeReimbursementEntries(txId): Flow<List<ReimbursementEntry>>
suspend fun getReimbursementEntries(txId): List<ReimbursementEntry>   // for CSV/backup
```

---

## 3. Validation (`domain/Share.kt`)

`isValidReimbursedMinor(reimbursedMinor, amountMinor)` is kept and applied to the **running
total** of entries. Per-entry rule: `amountMinor > 0` and `destinationKind` set. Combined:

```kotlin
/** The full set of entries is valid when each amount > 0 and their sum is in 1..amountMinor. */
fun isValidReimbursementTotal(entryAmountsMinor: List<Long>, amountMinor: Long): Boolean =
    entryAmountsMinor.all { it > 0 } &&
        isValidReimbursedMinor(entryAmountsMinor.sum(), amountMinor)
```

(The `feature/share-debit` combined-ceiling — `slShareMinor + Σ reimbursements ≤ amountMinor`
— remains branch-only merge logic; main does not add it.)

---

## 4. UI — Edit sheet + Add Manual

The single "Reimbursed by others" amount field becomes a **list of entry rows** under the
same section header.

Each row:
- **Amount** — currency-aware formatter/parser for the transaction's currency.
- **Destination bucket** — dropdown over `FundingSourceKind` (Credit Card / E-Wallet /
  Debit/Transfer / Cash). Defaults to Debit/Transfer.
- **Person** — optional free-text field.
- **Remove** button.

An **"Add reimbursement"** button appends a row. The section is valid when
`isValidReimbursementTotal(...)` holds; blank/invalid rows block save with inline feedback.

- **Edit sheet** (`EditTransactionSheet.kt` / `EditTransactionViewModel.kt`): observe entries
  via `observeReimbursementEntries`; add/update/delete call the repository, which keeps the
  cached `reimbursedMinor` in step. The `onReimbursedChange: (Long?) -> Unit` callback is
  replaced by per-entry callbacks.
- **Add Manual** (`AddManualSheet.kt` / `AddManualViewModel.kt` / `AddManualUiState`): collect
  entries in UI state; after the parent transaction is inserted (and its id known), persist
  each entry then recompute the cached total.

**Row display** in `HomeScreen.RowAmount` and the Foreign-tab rows is **unchanged** — net
amount (emphasized), original (strikethrough, `onSurfaceVariant`), `−X Reimbursed` in
`ReimbursedAccent` — now driven by the cached `reimbursedMinor`. Person labels and per-entry
breakdown are **not** shown on the row (edit-sheet only).

---

## 5. CSV export (`export/CsvExporter.kt`)

### 5.1 Header — drop `Source`, append four bucket columns

```
date,description,<category…>,Unverified,Credit Card,E-Wallet,Debit/Transfer,Cash
```

The bucket columns are emitted in `CANONICAL_KIND_ORDER`
(Credit Card, E-Wallet, Debit/Transfer, Cash). The existing `bucketLabel` /
`CANONICAL_KIND_ORDER` helpers supply the headers.

### 5.2 Category columns — unchanged net behavior, now multi-subtraction

The per-transaction category term subtracts **each** reimbursement entry inline:
`amount` → `amount-e1-e2-…`. So a day's Dining cell may read `=100.00-10.00-12.00`. The cell
still evaluates to the net. (`buildAmountCell` / `categoryTerm` widened from a single
`reimbursed` to the list of entry amounts for that transaction.)

### 5.3 Funding columns — positive gross + negative reimbursements

For each bucket kind, per day:
- **Positive terms:** every transaction whose linked funding source has that kind contributes
  `+<gross amountMinor>`.
- **Negative terms:** every reimbursement entry whose `destinationKind` equals that bucket
  contributes `-<amountMinor>`, on the **parent transaction's day-row**.
- **Cell format** (reuse the SL Debit-cell rule): empty → blank; one term → bare literal
  (`100.00` or `-10.00`); more than one → spreadsheet formula (`=100.00-10.00`).
- **Unlinked transactions** (null/unresolved `fundingSourceId`) contribute to no funding
  column — the same gap today's blank `Source` cell has. This is the only reason the two
  lenses can differ in total.

The builder needs the entries available at export time: `buildCsv` takes an additional
`reimbursementEntriesByTxId: Map<Long, List<ReimbursementEntry>>`, populated by `CsvExporter`
from `repository.getReimbursementEntries(...)` (batched). `writeCsv`, `exportCsv`,
`exportAllCurrenciesZip` thread it through.

---

## 6. Backup v9 → v10 (`export/Backup.kt`, `BackupExporter.kt`, `BackupImporter.kt`)

- `Backup.CURRENT_VERSION = 10`.
- New `BackupReimbursementEntry(transactionId, amountMinor, destinationKind, personLabel,
  createdAt)` and a top-level `reimbursementEntries: List<...> = emptyList()` on `Backup`.
- `BackupExporter` serializes all entries; `BackupImporter` restores them and recomputes each
  transaction's cached `reimbursedMinor` (or trusts the value already in `BackupTransaction`,
  which equals the sum).
- **Back-compat:** v9 backups (which carry `reimbursedMinor` but no entries) import by
  synthesizing one `DEBIT_BANK` entry per transaction with `reimbursedMinor > 0` — identical
  to the §2.3 migration backfill, so *restore* and *migrate* land in the same state.
  `SUPPORTED_VERSIONS` extends to `..10`.

---

## 7. Aggregation impact

**None.** Because `reimbursedMinor` is kept as the maintained cached sum, all Home totals,
Insights charts, summary notifications, and Foreign totals net exactly as they do today,
with no SQL or aggregator changes.

---

## 8. Testing (compile + `testDebugUnitTest` only; no emulator)

Unit:
- `isValidReimbursementTotal` — per-entry `> 0`, sum boundaries (`0`, `1`, `amount`,
  `amount+1`).
- `buildCsv` funding columns: gross positives by bucket; reimbursement negatives in the
  destination bucket; multi-term formula; multi-bucket day; **unlinked-transaction gap**;
  header has no `Source` and the four bucket columns in canonical order.
- `buildCsv` category cell: chained multi-subtraction (`=100.00-10.00-12.00`); net still
  correct.
- Repository: adding/updating/deleting an entry recomputes the cached `reimbursedMinor`
  (→ null when the last entry is removed).

Migration / round-trip (unit-level, matching existing migration-test style):
- v12 → v13: table created; one backfilled `DEBIT_BANK` entry per legacy `reimbursedMinor>0`
  row; rows with no reimbursement get none.
- Backup v9 → v10 round-trip; v9-import-into-v10 synthesizes the `DEBIT_BANK` entry and
  matches the migration result.

---

## 9. Touch-point summary (files to change)

- `data/Entities.kt` — new `ReimbursementEntry`.
- `data/TxDatabase.kt` — register entity; `version = 13`; `app/schemas/.../13.json`.
- `di/DatabaseModule.kt` — `MIGRATION_12_13` (+ backfill) + registration.
- `data/TransactionDao.kt` (or new `ReimbursementEntryDao`) — entry CRUD, observe, sum;
  keep `updateReimbursed`.
- `data/TransactionRepository.kt` — entry CRUD wrappers that recompute the cached sum;
  `getReimbursementEntries` for CSV/backup.
- `domain/Share.kt` — `isValidReimbursementTotal`.
- `ui/edit/EditTransactionSheet.kt` + `EditTransactionViewModel.kt` — entry-list section.
- `ui/manual/AddManualSheet.kt` + `AddManualViewModel.kt` (+ `AddManualUiState`) — entry list,
  persisted post-insert.
- `export/CsvExporter.kt` — drop `Source` column; four bucket columns (positive gross +
  negative reimbursements); multi-subtraction category cell; thread entries through
  `buildCsv` / `writeCsv` / `exportCsv` / `exportAllCurrenciesZip`.
- `export/Backup.kt` (`CURRENT_VERSION = 10`, `BackupReimbursementEntry`,
  `reimbursementEntries`), `BackupExporter.kt`, `BackupImporter.kt` (restore + v9 synth +
  `SUPPORTED_VERSIONS`).
- Tests: `export/BuildCsvTest.kt`, validation tests, migration test, backup round-trip test.

No changes to: `TransactionDao` netting SQL, `InsightsAggregator`, `SummaryWorker`,
`HomeScreen`/Foreign row rendering, `ui/theme/Color.kt` (`ReimbursedAccent` reused as-is).

---

## 10. Explicitly out of scope (YAGNI)

- Per-entry **receive-date** (entries net on the parent transaction's day).
- Destination as a specific `FundingSource` rather than a bucket **kind**.
- **Person name in CSV** (in-app label only).
- Reimbursing **inflows** (OUT rows only).
- Any **pool / ledger / deposit / balance** surface for reimbursements.
- Showing per-entry breakdown or person on the Home/Foreign transaction row.
