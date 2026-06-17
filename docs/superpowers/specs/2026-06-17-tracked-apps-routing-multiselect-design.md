# Design: Tracked-app rename, GX routing fix, and multi-select approve/reject

Date: 2026-06-17

Three independent features bundled into one spec. Each is self-contained and can be
planned/implemented in sequence.

1. **Rename tracked apps** — let the user override an app's auto-derived display label.
2. **GX routing fix** — make GX (and similar) transactions reliably reach the home page
   instead of the notification pool.
3. **Multi-select** — approve/reject several transactions at once on both the home page
   and the notification pool page.

---

## Background: how routing works today (verified)

The home-vs-pool decision lives entirely in `CapturePipeline.decide()`
(`service/CapturePipeline.kt`). It is **not** count-based — there is no "after N approvals"
threshold anywhere:

- A notification reaches the **home page** (as a PENDING / `needsVerification=true` row) only
  if `HeuristicExtractor.extract()` returns non-null, which requires **both an amount AND a
  resolvable merchant**.
- If the heuristic resolves nothing but an amount is still found, the notification is **pooled**.
- If the package is *rejected*, even a clean parse is pooled (hidden safety net).

`HeuristicExtractor.resolveMerchant()` (`parsing/HeuristicExtractor.kt:73`) gates on
`OUT_VERB` (paid / transferred / charged / debited / spent / withdrawn / sent / deducted /
purchased / billed …). GX notifications phrase spend as a confirmation —
`RM4.00 to CHEE NYOK LAN is successful` — with **no out-verb**, so the gate returns `null`
and they fall through to the pool. The `to <recipient>` anchor is present but never reached.

Confirmed failing samples:
- `RM4.00 to CHEE NYOK LAN is successful`
- `RM14.50 to AA PHARMACY-SEA PAR is successful`

---

## Feature 1: Rename tracked apps

### Goal
The Tracked Apps screen auto-derives each app's label from its package name
(`SourceLabels.label()`). Users want to override it for clarity. The label also renders on
the **notification pool** screen, so a rename must apply there too.

### Persistence
New Room table + DAO:

```kotlin
@Entity(tableName = "custom_source_labels")
data class CustomSourceLabel(
    @PrimaryKey val packageName: String,
    val label: String,
    val updatedAt: Instant,
)
```

DAO: `upsert(row)`, `delete(packageName)`, `observeAll(): Flow<List<CustomSourceLabel>>`
(repo maps to `Map<String, String>`). Bump Room schema version + migration (additive).

### Label resolution
`SourceLabels.label()` stays a pure auto-deriver. A custom label, when present, overrides it.
Resolution happens where UI data is assembled — composables never read the DB:

- **Tracked Apps**: `TransactionRepository.buildTrackedPackageRows()` (`:1659`) overlays the
  custom-label map onto each `TrackedPackageRow.label`.
- **Pool**: `PoolViewModel` observes the custom-label map and exposes a
  `labelFor(packageName): String` resolver in its UI state. `PoolScreen` replaces its three
  direct `SourceLabels.label(row.packageName)` calls (`PoolScreen.kt:153, 197, 239`) with
  `state.labelFor(...)`.

### Repository
```kotlin
suspend fun renameTrackedApp(packageName: String, label: String)   // upsert; blank => clear
suspend fun clearTrackedAppName(packageName: String)
fun observeCustomLabels(): Flow<Map<String, String>>
```

### UI
Add a **Rename** action to `PackageActionSheet` (`TrackedAppsScreen.kt:131`). Opens a small
dialog with a text field prefilled with the current label. Save with text → upsert override.
Save empty → clear override (revert to auto-derived). `TrackedAppsViewModel` gains
`rename(packageName, label)`.

### Scope
Tracked Apps + Pool screens only. Funding-source auto-naming
(`FundingSourceClassifier.kt:101`) is **not** changed; existing funding sources keep their
names and future auto-naming keeps using the built-in label.

### Tests
- DAO upsert/delete/observe round-trip.
- `buildTrackedPackageRows()` applies an override and falls back to auto when none.
- `PoolViewModel.labelFor()` returns override when present, auto otherwise.

---

## Feature 2: GX routing fix

Two complementary fixes (both requested).

### Fix A — parser extension (`HeuristicExtractor`)
Add a third recognized shape, **transfer-success**, that does not require an out-verb because
the `to <recipient>` + success-confirmation structure is specific enough to exclude promo
noise. Tried alongside the card-spend shape in `resolveMerchant()`, *before* the `OUT_VERB`
gate:

```kotlin
private val TRANSFER_SUCCESS_PATTERN = Regex(
    """^RM\s*[\d,]+(?:\.\d{2})?\s+to\s+(?<merchant>.+?)\s+is\s+success(?:ful)?\b""",
    RegexOption.IGNORE_CASE,
)
```

`resolveMerchant()` order becomes: card-spend → transfer-success → OUT_VERB gate →
recipient patterns → `UNDEFINED_MERCHANT`. The new shape captures `CHEE NYOK LAN` and
`AA PHARMACY-SEA PAR`.

Tests: both GX samples resolve to the right merchant; a promo line
(`RM5.00 cashback is waiting`) still does **not** match.

### Fix B — per-app "trust → auto-home" toggle (generic safety net)
New Room table + DAO:

```kotlin
@Entity(tableName = "auto_promote_sources")
data class AutoPromoteSource(
    @PrimaryKey val packageName: String,
    val enabledAt: Instant,
)
```

DAO: `insert`, `delete`, `isAutoPromote(packageName): Boolean`, `observeAllPackageNames()`.
Bump Room schema version + migration (additive).

`CapturePipeline.decide()` gains an `isAutoPromote: Boolean` parameter (passed by
`TxNotificationListener`, read via repo). In the **heuristic-failed-but-amount-found** branch:
if `isAutoPromote` is true, return `CaptureDecision.Parsed` with a `ParsedTransaction` using
`merchantRaw = UNDEFINED_MERCHANT`, `direction = OUT`, the parsed amount/currency — so it
lands on the home page as a PENDING row for the user to label — instead of `Pooled`.

Listener: `Parsed` path already inserts with `needsVerification=true` and calls
`trackPackage`, so no extra wiring beyond passing the flag.

UI: a **toggle** ("Auto-add to home even when details can't be read") in `PackageActionSheet`,
shown only for **TRACKED** apps. Repo: `setAutoPromote(packageName, enabled)`,
`observeAutoPromotePackages()`. The toggle state surfaces on `TrackedPackageRow`
(new `autoPromote: Boolean` field assembled in `buildTrackedPackageRows()`).

`UNDEFINED_MERCHANT` is the existing recipient-less sentinel; merchant-keyed learning already
skips it, so auto-promoted rows won't poison category/description learning.

Tests: `CapturePipeline.decide()` with `isAutoPromote=true` + unparseable-but-amount text →
`Parsed(UNDEFINED_MERCHANT)`; with `false` → `Pooled` (unchanged).

---

## Feature 3: Multi-select approve/reject

### Pattern
Standard Material 3 selection mode on both lists:
- **Long-press** a row enters selection mode and selects it.
- In selection mode, **tap** toggles selection (does not open the edit/action sheet).
- The top bar is replaced by a **contextual action bar**: selected count, action icons, and a
  close (✕) that exits selection mode and clears the selection.
- Selected rows show a checkbox / selected-state highlight.

Each ViewModel gains `selectionMode: Boolean` and `selectedIds: Set<Long>` in its UI state,
plus `toggleSelect(id)`, `enterSelection(id)`, `clearSelection()`.

### Home page (`HomeScreen` / `HomeViewModel`)
Actions: **Confirm** and **Delete**.

- **Confirm**: batch-clear `needsVerification` on selected rows. Must replicate the single-row
  side effect in `setNeedsVerification(false)` — insert each distinct `sourceApp`
  (excluding `MANUAL_SOURCE_APP`) into `ApprovedSource`. New repo method
  `confirmTransactions(ids: List<Long>)` running in one `withTransaction`; new DAO
  `UPDATE transactions SET needsVerification = 0 WHERE id IN (:ids)`. No-op effect on rows
  that are already verified.
- **Delete**: batch-delete with a single undo snackbar that restores all deleted rows
  (reuse existing delete/restore mechanics, batched). New DAO `DELETE … WHERE id IN (:ids)`
  and a batch restore.

Selection spans the currently displayed filter. Both action buttons always present in the CAB.

### Notification pool (`PoolScreen` / `PoolViewModel`)
Actions: **Approve** (promote to home) and **Reject** (mark noise).

- **Reject**: batch `markNoise` — per-entry disposition flip, **not** whole-package reject.
  New DAO `UPDATE captured_notifications SET disposition = 'NOISE' WHERE id IN (:ids)`;
  repo `markPoolEntriesNoise(ids)`.
- **Approve**: pool entries carry no parsed merchant, and `promotePoolEntryBody` rejects an
  empty merchant. So batch-approve uses a new batch-aware promote path: for each selected
  entry, **re-run the (now GX-aware) `HeuristicExtractor` on its `rawText`**; if it resolves a
  merchant use it, else fall back to `UNDEFINED_MERCHANT`. Promote with `needsVerification=true`
  so entries land on the home page as PENDING for labeling, reusing promote's dedupe-key
  computation, `markPromoted`, and `ApprovedSource` side effects. New repo method
  `promotePoolEntries(ids)`; runs each promote in the shared transaction semantics.

### Tests
- ViewModel: enter/toggle/clear selection transitions; action with empty selection is a no-op.
- Repo: `confirmTransactions` clears flags and inserts `ApprovedSource` for each distinct
  non-manual source; manual rows skipped.
- Repo: batch delete + restore round-trip.
- Repo: `markPoolEntriesNoise` flips disposition for all ids.
- Repo: `promotePoolEntries` resolves merchant via heuristic when possible, else
  `UNDEFINED_MERCHANT`; promoted rows are `needsVerification=true`; dedupe + `ApprovedSource`
  side effects fire.

---

## Cross-cutting notes

- **Room migrations**: two new tables (`custom_source_labels`, `auto_promote_sources`). Single
  schema-version bump with both additive migrations.
- **Testing limit**: compile gate + `testDebugUnitTest` only — no device/emulator runs.
- **No auto-commit** during spec/plan; commits are run explicitly by the user.
- `TrackedPackageRow` gains one new field (`autoPromote: Boolean`); its existing `label` is now
  produced by overlaying any custom label onto the auto-derived value. Nothing else about how
  the row is assembled changes.

## Out of scope (YAGNI)
- Recategorize-in-bulk on the home page (offered, not selected).
- Changing funding-source auto-naming.
- Count/confidence-based auto-approval thresholds (no such mechanism exists; not introducing one).
