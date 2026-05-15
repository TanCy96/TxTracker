# Future Improvements

Local-only roadmap, not committed. Each entry: motivation, design, implementation
outline, files most likely to change, rough effort. Replace / extend as new ideas
land in real use.

---

## 1. Foreign-currency support (Wise-driven) — ✅ DONE (2026-05-12) for phases 1–3; phase 4 deferred

Landed across 23 commits on `feature/foreign-currency`. Schema bumped v5 → v6
with two new entities (`TrackedCurrency`, `TripWindow`) plus a
`needsCurrencyConfirmation` column on `transactions`. The heuristic and
permissive extractors now share a widened three-shape AMOUNT regex (`RM/MYR<amt>`,
`<amt> <CODE>`, `<SYMBOL><amt>`) with decimals optional and `[A-Z]{3}`
case-sensitive inside `IGNORE_CASE` to prevent false matches on
digits-then-lowercase shapes (e.g. "Mastercard 1868 has"). A new
`parsing/Currencies.kt` owns code/symbol lookup and the resolution hierarchy:
explicit code > unambiguous symbol > user-default for ambiguous `$`/`¥` >
`UNKNOWN`. Wise's possessive recipient form ("in X's account") is matched
first in `RECIPIENT_PATTERNS`. `computeDedupeKey` now includes currency so
£20 and SGD$20 in the same bucket no longer hash-collide.

**Trip-gated UX** (the major design departure from the original sketch). The
"optional expiry per currency" idea evolved into first-class `TripWindow`
records: each trip is a `(currency, startAt, endAt?)` row, and a currency is
"active at instant T" iff any trip covers T. Captures outside an active trip
land with `needsCurrencyConfirmation = true` — hidden from the Foreign tab,
surfaced via a new "Currency review" Home filter, and announced by a
dismissible top banner that opens a `TripCreationDialog` when tapped.
`TransactionRepository.openTrip` atomically inserts the trip and clears the
flag on all rows in the window, so confirming a trip retroactively promotes
every parked row in range. `setCurrency` from the edit sheet mirrors that
flow: picking a non-MYR currency with no active trip prompts trip creation;
picking one with an active trip promotes the row immediately. Both edit and
manual sheets share `ui/currency/CurrencyPickerSheet` + `AddCurrencyDialog`.

**Foreign UI** is a top-level destination behind a new bottom `NavigationBar`
(Home / Foreign / Settings) — chosen over the "quiet pill" alternative so the
nav pattern scales to a future Insights tab. The Foreign screen groups rows
by currency with per-currency totals and category breakdowns; the bar hides
on Settings sub-routes.

**Currencies settings screen** lists tracked currencies with active-trip
status and a "Start a trip" / "Add a currency" pair. Tapping a row opens a
trip-history sub-screen with an "End now" action on active trips.

**CSV export** became a chooser sheet: Export MYR / Export `<currency>` per
tracked currency / Export all currencies (zip via `java.util.zip`). No
mixed-currency CSVs by construction. Filenames carry the currency code.

**Backup v6** round-trips `trackedCurrencies` + `tripWindows` and the
per-transaction `needsCurrencyConfirmation`. Reading older v5 backups
defaults the new fields cleanly; the importer accepts versions 5..6. Cloud
sync needs no changes — it pipes v6 backups through the existing pipeline.

Spec: `docs/superpowers/specs/2026-05-12-foreign-currency-design.md`
Plan: `docs/superpowers/plans/2026-05-12-foreign-currency.md`

**Wise samples seen so far** (more would help — the parser is now generic,
but real samples sharpen confidence):
- P2P transfer (MYR test): `Your transfer of 1 MYR is now in <Person>'s account`

**Still wants real-world validation:**
- Wise foreign-currency P2P (`100 GBP`-style suffix form on a real device).
- Wise card spend at a foreign POS.
- Wise currency-conversion notifications (do they mention both currencies on
  one line? — multi-currency single-row capture is explicit non-goal for v1).
- GX Bank foreign-card notifications.
- v5 → v6 migration smoke test on a device with actual transaction data
  (instrumentation migration test only covered the empty-data case).

**Deferred follow-ups:**
- **Phase 4: POST_NOTIFICATIONS push for unknown-currency detection** —
  pairs naturally with item 3 (App-sent push notifications) once that lands.
  Currently the in-app banner handles the same role.
- **Per-source-app symbol defaults** (Wise's `$` → USD vs OCBC's `$` → SGD).
  Single global default per ambiguous symbol shipped; per-app overrides wait
  for real-world need.
- **FX rate / MYR-equivalent reporting.** Foreign totals stay in their own
  currency. No conversion math. A consolidated "spent X MYR-equivalent this
  month" view is a future problem.
- **End-of-trip prompt.** When a trip's `endAt` passes, the app doesn't
  prompt the user to review. They can end a trip manually from Currencies
  settings.
- **`AddCurrencyDialog` from a fresh trip-start with no codes yet.** The
  current proactive "Start a trip" path opens the code picker first; this
  works but a more curated "common-trips" list (SGD for Singapore, GBP for
  UK) could be friendlier.

**Known minor UX rough edges** (post-merge polish, not blockers):
- The TripCreationDialog uses a plain text input for dates (`YYYY-MM-DD`).
  Replacing with a proper Material 3 DatePicker is a half-hour fix once
  the basic flow is validated on device.
- `ForeignRoute` renders inline minimal transaction rows because the home
  screen's `TransactionRow` is `private`. Hoisting visibility (or extracting
  the row composable into `ui/components/`) would unify rendering.

---

## 2. Backup format — include merchant notes — ✅ DONE (2026-05-11)

Landed in commit `3f7eda8` as part of the Backup v4 bump (which
also extends v3's `userFacingSources` and `approvedSources` round-trips). Merchant
notes use the later-`updatedAt`-wins merge rule, mirroring the existing
merchant-mapping merges in `applyBackup`.

---

## 3. App-sent push notifications — ✅ DONE (2026-05-15)

Landed on `feature/notification` (merged via `cc388c9`) and polished on
`feature/foreign-currency` through `eb58ef9`. All three sub-flows shipped:

- **3a. Permission + channels** — `NotificationChannels` registers
  `txtracker.pending` / `txtracker.foreign` / `txtracker.summary`. Android 13+
  `POST_NOTIFICATIONS` request goes through `NotificationPermissionBridge` +
  MainActivity launcher (contextual, not on cold start). Settings →
  "Notifications" hosts the per-flow toggles, cadence dropdown, and a
  TimePicker for the summary; an OS-disabled banner surfaces when the system
  permission is off.
- **3b. Pending-row reminder** — `PendingReminderWorker` (daily WorkManager
  job) fires on `needsVerification = true` rows older than the threshold,
  auto-cancels at zero, and suppresses re-fires within 12h of dismissal via
  `PendingDismissReceiver`. Deep-link routes through `DeeplinkBus` into Home
  with the Pending filter pre-selected.
- **3c. Foreign-currency push** — `ForeignCurrencyWorker` fires the push
  escalation of item 1's banner flow; dismissal handled by
  `ForeignDismissReceiver`. Deep-link opens the trip-creation flow.
- **3d. Summary** — `SummaryWorker` with `SummarySchedule` covers
  Daily/Weekly/Monthly cadences using Malaysia-timezone math
  (`rangeFor` + `millisUntilNextFiring`). Reminders are evening-anchored.

`NotificationScheduler` reconciles WorkManager against `NotificationPrefs` on
app start and pref changes. Plan:
`docs/superpowers/plans/2026-05-14-push-notifications.md`.

**Deferred follow-ups:** see ISSUE.md #2 (daily summary timing inconsistency
observed on real device — needs debugging).

---

## 4. Charts and statistical UI

**Why.** The home screen shows totals and a filter row but no visual
distribution of spending. For someone trying to understand where money actually
goes, a pie chart of category breakdown reads in a second versus reading a
list of chip totals. A trend line of month-over-month spending answers "am I
spending more this year?" without needing to flip months manually.

**Design.**

### 4a. Library

Use **Vico** (`com.patrykandpatrick.vico:compose-m3`) — Compose-native, MIT,
actively maintained, supports the chart types we need (column / line / pie),
plays well with Material3 theming, no third-party dependencies beyond Compose.
Adds ~150 KB to the APK. Alternative: hand-roll using `Canvas` for the simpler
charts (pie, bar). Hand-rolling pie is ~50 LOC and avoids the dependency, but
once we want a line chart with axes and gridlines the library pays off.

Recommendation: start with Vico. If APK size becomes a concern later (it
won't for v1), we can revisit.

### 4b. Charts to ship

- **Category pie chart** for the visible month — same data as the existing
  per-category breakdown chip row, rendered as a pie. Each slice tappable to
  filter the home screen to that category (re-uses `HomeFilter.Category`).
  Shown above or alongside the chip row.
- **Daily spending bar chart** for the visible month — one bar per day,
  height = total spend that day, color-segmented by category (stacked bar).
  Quickly shows spike days and quiet days.
- **Month-over-month line chart** — last 6 months, total spend per month as a
  line. Visible only on a dedicated "Insights" screen (see below) since it
  spans more than the visible month.
- **Per-category trend** — same as month-over-month but for one selected
  category. Useful for "is my Food spending creeping up?".

### 4c. Where in the UI

Two options, pick one:

- **Inline on the home screen** — pie above the transaction list, always
  visible. Simplest; minor information density increase. Daily bar chart only
  shown when there's a meaningful number of transactions.
- **Dedicated "Insights" screen** — new top-level destination (alongside Home
  / Foreign / Settings) that hosts all charts. Home stays unchanged. Cleaner
  separation; more taps to access.

The dedicated screen scales better long-term (room for many chart types
without crowding the home view) and pairs well with the foreign-currency tab
(same nav pattern). Recommended.

### 4d. Time-range selector

Most charts default to "current month" matching the home screen. The Insights
screen adds a small range chip row: This month / Last month / Last 3 months /
Last 6 months / This year / All time. Driven by the same `YearMonth` selection
plumbing or a new `DateRange` value type as needed.

**Files.**

- `gradle/libs.versions.toml`, `app/build.gradle.kts` — Vico dependency
- New `ui/insights/InsightsScreen.kt`, `InsightsViewModel.kt`
- `ui/insights/CategoryPieChart.kt`, `DailyBarChart.kt`, `TrendLineChart.kt`
  (each a small composable wrapping a Vico chart)
- `ui/AppRoute.kt` — route entry for Insights
- `data/TransactionDao.kt`, `TransactionRepository.kt` — new aggregation
  queries: `observeMonthlyTotals(start, end)`, `observeDailyTotals(start,
  end)`, `observeCategoryTotalsForRange(start, end)`

**Effort.** Library wiring + first chart (category pie inline or on Insights):
~half a day. Each additional chart type: 1–2 hours. Time-range selector and
its wiring: ~half a day. Total ~2 days for the full Insights screen with all
four chart types.

---

## 5. Cloud sync — ✅ DONE (2026-05-11)

Landed across 17 commits in 2026-05-11. Google Drive AppData via
`play-services-auth`. Backup v5 includes transactions with a configurable
year-month floor (`transactionCutoff`). Trigger mechanic is Room's
`InvalidationTracker` → 15-minute trailing-edge debounced WorkManager unique
job (REPLACE policy) → `CloudSyncWorker` → `DriveClient` upload to a single
canonical `txtracker-backup.json` in AppData. UI lives in a new "Cloud sync"
section in Settings with sign-in, sync-now, pause toggle, cutoff picker,
restore-from-cloud, and sign-out (with optional cloud-backup deletion).

Local-first safety: `BackupExporter.saveLocalRollbackSnapshot(name)` writes a
no-cutoff snapshot to `cacheDir/exports/` before any cloud-driven restore or
cutoff change — the user reverts via the existing "Restore from backup"
Settings row. Transactions are local-only beyond the cloud copy (cloud
corruption can't lose them); the cloud carries only learning state +
transactions filtered by the cutoff.

Spec: `docs/superpowers/specs/2026-05-11-cloud-sync-design.md`
Plan: `docs/superpowers/plans/2026-05-11-cloud-sync.md`

**One-time OAuth client setup (manual, your side):**
1. Open Google Cloud Console, create/reuse a project.
2. Enable Drive API.
3. Credentials → Create OAuth 2.0 Client ID, type **Android**.
4. Package: `cy.txtracker.debug` (debug build's applicationId).
5. SHA-1: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`.
6. Repeat for `cy.txtracker` if you build a release variant.
7. Drive AppData is non-sensitive — no app-verification step needed.

**Deferred follow-ups:**
- Multi-device active sync (pull-on-foreground, periodic downloads).
- Snapshot rotation in Drive (keep last N versions vs single canonical file).
- gzip compression for backups with many transactions.
- End-to-end encryption of the cloud copy.
- Multi-account switching.
- "Show me what's in the cloud" diagnostics screen.
- Selective restore (only categories / only mappings / etc.).

---

## 6. Heuristic-by-group sub-categorization (Grab and beyond) — ✅ DONE (2026-05-11)

The full pipeline rework landed across these commits:

- `07543af` Heuristic widened to catch the Google Wallet card-spend shape (no
  verb required — extracts merchant from the head of `<MERCHANT> RM<amt> with
  <card> ••<last4>`).
- `0c3634a` Four strict parsers retired (`GoogleWalletParser`, `TouchNGoParser`,
  `GrabParser`, `CIMBParser`). Package constants moved to a new
  `parsing/SourcePackages.kt`. `ParsingModule.kt` deleted. Listener no longer
  has a strict-parser dispatch path.
- `83f15f5` `CAPTURE_ALL_PACKAGES` promoted from a hardcoded debug constant to
  a user-controllable Settings toggle (default off). `extractAmountOnly`
  collapsed into `extract(bypassAllowlist)`.
- `984aa92` Grab sub-product detection from booking ID prefix: `A-...` →
  `"Grab Car"`, `<digits>-...` → `"Grab Food"`, anything else → `"GRAB"`.

The pipeline today is heuristic → permissive (with optional allowlist bypass).
Per-app overrides live in `PermissiveExtractor.merchantFor()`. The TnG and CIMB
notifications are caught by the heuristic's verb+recipient flow; Grab is caught
by the permissive layer with the booking-ID-based label.

Future follow-up: see item 8 (learn Grab sub-product labels from user edits).

---

## 7. Cross-source dedup follow-ups

The v1 cross-source dedup design (`docs/superpowers/specs/2026-05-11-cross-source-dedup-design.md`) intentionally defers two pieces. Land them after v1 has had real usage.

### 7a. "Keep both, mark them linked" mode

User opt-in setting. When two notifications match the cross-source dedup
window, instead of dropping one, insert both rows and tag them as
`linkedPaymentId = <shared UUID>`. Home-screen totals dedupe by linked-payment
group so the math stays correct; the UI surfaces both rows with a small chip
("via GWallet") and lets the user collapse them visually if desired.

Useful for users who reconcile against credit-card statements (the card-layer
row carries the card-last-4 info that the user-facing row drops). Disabled by
default since most users don't care.

Schema: new nullable `linkedPaymentId: String?` column on `transactions`,
plus a `groupBy linkedPaymentId` clause in total queries.

### 7b. Backfill of historical duplicates

Settings → "Notification priority" → "Consolidate past duplicates" button.
Scans existing rows: every (amount, 5-min-bucket, currency) group with 2+
rows from different packages gets a preview entry ("Keep N rows, remove N
rows. Tier 1 labels will replace card-layer labels."). User reviews → confirms
→ applies the same promotion mechanic as the live path. Idempotent.

Special handling for pairs where both rows have user-set categories that
differ — surface as "⚠ N pairs have category conflicts" and let the user
pick which category wins per pair before applying.

Files: `ui/settings/sources/ConsolidateScreen.kt`,
`data/TransactionRepository.consolidateHistoricalDupes()`. ~half a day once
the live path is shipped and validated.

---

## 8. Grab sub-product learning from user edits

**Why.** The hardcoded booking-ID-prefix heuristic in
`PermissiveExtractor.grabMerchantFrom()` (item 6 / commit `984aa92`) covers
Grab Car (`A-...`) and Grab Food (`<digits>-...`) from the two samples
available today. Any other Grab product (Grab Mart, Grab Express,
Grab Pay top-up, …) falls back to `"GRAB"` until the user re-labels it in the
edit sheet. If the user repeatedly fixes the same kind of row to the same label,
the app could learn the mapping and auto-apply it next time — eliminating the
manual fix and avoiding the need to hand-add every new Grab product variant.

**Mechanism (sketch).**

New entity:

```kotlin
@Entity(tableName = "booking_id_rules")
data class BookingIdRule(
    @PrimaryKey val prefix: String,
    val productLabel: String,
    val sourceApp: String,
    val learnedAt: Instant,
)
```

- **Learn on save.** When the user edits a Grab row's `merchantRaw` in the
  edit sheet, extract the booking ID from `rawText`, compute a smart prefix
  (probably "everything up to and including the first dash" — `A-`, `00193-`,
  …), and upsert the rule. Either implicitly (any non-trivial edit teaches a
  rule) or behind an explicit "Remember as X" affordance — UX choice TBD.
- **Apply on ingest.** `grabMerchantFrom(text)` checks the rules table first;
  if any rule's prefix matches the booking ID, return its label. Otherwise
  fall through to the current hardcoded heuristic, otherwise `"GRAB"`.

**Open design questions** (deserve their own brainstorm before implementation):

- **Prefix granularity.** `00193` matches one Food booking; `00` matches every
  digit-prefix booking; pick a heuristic or expose it in UI.
- **Implicit vs. explicit learn.** Implicit risks accidental rules from typo
  fixes; explicit costs one tap.
- **Rule conflict resolution.** Longest matching prefix wins, or newest, or
  oldest?
- **Backup integration.** Bump `Backup.CURRENT_VERSION` to 3 and round-trip
  rules, or keep rules device-local.

**Skip until.** Real-world misclassifications start happening — i.e., you
encounter a Grab product that the hardcoded heuristic misses *and* you find
yourself re-labeling it more than once. Until then, manually editing the
occasional Grab Mart row is cheaper than building the learning system.

**Files most likely to touch:**

- `data/Entities.kt` (new `BookingIdRule`), `data/TxDatabase.kt` (schema bump
  v4 → v5), new `data/BookingIdRuleDao.kt`, `di/DatabaseModule.kt`
- `parsing/PermissiveExtractor.kt` (rule lookup before the hardcoded prefix
  check)
- `data/TransactionRepository.kt` (learn-on-save in the edit-sheet save path)
- `ui/edit/EditTransactionViewModel.kt` (the trigger for learning)
- Possibly `export/Backup.kt` + `applyBackup` if rules round-trip

---

## Suggested order

1. **Address ISSUE.md before more roadmap work** — see ISSUE.md. #1 (backup
   data loss) and #3 (CIMB amount parsing) are data-integrity bugs; #2 is a
   regression in the just-shipped notification work. Insights/charts on top
   of suspect data is wasted effort.
2. **Capture real foreign-currency notification samples** for Wise card
   spend, currency conversion, Wise foreign-currency P2P, and GX Bank —
   validates the shipped phase 1–3 work on real data.
3. **Inline category pie chart** (item 4, first chunk only — pie above the
   transaction list, library wiring) — a half-day visual win that lifts the
   home screen.
4. **Full Insights screen** (item 4 remaining — daily bar, trend lines, time-
   range selector). Build out once the inline pie has earned its keep.
5. **Grab sub-product learning** (item 8) — only after you've felt the
   hardcoded heuristic miss in practice. Until then, hand-editing the
   occasional Grab Mart row is cheaper than building the learning system.
