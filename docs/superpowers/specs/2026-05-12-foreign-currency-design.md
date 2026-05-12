# Foreign-currency support (trip-gated)

**Date:** 2026-05-12
**Scope:** Phases 1–3 of FUTURE.md item 1. Capture and store foreign-currency
transactions, surface them in a new top-level "Foreign" destination, and offer
per-currency CSV export. Phase 4 (push notifications for unknown currencies) is
deferred to the broader app-notifications work in FUTURE.md item 3.

---

## Problem

The heuristic and permissive extractors are anchored to `RM` / `MYR`. Wise,
GX Bank's foreign-card features, OCBC SG, and any non-Malaysian banking app
emit notifications in foreign currencies — GBP / USD / SGD / EUR / etc. Those
notifications either match the existing regex with the wrong currency (false
MYR-tagged rows) or fail to match entirely (lost transactions). The user has
no way to track travel spending in TxTracker today.

A naïve fix — always parse every currency — has a different problem: during
normal life in Malaysia, a promo notification like *"send 100 GBP to a friend
this week"* would create a phantom GBP transaction. Currency support must be
opt-in for the user's normal life and only active during periods when they
actually expect foreign spending.

## Goal

The user holds the app to its MYR-only behavior by default. When they travel
or start spending in a new currency, they open a "trip" — a time window for
that currency. During the trip, foreign-currency notifications are auto-tagged
to that currency and visible on a Foreign tab. Outside an active trip, a
foreign notification is still captured (no data loss) but parked in a
"Currency review" state with a home-screen banner offering to start a trip.
Opening a trip retroactively promotes every parked row in that currency.

The fallback path matters: if the user forgot to start a trip before a Wise
notification arrives, they can fix it from the edit sheet — pick the
correct currency, accept the trip-creation prompt, and every related parked
row promotes in one transaction.

## Non-goals

- **FX rate / MYR-equivalent calculations.** No conversion math. Foreign
  totals stay in their own currency. A consolidated "spent X MYR-equivalent
  this month" view is a future problem.
- **Per-source-app symbol defaults.** A single global default per ambiguous
  symbol (`$` → USD, `¥` → JPY). Per-app overrides (Wise's $ vs OCBC's $)
  deferred until real-world need.
- **POST_NOTIFICATIONS push** for unknown-currency detection. Lives with the
  broader app-sent push work (FUTURE.md item 3).
- **Multi-currency single-transaction parsing** (Wise currency-conversion
  notifications mentioning both sides). Too few real samples to design
  against; revisit when one lands.
- **Retroactive re-parse of existing rows.** Migration is additive. Old
  permissive `(review)` rows that might have been foreign in disguise stay as
  they are; the user can fix them via the new edit-sheet currency picker.

---

## Design

### Data model

Schema bump v3 → v4. Three changes:

**New entity `TrackedCurrency`:**

```kotlin
@Entity(tableName = "tracked_currencies")
data class TrackedCurrency(
    @PrimaryKey val code: String,      // "GBP", "USD", "SGD" — ISO 4217
    val displaySymbol: String,         // "£", "$", "S$"
    /** True when this row is the user's chosen interpretation of an
     *  ambiguous symbol. At most one row may have isDefaultForSymbol = true
     *  per symbol value. */
    val isDefaultForSymbol: Boolean,
    val addedAt: Instant,
)
```

**New entity `TripWindow`:**

```kotlin
@Entity(
    tableName = "trip_windows",
    indices = [Index("currency"), Index("startAt"), Index("endAt")],
)
data class TripWindow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val currency: String,              // soft FK to tracked_currencies.code
    val startAt: Instant,
    val endAt: Instant?,               // null = open-ended ("until I turn it off")
    val createdAt: Instant,
)
```

Multiple `TripWindow` rows per currency (each trip = one record). "Active for
currency X at instant T" = any `TripWindow` where `currency = X` and
`startAt <= T < (endAt ?: DISTANT_FUTURE)`.

**New column on `transactions`:**

```kotlin
val needsCurrencyConfirmation: Boolean = false
```

Default false for all existing rows (they're all `currency = "MYR"`, no
ambiguity). New rows in non-MYR currencies set this to true when no active
trip covers `occurredAt`.

The existing `transactions.currency` column already exists; until now it has
been hardcoded to `"MYR"`. From v4 onward it carries the real detected code.

**`computeDedupeKey` adds a `currency` parameter:**

```kotlin
fun computeDedupeKey(
    amountMinor: Long,
    merchantNormalized: String,
    occurredAt: Instant,
    currency: String,
): String
```

So a £20 and an SGD$20 in the same 5-minute bucket no longer hash-collide.
Existing rows keep their pre-migration keys (all MYR — semantically unchanged).

### Parsing layer

**Widened amount regex** in `HeuristicExtractor` and `PermissiveExtractor`.
One alternation covers three shapes. Decimals optional everywhere — the
out-verb gate continues to fence off "RM 5 voucher"-style noise.

```kotlin
private val AMOUNT = Regex(
    """(?:(?<prefix>RM|MYR|[£€¥₹₩₽฿$])\s*(?<amtA>\d{1,3}(?:,\d{3})*(?:\.\d+)?)|""" +
    """(?<amtB>\d{1,3}(?:,\d{3})*(?:\.\d+)?)\s*(?<suffix>[A-Z]{3}))""",
    RegexOption.IGNORE_CASE,
)
```

The two-arm structure prevents the suffix path from accidentally grabbing
`RM 12.50` and reading `RM` as merchant text.

**New `parsing/Currencies.kt`** owning the symbol/code lookup and
disambiguation:

```kotlin
object Currencies {
    val KNOWN_CODES: Set<String> = setOf(
        "MYR", "USD", "GBP", "EUR", "SGD", "AUD", "JPY", "CNY",
        "HKD", "NZD", "CAD", "THB", "IDR", "PHP", "VND", "INR", "KRW", "TWD",
        // … extend as real samples arrive
    )

    /** Unambiguous symbol → code. null = ambiguous (resolve via prefs). */
    val SYMBOL_TO_CODE: Map<String, String?> = mapOf(
        "RM" to "MYR", "£" to "GBP", "€" to "EUR", "₹" to "INR",
        "₩" to "KRW", "₽" to "RUB", "฿" to "THB",
        "$" to null,   // USD / SGD / AUD / HKD / NZD / CAD / …
        "¥" to null,   // JPY / CNY
    )

    /**
     * Resolution hierarchy:
     *   1. Explicit 3-letter code in KNOWN_CODES → use as-is.
     *   2. Unambiguous symbol → its mapped code.
     *   3. Ambiguous symbol → user's isDefaultForSymbol = true row, if any.
     *   4. Otherwise → "UNKNOWN".
     */
    fun resolve(
        prefixToken: String?,
        suffixToken: String?,
        symbolDefaults: Map<String, String>,
    ): String { /* … */ }
}
```

`symbolDefaults` is a one-shot read of `TrackedCurrencyDao.findDefaultsForSymbol()`
per parse — cheap.

**Possessive recipient pattern** for Wise's "in X's account" form, added to
`HeuristicExtractor.RECIPIENT_PATTERNS` first in the list so the trailing
`'s account` anchor matches before the generic `to <X>` pattern grabs an
errant "in":

```kotlin
Regex(
    """\bin\s+(?<merchant>[^\.\n,]+?)'s\s+account\b""",
    RegexOption.IGNORE_CASE,
)
```

**Out-verb gate** already covers `transfer` (added in a prior commit for
Wise's "Your transfer of … is now in X's account" form). No change needed.

### Ingest flow

`TxIngestor.ingest()` adds one branch after parsing, before insert:

```
resolvedCurrency = parsed.currency  // from extractor + Currencies.resolve

if (resolvedCurrency == "MYR"):
    needsCurrencyConfirmation = false   // existing path, unchanged

else:
    // Ensure a TrackedCurrency row exists. Auto-creation here just makes the
    // currency visible in Settings → Foreign currencies so the user can act
    // on it; it does NOT make the currency "active" anywhere.
    upsert TrackedCurrency(code = resolvedCurrency, displaySymbol = lookup,
                           isDefaultForSymbol = false, addedAt = now)
                           if not already present

    activeTrip = tripWindowDao.findActiveAt(resolvedCurrency, occurredAt)
    needsCurrencyConfirmation = (activeTrip == null)

Insert Transaction with currency = resolvedCurrency,
                       needsCurrencyConfirmation = computed value,
                       dedupeKey = computeDedupeKey(…, resolvedCurrency)
```

Cross-source dedupe (`findCrossMerchantDupe`) gains a `currency` filter so
GBP and SGD payments in the same bucket can't be considered the same payment.

### Repository methods

**`openTrip(currency, startAt, endAt, now)`** — creates a trip and
retroactively promotes all parked rows in its range:

```kotlin
suspend fun openTrip(
    currency: String,
    startAt: Instant,
    endAt: Instant?,
    now: Instant = Clock.System.now(),
): Long = database.withTransaction {
    val tripId = tripWindowDao.insert(
        TripWindow(currency = currency, startAt = startAt,
                   endAt = endAt, createdAt = now),
    )
    transactionDao.clearCurrencyConfirmationForRange(
        currency = currency,
        startAt = startAt,
        endAtExclusive = endAt ?: Instant.DISTANT_FUTURE,
    )
    tripId
}
```

**`setCurrency(txId, currency, now)`** — change a single row's currency.
Mirrors the existing `setMerchant` contract (returns `Boolean`, `false` on
dedupe-key collision, regenerates the dedupe key on success). Atomically,
inside the same DB transaction:

1. Updates `transactions.currency` to the new code.
2. Regenerates `notificationDedupeKey` against the new currency.
3. Looks up `tripWindowDao.findActiveAt(currency, occurredAt)` and sets
   `needsCurrencyConfirmation` to `(activeTrip == null)`. So picking a
   currency with an active trip auto-promotes the row; picking one without
   leaves it parked.

```kotlin
suspend fun setCurrency(txId: Long, currency: String): Boolean
```

The ViewModel calls `openTrip(...)` afterward when the user accepts the
trip-creation prompt for a parked row. That openTrip call promotes the
just-set row along with any other parked rows in the currency in range.

**`closeTrip(tripId, endAt)`** — set or update a trip's `endAt`. Used by
"End trip" in the trip-history UI. Does NOT re-flag previously-promoted
rows; once promoted, rows stay promoted (the trip is the artifact, not the
gate).

### UI

**Bottom navigation (structural change to `AppRoute.kt`).** Wrap the
existing `NavHost` in a `Scaffold` with a `NavigationBar`. Three
destinations: **Home**, **Foreign**, **Settings**. Bottom bar shows only on
these three top-level routes — `settings/categories`, `settings/merchants`,
etc. push above it (detected by `currentBackStackEntryAsState().destination.route`
against a set of top-level routes). Settings stops being a top-app-bar gear
on Home; it becomes a peer destination.

**Foreign tab (`ui/foreign/ForeignScreen.kt` + `ForeignViewModel.kt`).**
Observes transactions where `currency != "MYR" AND needsCurrencyConfirmation
= false`, grouped by `currency`. Per currency:

- Header: code + symbol + total + transaction count
- Per-category breakdown chip row (same component as Home)
- Transaction list (same row component as Home, same edit sheet)
- Sections collapsible

Empty state: *"No foreign transactions yet. Open Settings → Foreign currencies
to plan a trip, or wait for a foreign notification to arrive."*

**Currencies settings screen
(`ui/settings/currencies/CurrenciesScreen.kt`).** Reached from Settings.
Lists tracked currencies — both auto-added by ingest and user-added. Each
row: code, symbol, "Active trip until <date>" or "No active trip". Tap →
trip-history page with past + active trips and edit / end-trip controls.
Top-of-screen actions:

- **Start a trip** — pick one or more currencies + start date + end date
  (end optional for open-ended).
- **Add a currency** — picker over `Currencies.KNOWN_CODES` with a
  symbol-default toggle for `$` and `¥`.

**`EditTransactionSheet` modifications.** New row labeled "Currency"
between Merchant and Category. Clickable chip showing current currency. Tap
opens a bottom-sheet picker over MYR + all tracked currencies + "Other…"
(full code picker). Selecting a different currency:

- If `findActiveAt(currency, occurredAt)` returns a trip → row updates
  immediately, `needsCurrencyConfirmation` flips to false.
- Otherwise → trip-creation dialog with defaults: `startAt = occurredAt`,
  `endAt = occurredAt + 14 days`. User adjusts / confirms → `openTrip(...)`
  runs in one transaction and the row promotes along with any other parked
  rows in that currency in range.

**Home screen modifications.**

- New filter chip **Currency review** alongside the existing **Pending
  verification** chip. Surfaces all rows where `needsCurrencyConfirmation =
  true`, regardless of currency.
- Sticky top banner when any parked-currency rows exist for a currency that
  has no active trip: *"Detected GBP transactions outside a trip. Start
  tracking?"* Tap → trip-creation dialog pre-filled with that currency and
  date range spanning the earliest parked-row `occurredAt` to `+14 days`.
  Dismiss → banner hides until a new currency appears (per-currency
  dismissal state stored in `CurrencyPrefs`).

**`AddManualSheet` modifications.** Same Currency picker as the edit sheet,
default MYR. No trip prompt on manual entry — the user explicitly chose a
currency, respect it. (A manually-added GBP row still goes through the
trip-active check: parked if no active trip covers `occurredAt`.)

### Export

CSV export becomes a chooser bottom sheet from Settings → Export to CSV:

- **Export MYR** (current behavior — file: `txtracker-MYR-<YYYY-MM>.csv`)
- **Export `<currency>`** for each tracked currency with rows
- **Export all currencies** — produces one CSV per currency, zipped together
  into `txtracker-export-<YYYY-MM>.zip` via `java.util.zip.ZipOutputStream`

`CsvExporter` gains a `currency: String` parameter and a currency filter on
its repository query. The zip path is a thin wrapper iterating over tracked
currencies with rows. No mixed-currency CSVs — the math stays
un-mixable by construction.

### Backup

`Backup.CURRENT_VERSION` bumps 5 → 6. New top-level keys:

```json
"trackedCurrencies": [
    { "code": "GBP", "displaySymbol": "£", "isDefaultForSymbol": false, "addedAt": "…" },
    { "code": "USD", "displaySymbol": "$",  "isDefaultForSymbol": true,  "addedAt": "…" }
],
"tripWindows": [
    { "currency": "GBP", "startAt": "…", "endAt": "…",  "createdAt": "…" },
    { "currency": "SGD", "startAt": "…", "endAt": null, "createdAt": "…" }
]
```

Transactions already carry `currency`. The per-transaction record adds
`needsCurrencyConfirmation`.

**Merge rules in `applyBackup`** (matching existing patterns):

- `trackedCurrencies` — keyed on `code`. Insert-or-ignore; local symbol /
  default flags win on conflict.
- `tripWindows` — keyed on `(currency, startAt)`. Insert-or-ignore. Trips
  are append-only; never update from backup.
- Transactions — existing unique-on-`notificationDedupeKey` IGNORE behavior
  unchanged. `needsCurrencyConfirmation` round-trips per row.

**Forward compatibility for older backups (v1–v5):** missing
`trackedCurrencies` / `tripWindows` default to empty; missing
`needsCurrencyConfirmation` defaults to false on every transaction.

Cloud sync is unaffected — it pipes the v6 backup through the existing
`BackupExporter` / `BackupImporter` / `CloudSyncWorker` path. The local
rollback snapshot path likewise unchanged.

### Migration

Real `Migration(3, 4)` in `DatabaseModule`:

```sql
CREATE TABLE tracked_currencies (
    code TEXT NOT NULL PRIMARY KEY,
    displaySymbol TEXT NOT NULL,
    isDefaultForSymbol INTEGER NOT NULL DEFAULT 0,
    addedAt INTEGER NOT NULL
);

CREATE TABLE trip_windows (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    currency TEXT NOT NULL,
    startAt INTEGER NOT NULL,
    endAt INTEGER,
    createdAt INTEGER NOT NULL
);
CREATE INDEX idx_trip_windows_currency ON trip_windows(currency);
CREATE INDEX idx_trip_windows_startAt  ON trip_windows(startAt);
CREATE INDEX idx_trip_windows_endAt    ON trip_windows(endAt);

ALTER TABLE transactions
    ADD COLUMN needsCurrencyConfirmation INTEGER NOT NULL DEFAULT 0;
```

No data backfill. Existing rows stay `currency = "MYR"`,
`needsCurrencyConfirmation = 0`. New tables start empty.

---

## Testing

### Unit (JVM)

- `HeuristicExtractorTest` — new cases per amount shape (prefix code, suffix
  code, prefix symbol), decimals optional in each shape, Wise possessive
  recipient (`in X's account`), Wise sample text (`Your transfer of 1 MYR is
  now in <Person>'s account`).
- `CurrenciesTest` (new) — `resolve()` for every disambiguation branch:
  explicit code, unambiguous symbol, ambiguous symbol with default,
  ambiguous symbol without default → `UNKNOWN`.
- `PermissiveExtractorTest` — coverage for the same widened shapes.

### Instrumentation (compile-only, not auto-run)

- `TransactionRepositoryTest`
  - `openTrip` flips `needsCurrencyConfirmation` for rows in range
  - `openTrip` leaves rows outside its window untouched
  - `setCurrency` MYR → GBP with no active trip leaves the row parked
  - `setCurrency` regenerates dedupe key (mirrors existing `setMerchant`)
  - `setCurrency` returns false on dedupe-key collision
  - Backup v6 round-trip preserves `trackedCurrencies` + `tripWindows` +
    per-row `needsCurrencyConfirmation`
  - Reading a v5 backup with missing new fields defaults cleanly
- `TxDatabaseTest` — Migration(3, 4) on a real seeded v3 DB: tables exist,
  indices present, existing rows preserved, new column defaults to 0.

### Manual smoke (on device)

- Wise MYR P2P notification parses with the new heuristic.
- Manual entry then change-currency to GBP with no trip → row lands in
  Currency review, banner appears.
- Accepting the banner's trip dialog promotes the row + any other parked
  GBP rows into the Foreign tab.
- Per-currency CSV export produces correctly-scoped files.

---

## Files most likely to change

**New:**
- `data/TrackedCurrencyDao.kt`
- `data/TripWindowDao.kt`
- `parsing/Currencies.kt`
- `ui/foreign/ForeignScreen.kt`, `ForeignViewModel.kt`
- `ui/settings/currencies/CurrenciesScreen.kt`, `CurrenciesViewModel.kt`,
  trip-history sub-screen
- `ui/edit/CurrencyPicker.kt` (shared between edit + manual sheets)
- `service/CurrencyPrefs.kt` (per-currency banner-dismissal state)

**Modified:**
- `data/Entities.kt` (new entities, `needsCurrencyConfirmation` column)
- `data/TxDatabase.kt` (schema bump v3 → v4, new DAOs)
- `di/DatabaseModule.kt` (Migration(3, 4), DAO bindings)
- `data/TransactionDao.kt` (`clearCurrencyConfirmationForRange`,
  currency-filtered queries)
- `data/TransactionRepository.kt` (`openTrip`, `closeTrip`, `setCurrency`,
  `applyBackup` for v6)
- `data/MerchantNormalizer.kt` — unchanged in scope; the Malaysian-suffix
  stripping is safe for foreign merchants (foreign notifications don't
  contain `SDN BHD`)
- `parsing/HeuristicExtractor.kt` (widened AMOUNT, possessive recipient)
- `parsing/PermissiveExtractor.kt` (same widening)
- `parsing/NotificationParser.kt` (currency resolution wiring)
- `service/TxIngestor.kt` (trip-active check + parked-flag assignment)
- `ui/AppRoute.kt` (Scaffold + NavigationBar wrapping the NavHost)
- `ui/home/HomeScreen.kt`, `HomeViewModel.kt`, `HomeUiState.kt` (Currency
  review filter, banner)
- `ui/edit/EditTransactionSheet.kt`, `EditTransactionViewModel.kt` (currency
  picker, trip prompt)
- `ui/manual/AddManualSheet.kt`, `AddManualViewModel.kt` (currency picker)
- `ui/settings/SettingsScreen.kt` (route entry for Currencies, route entry
  becomes nav-aware), `SettingsViewModel.kt`
- `export/CsvExporter.kt` (currency parameter), `export/Backup.kt` (v6
  fields), `export/BackupExporter.kt`, `export/BackupImporter.kt`

---

## Phased implementation

The spec covers Phases 1–3 from FUTURE.md item 1. Order of work
(implementation-plan input):

1. **Schema + data model.** Migration, entities, DAOs, `computeDedupeKey`
   currency arg. Unit tests + migration test.
2. **Parsing widening.** AMOUNT regex, `Currencies.kt`, possessive
   recipient. Heuristic + permissive tests.
3. **Ingest wiring.** Trip lookup, parked-flag, auto-create
   `TrackedCurrency`. Cross-source dedupe currency filter.
4. **Repository methods.** `openTrip`, `closeTrip`, `setCurrency`.
   Instrumentation tests.
5. **Backup v6.** Round-trip new entities + new column. Forward-compat for
   v5 backups.
6. **Bottom navigation + Foreign tab.** Top-level nav restructure first
   (verify everything still works MYR-only), then `ForeignScreen`.
7. **Edit sheet + manual sheet currency pickers + trip prompt.** The
   reactive trip-creation path.
8. **Home banner + Currency review filter.** The discovery path.
9. **Currencies settings screen + trip history.** The proactive trip-start
   path.
10. **CSV chooser + per-currency export + zip-all.**

Each step lands in its own commit (or small commit cluster) so review and
revert are localized.

---

## Open questions and follow-ups

- **Pre-population of `Currencies.KNOWN_CODES`** — start with the
  ISO-4217 codes most likely to appear (listed in the design). Extend as
  real Wise / GX Bank / OCBC samples arrive.
- **End-of-trip prompt** — when an `endAt` passes, should the app prompt
  the user *"Trip to GBP ended — review captures?"*? Deferred for now; the
  user can always end a trip manually from Currencies settings.
- **Display-symbol auto-fill** — when a `TrackedCurrency` is auto-created
  during ingest, pick the display symbol from a static lookup (USD → `$`,
  GBP → `£`, etc.). User can edit it later.
- **FX rate / MYR-equivalent.** Out of scope for this spec. A natural
  successor when the basic flow has earned its keep.
