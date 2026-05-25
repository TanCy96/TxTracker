# Home + Foreign list layout consolidation

**Date:** 2026-05-25
**Scope:** FUTURE-list item 8. Collapse the two filter surfaces above the
transaction list (category breakdown row + filter chip row) into one
clickable breakdown row, so the per-category total and the category
filter are the same affordance. Same treatment applied to Foreign.

---

## Problem

Home today renders two near-duplicate horizontal rows of category chips:

1. `CategoryBreakdownRow` — `AssistChip` per category with a color dot
   and `name + total`, e.g. `● Food RM199.50`. Today's onClick is a
   no-op TODO (`HomeScreen.kt:249`).
2. `FilterRow` — `FilterChip` per category (`name` only, no total),
   alongside the verification-state chips (`All`, `Pending (N)`,
   `Unverified`, `Currency review`).

The user reads the totals from row 1 and then has to scan row 2 to find
the same category and tap it to filter. The two rows show overlapping
information and consume vertical space above the actual transaction list.
The bottom row's category chips are redundant: every category in row 2
with non-zero spend already appears in row 1, and the chip without a
total is the less informative of the two.

## Goal

Single breakdown row of selectable chips. Each chip carries the
category color, name, and month total, and acts as the filter toggle.
Tapping a chip filters the transaction list to that category; tapping
the active chip clears the filter back to `All`. Verification-state
chips (Pending, Currency review) move to a thin row above the breakdown,
shown only when their counts are non-zero. There's no explicit `All`
chip — the unselected state IS `All`.

Foreign's trip-detail screen mirrors this — it already reuses
`CategoryBreakdownRow` and has its own near-identical `ForeignFilterRow`,
so the same change ports across.

## Non-goals

- Changing per-transaction row content. The `CategoryChip` inside each
  `TransactionRow` stays — user confirmed they want the in-row category
  cue preserved as today.
- Adding category icons / per-category drill-in.
- Multi-select filtering (Food + Transport at once). The filter remains
  single-select, mirroring today's `HomeFilter` sealed interface.

---

## Layout (after)

```
┌─────────────────────────────────────────────────┐
│ Spent this month                                │
│ RM 1,234.56                                     │
│ 27 transactions                                 │
├─────────────────────────────────────────────────┤
│ [Pending (3)]  [Currency review (1)]            │   ← StatusFilterRow,
│                                                 │     only when any count > 0
├─────────────────────────────────────────────────┤
│ [●Unverified RM50]  [●Food RM199.50]  [●Trans…  │   ← CategoryBreakdownRow,
│                                                 │     now clickable FilterChips
├─────────────────────────────────────────────────┤
│ [Currency-review banner, when present]          │
├─────────────────────────────────────────────────┤
│ ─── Wed, 24 May ─────────────────── RM 200.00  │
│  …existing transaction rows unchanged…          │
└─────────────────────────────────────────────────┘
```

The bottom `FilterRow` and Foreign's `ForeignFilterRow` are deleted.

---

## Behavior

### Selection state

`CategoryBreakdownRow` chips become `FilterChip` (selectable Material 3
chip), not `AssistChip`. The chip's `selected` boolean is derived from
the current `HomeFilter`:

```kotlin
val selected = when {
    entry.category == null -> filter is HomeFilter.Unverified
    else -> filter is HomeFilter.Category && filter.id == entry.category.id
}
```

`StatusFilterRow` chips use the analogous derivation for
`HomeFilter.Pending` and `HomeFilter.CurrencyReview`.

### Tap behavior

Single `onChipTap(target: HomeFilter)` helper, called by every chip:

```kotlin
fun onChipTap(target: HomeFilter) {
    onFilterChange(if (filter == target) HomeFilter.All else target)
}
```

Tapping a chip that's already the active filter resets to `All`.
Tapping a different chip switches. No dedicated `All` chip is rendered.

### Visibility rules

- `StatusFilterRow` renders nothing when both `pendingCount == 0` and
  `currencyReviewCount == 0`. When either is non-zero, the row appears
  with just the chip(s) that have non-zero count. (`currencyReviewCount`
  is already specified to land in the capture-pool design — both specs
  add the same field; only the first to land actually adds it.)
- `CategoryBreakdownRow` renders nothing when the breakdown is empty
  (already the current behavior).
- A category with `totalMinor == 0` for the month never appears in the
  breakdown row, which means it also doesn't appear as a filter option.
  **Intentional behavior change**: today's `FilterRow` shows every
  category regardless of spend, letting the user filter to a category
  with no transactions (yielding an empty list). After this spec, you
  can only filter to categories that have at least one transaction this
  month. Categories with zero spend are reachable from the per-row
  `CategoryChip` flow and the Edit sheet's category picker, but not via
  the filter row.

### Empty/edge cases

- **No transactions for the month**: `CategoryBreakdownRow` already
  returns early when empty; `StatusFilterRow` also empty (no pending);
  user sees only the totals header + `EmptyState`. No regression.
- **Active filter on a category whose spend drops to 0** (e.g. user
  deletes the last transaction in `Food` while filtered to `Food`):
  the `Food` chip disappears, so the filter is unreachable from the
  UI. `HomeViewModel` watches for this and snaps back to `All` in the
  same `flatMapLatest` block — same mechanism the capture-pool spec
  uses for the `CurrencyReview` auto-clear case.

---

## Code changes

### `cy.txtracker.ui.home.HomeScreen.kt`

- Delete the private `FilterRow` composable (the per-category +
  status-chip row). Remove its call site in `HomeScreen`.
- Change `CategoryBreakdownRow`:
  - `AssistChip` → `FilterChip`.
  - Add `selectedFilter: HomeFilter` and `onChipTap: (HomeFilter) -> Unit`
    parameters.
  - For each entry, compute `selected` per the rule above and call
    `onChipTap(target)` where target is `HomeFilter.Unverified` for
    `entry.category == null` and `HomeFilter.Category(entry.category.id)`
    otherwise.
  - The leading color dot stays as the chip's `leadingIcon`.
- Add a new private `StatusFilterRow` composable rendering up to two
  `FilterChip`s (Pending, Currency review) with the same `onChipTap`
  helper. Renders `null`/nothing when both counts are zero.
- Wire `HomeScreen`'s Column to call `StatusFilterRow` between
  `MonthTotalHeader` and `CategoryBreakdownRow`, and `CategoryBreakdownRow`
  before the divider that separates the header area from the list.

### `cy.txtracker.ui.home.HomeUiState`

- `currencyReviewCount: Int` is added by the capture-pool spec; this
  spec depends on that field being present. If this spec lands first,
  this is where the field gets introduced.
- `categories: List<Category>` is no longer consumed by `HomeScreen`
  (only `EditTransactionSheet` uses it). Keep on `HomeUiState` for now —
  it's already populated by `HomeViewModel`, and removal is a separate
  cleanup. Mark the field as "consumed by edit sheet only" in a kdoc
  line.

### `cy.txtracker.ui.home.HomeViewModel`

- Add the snap-to-`All` guard for category filters whose breakdown
  entry has disappeared:
  ```kotlin
  if (filter is HomeFilter.Category &&
      breakdown.none { it.category?.id == filter.id }) {
      _filter.value = HomeFilter.All
  }
  ```
  Lives in the same `flatMapLatest` block that builds state, so it
  fires whenever month / transactions change.
- No other VM changes — `setFilter` already does the right thing for
  arbitrary filter values.

### `cy.txtracker.ui.foreign.ForeignRoute.kt`

- `CategoryBreakdownRow` call site updates to pass the new params
  (selected filter + onChipTap). Foreign uses its own filter sealed
  type, so the helper wires through Foreign's equivalent `onFilterChange`
  with a Foreign-specific `onChipTap`.
- Delete `ForeignFilterRow` (lines 250+ in `ForeignRoute.kt`). Remove
  its call site.
- Foreign's UI state likely needs an analogous count snapshot — only
  if Foreign has equivalent verification-state filters today. Spot check
  during implementation: if Foreign currently has no Pending / Currency-
  review chips of its own, `StatusFilterRow` simply isn't rendered on
  Foreign.

---

## Testing strategy

- Compose UI tests (`HomeScreenTest` / equivalent):
  - With breakdown empty and no pending/currency-review, status row +
    breakdown row both absent.
  - With one Pending row only, status row shows `Pending (1)` chip only.
  - Tapping a category chip changes the filter and visibly shows
    `selected = true`.
  - Tapping the active chip returns to `All` and visibly deselects.
- Unit tests on `HomeViewModel`:
  - Filter snaps back to `All` when the active category drops to 0
    transactions.
  - `currencyReviewCount` and `pendingCount` derive correctly.
- Foreign: same shape of test for `ForeignViewModel` snap-back.

---

## Migration / rollout

No data migration. Purely a UI restructure. The PR ships in one shot
across both Home and Foreign. No feature flag.

---

## Open / deferred

- **Showing zero-spend categories in the chip row.** If users miss this
  (unlikely — empty filter is rarely useful), surface them as a "+ more"
  collapse at the end of the row. Skip for v1.
- **Reordering chips by total descending.** Currently sorted by category
  table order. Worth a small follow-up if visually noisy. Skip for v1.
- **Tap-and-hold to enter a multi-category filter.** Skip — `HomeFilter`
  is single-select by design.
