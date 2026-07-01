# Trip-scoped categories — design

**Date:** 2026-07-01
**Status:** Approved for planning

## Goal

Let each foreign-currency **trip** own its own, fully independent set of spending
categories. A trip's categories are invisible to Home and to other trips. Home keeps its
existing global categories and behaviour unchanged.

## Requirements (agreed)

1. **Fully separate per trip.** Each trip has its own category list, unrelated to Home's
   global categories and to other trips'. A trip's categories never appear on Home or on
   another trip.
2. **Built-in travel template.** Starting a new trip auto-seeds a fixed travel template
   (editable per trip afterwards).
3. **Manual categorization inside trips.** Trip transactions start uncategorized; the user
   assigns from the trip's own categories. No auto-categorization runs against trip
   categories. Home's auto-categorization (merchant mappings + keyword patterns) is
   untouched.

## Chosen approach

**Approach A — add a nullable `tripId` to the existing `Category` table.**

`tripId = NULL` means a global/Home category (today's behaviour); `tripId = <trip id>` means
the category is owned by that trip. Every category — global or trip — remains a row in the
same `categories` table, so the existing `Transaction.categoryId` foreign key, the category
DAO, and the category-management UI keep working for both kinds. This is the smallest schema
change (one nullable column + an index rework) and preserves the single-FK
transaction→category model.

Rejected alternatives:
- **Separate `trip_categories` table** — forces `Transaction` to reference two category
  tables (second nullable FK or polymorphic reference), rippling into every
  transaction↔category join (Home, Foreign, edit sheet, breakdowns, export, backup).
  Roughly double the code for no benefit here.
- **Junction/tagging (shared categories, per-trip subset)** — this is the "pick a subset"
  model, explicitly rejected in favour of full separation.

## Data model & migration

### `Category` entity change

Add one column:

```kotlin
val tripId: Long? = null   // null = global/Home category; else owned by that trip
```

- New foreign key `Category.tripId → TripWindow.id`, `onDelete = CASCADE`.
- Non-unique index on `tripId` to support per-trip queries.

### Uniqueness moves to the app layer

Today `name` is globally unique via a DB index. With trip categories, the same name (e.g.
"Food") must be allowed in Home **and** in each trip. A plain composite `(name, tripId)`
unique index would **not** protect Home's global names, because SQLite treats `NULL`s as
distinct in unique indexes (multiple `(name, NULL)` rows would be permitted). Room's `@Index`
cannot express a partial/filtered unique index.

Therefore:
- Drop the old unique index on `name`.
- Enforce **"name unique within its scope"** in the repository on add/rename: unique among
  global categories (`tripId IS NULL`), or unique within a given trip (`tripId = :tripId`).
  This replaces the current reliance on `OnConflictStrategy.ABORT`.

### Migration 15 → 16

- Recreate the `categories` table with the new `tripId` column + foreign key (SQLite cannot
  add a foreign key via `ALTER TABLE`), copying existing rows with `tripId = NULL` so all
  current categories stay global — **Home is unchanged.** Row ids are preserved, so existing
  `transactions.categoryId` references remain valid.
- Drop the old `name` unique index; add the `tripId` index.
- Seed the travel template (below) for every `TripWindow` that already exists.
- Clear `categoryId` on existing **non-MYR** transactions (see "Existing data").
- `exportSchema = true` is on, so the migration must produce a schema matching the updated
  entity annotations (validated by the generated schema JSON).

### Query surface

- Home / global: `observeGlobalCategories()` → `WHERE tripId IS NULL`. Home stops seeing
  trip categories.
- Foreign: `observeCategoriesForTrip(tripId)` → `WHERE tripId = :tripId`.
- `CategorizationEngine` considers only global categories, so trip transactions are never
  auto-assigned (matches requirement 3).

## Template seeding

- Define a built-in **travel template**, separate from Home's everyday defaults
  (`DefaultCategories`). Proposed set (distinct colours, **no** keyword patterns):

  `Accommodation, Food & Drink, Transport, Attractions, Shopping, Groceries, Fees & Cash, Other`

- Seeding runs inside `openTrip`: immediately after the `TripWindow` is inserted, insert the
  template rows with `tripId = newTripId`, `isCustom = false`, sequential `sortOrder` — all
  within the existing `database.withTransaction`, so trip creation stays atomic.
- The 15→16 migration backfills the same template for every pre-existing trip.

## Foreign tab: display + per-trip management

- `ForeignViewModel` swaps `observeAllCategories()` for `observeCategoriesForTrip(trip.id)`.
  The breakdown chart, filter chips, and transaction→category join now use the trip's own
  categories. Switching trips is safe — `snapStaleForeignCategoryToAll` already resets a
  filter whose category isn't in the current set.
- **Per-trip category management** reuses the existing `CategoriesScreen` /
  `CategoriesViewModel` pattern, scoped to the trip, reached from the Foreign tab (a "Manage
  categories" action on the current trip). Add/edit/delete/reorder all stamp `tripId`. The
  "learned/auto" count chips are hidden for trips (no keyword patterns, manual-only) — a
  trimmed variant of the screen. The trip-scoped ViewModel takes a `tripId`.

## Edit / assign picker

- Editing a foreign transaction shows **that trip's** categories in the picker. The
  transaction's trip is resolved from its currency + `occurredAt` (the trip window it falls
  in — `EditTransactionViewModel` already exposes `findActiveTrip`). MYR/Home transactions
  keep using global categories exactly as today.
- **Edge case — parked foreign transactions:** a row with `needsCurrencyConfirmation = true`
  is not yet inside any trip (it lives in Home's "Currency review"). It has no trip, so no
  trip categories to assign; it stays uncategorized until a trip covers it, after which it
  can be categorized on the Foreign tab. The picker for such a row offers nothing assignable
  (or shows a hint to open a trip).

## Existing data & backup

- **Existing foreign (non-MYR) transactions** may currently carry a *global* categoryId (the
  Foreign tab offers global categories today). **Decision (default): clear `categoryId` on
  non-MYR transactions in the 15→16 migration**, so trips start clean against their travel
  template. Rationale: prior foreign categorizations were against Home's everyday categories,
  not the travel categories the user actually wants. _Reversible: if preferred, leave them
  as-is — no data loss, but a trip transaction may display a Home category not in the trip's
  list (the filter chip self-heals via snap-back; the breakdown would still show it)._
- **Backup / restore:** the backup includes categories, so it must now carry `tripId` and
  re-associate trip categories to the correct restored trip (backup format version bump +
  restore mapping keyed on trip identity). CSV export uses category **names** only, so no
  export-format change is needed there.

## Testing

**JVM unit tests (primary gate — mockk, same style as existing repository tests):**
- `openTrip` seeds the travel template with `tripId` set.
- `observeCategoriesForTrip` returns only that trip's categories.
- `observeGlobalCategories` excludes trip categories.
- App-level name-uniqueness: same name allowed across trips/Home; rejected as duplicate
  within a single trip or within Home.
- `deleteTrip` cascade removes the trip's categories (and `SET_NULL`s them off the trip's
  transactions).
- `ForeignViewModel` / trip-scoped `CategoriesViewModel` scope to the correct set.

**Instrumented / androidTest (written, compilation-gated; on-device run left to the user/CI
per the no-device-test policy):**
- Room `MigrationTestHelper` test for 15 → 16 (column added, rows backfilled global,
  templates seeded for existing trips, non-MYR categoryIds cleared).
- Backup serialize → restore round-trip preserving trip↔category association.

## Out of scope (YAGNI)

- Auto-categorization / keyword patterns / merchant learning for trip categories.
- Copying categories between trips, or "copy from last trip" (template only, for now).
- Per-trip subset selection of shared categories.
- Changing `deleteTrip`'s "transactions persist" behaviour.

## Open question for spec review

- Confirm the existing-foreign-transactions decision (default: **clear** non-MYR
  `categoryId` on migration) vs. leaving them as-is.
- Confirm the travel template contents/colours.
