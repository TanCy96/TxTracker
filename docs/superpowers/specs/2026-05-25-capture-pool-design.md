# Capture pipeline visibility — notification pool + app management

**Date:** 2026-05-25
**Scope:** FUTURE-list items 5, 6, 7 (with item 4 absorbed as a symptom of
item 5). Replace the binary "capture everything as transactions / drop
everything" capture toggle with a three-tier pipeline: heuristic-confident
parses become real transactions as today; everything else with an amount
lands in a reviewable pool; user manages tracked / rejected / untracked
packages from a Settings screen.

---

## Problem

Three related symptoms in the current pipeline:

1. **Lost notifications.** With `CapturePrefs.captureAllPackages = OFF`,
   notifications from packages not in `SourcePackages.PERMISSIVE_PACKAGES ∪
   ApprovedSource` are dropped at the listener (`TxNotificationListener.kt:91`).
   If GX Bank emits a debit-card notification whose phrasing the heuristic
   misses, the transaction silently vanishes. The user has no way to recover
   it short of re-toggling capture-all and waiting for it to happen again.

2. **Noisy "(review)" transactions.** When `captureAllPackages = ON`, the
   `PermissiveExtractor` creates `<Source> (review)` rows for any amount-
   bearing notification from any package. These pollute the transactions
   table — Slack messages with `$5`, shopping promos with `RM50 off`,
   notification mirrors from other devices — and they all surface in the
   Pending verify queue mixed with real captures. The user turned the
   toggle off to stop this, which re-introduces symptom 1.

3. **No package management.** Packages are added to `ApprovedSource`
   silently whenever the user verifies a Pending row from them. There's
   no way to view that list, remove a package the user no longer wants
   tracked, or reject a package that the parser keeps mis-firing on. The
   only knob is the global capture-all toggle.

Symptom 7 from the user's list — `Currency Review` filter chip permanently
visible on Home even when empty — is a smaller adjacent issue treated in
the same spec because the cleanup belongs to the same surface area.

## Goal

A single capture pipeline with three lanes:

- **Confident lane.** Heuristic-extractor success → `transactions` row,
  `needsVerification = true`, package added to `ApprovedSource`. Identical
  to today.
- **Pool lane.** Notification has an amount but the heuristic missed →
  `captured_notifications` row, no transaction. User reviews from a
  Settings entry, promotes to a real transaction or marks as noise.
- **Dropped.** Notification has no amount, or is a group summary. No
  storage, identical to today.

The user manages which packages are trusted (confident lane), which are
soft-rejected (still pooled but hidden), and which are untracked (pooled
and visible) from a single tracked-apps screen. The `captureAllPackages`
toggle is removed — its job is now split between the pool (catches what
used to be dropped) and the rejected list (suppresses what used to flood
in).

## Non-goals

- **Bulk-triage UI.** Promote / noise / reject operate one row at a time.
  If pool volume grows, revisit later.
- **Push notifications for pool growth.** Single-user app; the Settings
  count badge is sufficient discoverability.
- **Pool entries for amount-less notifications.** No "audit log of every
  notification". Pool entry requires the amount regex to fire.
- **Retroactive package operations beyond migration.** Moving an existing
  tracked package to rejected does NOT rewrite past transactions — only
  affects future captures plus the visibility of current pool entries.
- **Cross-device sync of pool entries.** Pool is device-local; backup
  export of pool entries is out of scope (revisit if users ask).

---

## Data model

Schema v8 → v9. Two new tables, no changes to `transactions` beyond the
implicit removal of `(review)` rows by the migration.

### `captured_notifications`

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
    val postedAt: Instant,         // sbn.postTime
    val amountMinor: Long,
    val currency: String,
    val rawText: String,           // original notification body
    val rewrittenText: String?,    // post-NotificationRewriteEngine, null if no rewrite changed it
    val disposition: Disposition,  // PENDING | PROMOTED | NOISE
    val promotedToTxId: Long?,     // FK to transactions when disposition = PROMOTED; nullable
    val capturedAt: Instant,       // for TTL math; set at insert time
)

enum class Disposition { PENDING, PROMOTED, NOISE }
```

Stored as a TEXT column via a Converter. `promotedToTxId` is a logical
FK — no `ForeignKey` constraint, because cascading from `transactions`
deletion would silently flip promoted entries to a dangling state. If a
user deletes a promoted transaction, the pool row stays with its
`promotedToTxId` pointing at a nonexistent id; the UI treats that as
"orphaned promoted" (visually muted, still recoverable).

### `rejected_sources`

```kotlin
@Entity(tableName = "rejected_sources")
data class RejectedSource(
    @PrimaryKey val packageName: String,
    val rejectedAt: Instant,
)
```

A package can be in `ApprovedSource`, `RejectedSource`, or neither.
Mutual exclusion is enforced at the repository layer: moving to one
removes from the other.

### Migration v8 → v9

```sql
CREATE TABLE captured_notifications ( … );
CREATE INDEX index_captured_notifications_packageName ON captured_notifications(packageName);
CREATE INDEX index_captured_notifications_disposition ON captured_notifications(disposition);
CREATE INDEX index_captured_notifications_capturedAt ON captured_notifications(capturedAt);

CREATE TABLE rejected_sources (
    packageName TEXT NOT NULL PRIMARY KEY,
    rejectedAt INTEGER NOT NULL
);

-- Move existing permissive '(review)' rows into the pool.
INSERT INTO captured_notifications (
    packageName, postedAt, amountMinor, currency, rawText, rewrittenText,
    disposition, promotedToTxId, capturedAt
)
SELECT
    sourceApp, occurredAt, amountMinor, currency, rawText, NULL,
    'PENDING', NULL, occurredAt
FROM transactions
WHERE merchantRaw LIKE '% (review)' AND rawText IS NOT NULL;

DELETE FROM transactions WHERE merchantRaw LIKE '% (review)' AND rawText IS NOT NULL;
```

Rows with `rawText IS NULL` are skipped (defensive — manual entries with a
`(review)` literal merchant name would otherwise vanish; vanishingly rare
but treat as data we don't own).

The SharedPreferences entry `capture/all_packages` is left on disk and
simply not read anymore. Removing the file would require a new migration
step that isn't worth the surface area; the next install just won't see
it.

---

## Listener flow

`TxNotificationListener.onNotificationPosted` becomes:

```
if FLAG_GROUP_SUMMARY: return
rawText = sbn.extractText() ?: return
rewritten = rewriteEngine.apply(packageName, rawText)
postedAt = Instant.fromEpochMilliseconds(sbn.postTime)

scope.launch {
  symbolDefaults = trackedCurrencyDao.getDefaultsForSymbol().associateBy(...)

  // Confident lane.
  heuristic = HeuristicExtractor.extract(rewritten, packageName, postedAt, symbolDefaults)
  if (heuristic != null) {
    insert(heuristic, packageName, needsVerification = true)
    return@launch
  }

  // Pool lane: requires an amount somewhere in the (rewritten) text.
  amountMatch = AMOUNT.find(rewritten) ?: return@launch
  amount = parseAmountMinor(amountMatch.amountStr)
  currency = Currencies.resolve(amountMatch.prefix, amountMatch.suffix, symbolDefaults)
  poolDao.insert(CapturedNotification(
    packageName, postedAt, amount, currency,
    rawText = rawText,
    rewrittenText = if (rewritten != rawText) rewritten else null,
    disposition = PENDING,
    promotedToTxId = null,
    capturedAt = Clock.System.now(),
  ))
}
```

The pool lane runs the AMOUNT regex independently of the heuristic — we
don't reach into the heuristic's null result for a partial match. The
amount regex lives next to `AMOUNT` in `HeuristicExtractor`'s companion
or moves to a shared `Currencies.kt` helper if we want to share it cleanly
with the pool path.

The `bypassAllowlist` parameter and `SourcePackages.PERMISSIVE_PACKAGES`
check at the entry point are both removed. There's no allowlist gate
anymore — every package's notifications enter at least one of the two
lanes (or get dropped for lacking an amount). `ApprovedSource` and
`PERMISSIVE_PACKAGES` survive only as UI signals ("which packages are
tracked by default / by user approval"), plus the auto-add-on-promote
bookkeeping target for `ApprovedSource`. Neither gates capture.

`PermissiveExtractor` is deleted. Its currency-resolution call was already
delegated to `Currencies.resolve`; no logic is lost.

### Retention

A `WorkManager` worker runs daily, deleting rows from
`captured_notifications` where:

```
disposition IN ('PENDING', 'NOISE')
AND packageName IN (SELECT packageName FROM rejected_sources)
AND capturedAt < (now - 30 days)
```

Promoted entries are kept indefinitely (they're the audit link to the
real transaction). Entries from non-rejected packages are kept
indefinitely too — the user explicitly wants the safety net for missed
captures.

---

## UI: pool review screen

Reached from Settings → `Notification pool (N)` where N is the count of
`PENDING` entries whose `packageName` is not in `rejected_sources`.

### Layout

Day-grouped LazyColumn, same shape as `HomeScreen` transaction list:

```
─── Wed, 24 May ──────────────────────
  09:42 │ GX Bank                          RM 9.40
        │ RM9.40 to ML TRADITIONAL DESSERT TAMAN
        │ PARAMOUNT is successful
        │ → (rewritten) RM9.40 paid to ML TRADITIONAL …    ← muted, only when rewrite present
  14:12 │ Slack                            RM 50.00
        │ Lunch yesterday RM50 — Splitwise reminder
─── Tue, 23 May ──────────────────────
  …
```

Each row:
- Time of day + package short-label (resolved by the same `sourceLabel`
  helper currently in the deleted `PermissiveExtractor` — moves to a
  shared `SourceLabels` object).
- Currency-formatted amount on the right.
- Subtitle line 1: `rawText`, ellipsized to ~2 lines.
- Subtitle line 2 (conditional): `→ <rewrittenText>` in
  `colorScheme.onSurfaceVariant` when present and != rawText. Lets the
  user see what the parser actually saw after their rewrite rules.

### Filter chips (top of screen)

`Pending` (default) / `Noise` / `Promoted` / `All`. Pending excludes
entries from `rejected_sources`; `All` shows them too.

### Row tap → action sheet

Three primary actions, vertically stacked:

- **Promote to transaction.** Opens a pre-filled
  `EditTransactionSheet` variant (`PromotePoolEntrySheet`) with:
  - amount, currency, occurredAt = the pool row's
  - merchant = empty (user fills)
  - rawText pre-filled
  - sourceApp = the pool row's package
  - `needsVerification = false` on save (user just touched it)
  - On save: insert the transaction, update pool row to
    `disposition = PROMOTED, promotedToTxId = newId`,
    insert-or-ignore into `ApprovedSource(packageName)`. The package
    automatically becomes tracked.

- **Mark as noise.** Sets `disposition = NOISE`. No package-level
  effect. Row vanishes from the default filter; still visible in `Noise`.

- **Reject package.** Bottom-sheet confirmation: "Hide all future
  notifications from `<package>` by default?" On confirm: upsert into
  `rejected_sources`, soft-mark all of this package's `PENDING` entries
  as `NOISE`, remove from `ApprovedSource` if present.

A secondary "View full text" expands the row in place — no separate
detail screen needed.

---

## UI: tracked apps screen (item 6)

Reached from Settings → `Tracked apps`. Three sections inside a single
LazyColumn, each rendered as a sticky-header group:

### Section 1 — Tracked

`PERMISSIVE_PACKAGES ∪ ApprovedSource.packageName`. Each row:
- Package label (e.g. "GX Bank") + small `(my.com.gxbank.app)` in
  caption style.
- "built-in" assist chip if the package is in `PERMISSIVE_PACKAGES`.
  Built-ins can be moved to Rejected — since the listener no longer
  consults `PERMISSIVE_PACKAGES` for gating (it's UI-display-only post
  this spec), a rejected built-in simply renders under Rejected and
  is excluded from the pool screen's default filter. The package
  remains in `PERMISSIVE_PACKAGES` (it's a code constant) but the
  rejected-sources row wins for every UI surface.
- Trailing: count of `captured_notifications` entries (any disposition)
  from this package, last 30 days. Tap → deep-link to pool screen
  pre-filtered by `packageName`.

Row action sheet:
- **Move to Rejected** — insert into `rejected_sources`, remove from
  `ApprovedSource` if present.
- **View entries in pool** — same as trailing tap.

### Section 2 — Rejected

`rejected_sources`. Row layout identical to Tracked minus the "built-in"
chip. Action sheet:
- **Move to Tracked** — delete from `rejected_sources`, insert into
  `ApprovedSource` (always — built-ins recovered this way still get an
  explicit ApprovedSource row, harmless since the runtime union treats
  the two the same).
- **View entries in pool**.

### Section 3 — Untracked

Packages that have at least one `captured_notifications` row but are not
in `PERMISSIVE_PACKAGES`, `ApprovedSource`, or `rejected_sources`. This
section is the discovery surface for new finance apps. Action sheet:
- **Move to Tracked** — insert into `ApprovedSource`.
- **Move to Rejected** — insert into `rejected_sources`, soft-noise the
  existing pool entries.
- **View entries in pool**.

Section header for each section shows a count. Empty sections render as
"No packages." rather than collapsing — keeps the surface predictable.

---

## Item 7: Currency Review chip auto-hide

`HomeViewModel.buildState` adds a derived count:

```kotlin
val currencyReviewCount = needsCurrencyConfirmationFromAll(joined).size
```

`HomeUiState` carries `currencyReviewCount: Int`. `HomeScreen.kt:300-306`
guards the FilterChip:

```kotlin
if (state.currencyReviewCount > 0) {
    FilterChip(
        selected = filter == HomeFilter.CurrencyReview,
        onClick = { onFilterChange(HomeFilter.CurrencyReview) },
        label = { Text("Currency review (${state.currencyReviewCount})") },
    )
}
```

The label gains a count for parity with how `Pending` already shows
counts in similar surfaces.

If the user is *inside* the CurrencyReview filter when the count drops
to 0 (last row resolved), `HomeViewModel` snaps the filter back to `All`
in the same `flatMapLatest` block that today recomputes state.

No data migration. The chip simply stops appearing once the user clears
the existing stale rows by the normal means (assign currency, mark not-
a-transaction).

---

## Settings entry + count badge

In `SettingsScreen`, replacing the position currently held by the
removed `captureAllPackages` toggle:

```
Notification pool (12)            Review captured notifications that
                                  weren't auto-tracked.
Tracked apps                      Manage which apps create transactions
                                  automatically.
Notification rewrites             Per-app regex rules applied to
                                  notification text before parsing.
```

The `(12)` count comes from a `repository.observePoolPendingCount(): Flow<Int>`
exposed via `SettingsViewModel`. The number excludes entries from
rejected packages (same rule as the pool screen's default filter).

---

## Repository surface

New methods on `TransactionRepository` (or extracted into a new
`CapturePoolRepository` if the existing repo's surface keeps growing):

```kotlin
fun observePool(filter: PoolFilter): Flow<List<CapturedNotification>>
fun observePoolPendingCount(): Flow<Int>
suspend fun promotePoolEntry(id: Long, edit: PromoteEdit): Long /* new tx id */
suspend fun markPoolEntryNoise(id: Long)
suspend fun rejectPackage(packageName: String)
suspend fun unrejectPackage(packageName: String)  // moves to tracked
suspend fun trackPackage(packageName: String)
fun observeTrackedPackages(): Flow<List<TrackedPackageRow>>
fun observeRejectedPackages(): Flow<List<RejectedSource>>
fun observeUntrackedPackagesSeenInPool(): Flow<List<String>>
```

`PromoteEdit` is a small DTO carrying merchant + optional overrides on
amount/currency/occurredAt the user might tweak in the promote sheet.

---

## Wiring changes summary

- Delete `PermissiveExtractor` and its tests.
- Delete `CapturePrefs.captureAllPackages` + setter + Settings toggle UI.
- Move `sourceLabel` helper from old PermissiveExtractor to a shared
  `cy.txtracker.parsing.SourceLabels` (used by pool UI).
- New DAOs: `CapturedNotificationDao`, `RejectedSourceDao`.
- New screens: `PoolScreen`, `TrackedAppsScreen`, `PromotePoolEntrySheet`.
- New routes in `AppRoute`: `settings/pool`, `settings/pool?package=X`
  (pre-filtered), `settings/tracked-apps`.
- New worker: `PoolRetentionWorker` (daily, deletes rejected-source pool
  entries older than 30 days).
- `TxNotificationListener` flow simplified per the listener-flow section.
- `HomeViewModel` + `HomeUiState` gain `currencyReviewCount` + the
  snap-back-to-All-when-empty behavior.
- Migration v8 → v9 as specified.

---

## Testing strategy

- Unit: pool insertion when heuristic misses, no insertion when amount
  regex misses, no double-insertion when heuristic succeeds.
- Unit: promote flow inserts transactions row + flips disposition +
  upserts ApprovedSource.
- Unit: reject flow soft-noises existing pending entries + removes from
  ApprovedSource.
- Migration test (`TxDatabaseTest`): v8 row with `merchantRaw =
  "GX Bank (review)"` and a non-null `rawText` becomes a pool entry
  post-migration; transactions row is gone.
- Migration test: v8 row with `merchantRaw = "GX Bank (review)"` and a
  null `rawText` stays in transactions (defensive carve-out).
- Migration test: `rejected_sources` table created and queryable.
- UI smoke: pool screen renders day groups, action sheet shows three
  actions, promote opens edit sheet pre-filled.
- Retention worker: rejected-source PENDING entry older than 30 days is
  deleted; non-rejected PENDING entry of same age is kept; PROMOTED
  entry from rejected source of same age is kept.

---

## Open / deferred

- **Promote-to-merchant hinting.** When promoting, we could suggest the
  merchant from prior rows with the same package + similar amount. Skip
  for v1 — the user types it.
- **Bulk operations.** Multi-select promote / reject. Skip for v1.
- **Pool export.** CSV / JSON for diagnostics. Skip — backup export
  currently ignores pool entries, and that's fine.
- **Per-package retention override.** Some users might want longer
  retention for specific rejected packages. Skip — 30 days is the
  single knob.
