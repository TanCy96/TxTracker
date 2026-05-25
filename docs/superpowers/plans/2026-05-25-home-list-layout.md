# Home List Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the two filter rows on Home and Foreign into one selectable `CategoryBreakdownRow`. Verification-state filters (Pending, Currency review) move into a thin `StatusFilterRow` above the breakdown, shown only when their counts are non-zero. Per-tx `CategoryChip` stays.

**Architecture:** UI-only restructure with two small ViewModel additions: a `currencyReviewCount` field on `HomeUiState`, and a pure snap-back helper that reverts the active category filter to `All` when the filtered-to category drops to 0 transactions for the month. No DB migration. No schema change. Spec: `docs/superpowers/specs/2026-05-25-home-list-layout-design.md`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, kotlinx.coroutines flows, JUnit + Truth (existing test stack — see `app/src/test/java/cy/txtracker/data/CategoryBackfillTest.kt` for pattern).

---

## File Structure

**Modified:**
- `app/src/main/java/cy/txtracker/ui/home/HomeUiState.kt` — add `currencyReviewCount: Int`.
- `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt` — emit `currencyReviewCount`; apply snap-back inside `buildState`.
- `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt` — rewrite `CategoryBreakdownRow`; add `StatusFilterRow`; remove `FilterRow`; wire `Column`.
- `app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt` — apply snap-back inside `buildLoaded`.
- `app/src/main/java/cy/txtracker/ui/foreign/ForeignRoute.kt` — update `CategoryBreakdownRow` call; add `StatusFilterRow`; delete `ForeignFilterRow`.

**Created:**
- `app/src/main/java/cy/txtracker/ui/home/CategoryFilterSnapBack.kt` — pure helpers `snapStaleHomeCategoryToAll` / `snapStaleForeignCategoryToAll`.
- `app/src/test/java/cy/txtracker/ui/home/CategoryFilterSnapBackTest.kt` — unit test.

**Bounded responsibilities:**
- `CategoryFilterSnapBack.kt` owns the rule "category filters disappear with their breakdown chip" — pure, no Compose, no Android. Easy to unit-test.
- `StatusFilterRow` + `StatusChipSpec` (added to `HomeScreen.kt`) is the shared composable for Pending / CurrencyReview. Foreign passes a 1-element spec list; Home a 0-2-element list. Same composable.
- `CategoryBreakdownRow` (existing, in `HomeScreen.kt`) keeps the same name; its signature changes to take `isSelected` + `onChipTap` predicates so both Home and Foreign can pass their own filter sealed types without generics.

---

### Task 1: Add `currencyReviewCount` to `HomeUiState`

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeUiState.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt`

- [ ] **Step 1.1: Add the field to `HomeUiState`**

In `HomeUiState.kt`, add to the `HomeUiState` data class (after `pendingCount`):

```kotlin
/** Count of MYR transactions awaiting currency confirmation. Drives the visibility of the
 *  Currency-review filter chip in the breakdown header — chip hides when count is 0. */
val currencyReviewCount: Int,
```

- [ ] **Step 1.2: Add a flow for currency-review count in `HomeViewModel`**

In `HomeViewModel.kt`, immediately after the `_bannerOffer` declaration (around line 49), add:

```kotlin
private val _currencyReviewCount: StateFlow<Int> =
    repository.observeCurrencyReviewTransactions()
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = 0,
        )
```

Add `import kotlinx.coroutines.flow.map` at the top of the file.

- [ ] **Step 1.3: Plumb it through the outer `combine`**

Change the outer `combine(...)` in the `state` declaration (currently 4-arg) to a 5-arg combine including `_currencyReviewCount`:

```kotlin
val state: StateFlow<HomeUiState> =
    combine(_yearMonth, _filter, repository.observeAllCategories(), _bannerOffer, _currencyReviewCount) { ym, filter, cats, banner, crCount ->
        object {
            val yearMonth = ym
            val filter = filter
            val categories = cats
            val banner = banner
            val currencyReviewCount = crCount
        }
    }.flatMapLatest { params ->
        val ym = params.yearMonth
        val filter = params.filter
        val categories = params.categories
        val banner = params.banner
        val currencyReviewCount = params.currencyReviewCount
        // ...existing body...
```

Inside the existing `flatMapLatest` body, pass `currencyReviewCount` through both inner `combine` blocks to `buildState` and `buildCurrencyReviewState`.

- [ ] **Step 1.4: Update `buildState`, `buildCurrencyReviewState`, and `empty()` signatures + bodies**

Both `buildState` and `buildCurrencyReviewState` get a new `currencyReviewCount: Int` parameter. Pass it through to the constructed `HomeUiState`:

```kotlin
return HomeUiState(
    // ...existing fields...
    pendingCount = transactions.count { it.needsVerification },
    currencyReviewCount = currencyReviewCount,
    isLoading = false,
    bannerCurrency = banner,
)
```

Update `empty()` to set `currencyReviewCount = 0`.

- [ ] **Step 1.5: Compile check**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 1.6: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/home/HomeUiState.kt app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt
git commit -m "HomeUiState: track currencyReviewCount for chip-visibility logic"
```

---

### Task 2: Pure snap-back helper + unit test

**Files:**
- Create: `app/src/main/java/cy/txtracker/ui/home/CategoryFilterSnapBack.kt`
- Create: `app/src/test/java/cy/txtracker/ui/home/CategoryFilterSnapBackTest.kt`

- [ ] **Step 2.1: Write the failing test**

Create `app/src/test/java/cy/txtracker/ui/home/CategoryFilterSnapBackTest.kt`:

```kotlin
package cy.txtracker.ui.home

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import org.junit.Test

class CategoryFilterSnapBackTest {

    private val foodCat = Category(id = 5L, name = "Food", color = 0, sortOrder = 0)
    private val foodEntry = CategoryBreakdownEntry(category = foodCat, totalMinor = 19950L)
    private val unverifiedEntry = CategoryBreakdownEntry(category = null, totalMinor = 5000L)

    @Test
    fun `category filter survives when its breakdown chip is present`() {
        val result = snapStaleHomeCategoryToAll(
            filter = HomeFilter.Category(5L),
            breakdown = listOf(foodEntry, unverifiedEntry),
        )
        assertThat(result).isEqualTo(HomeFilter.Category(5L))
    }

    @Test
    fun `category filter snaps to All when its breakdown chip is gone`() {
        val result = snapStaleHomeCategoryToAll(
            filter = HomeFilter.Category(99L),
            breakdown = listOf(foodEntry),
        )
        assertThat(result).isEqualTo(HomeFilter.All)
    }

    @Test
    fun `category filter snaps to All when breakdown is empty`() {
        val result = snapStaleHomeCategoryToAll(
            filter = HomeFilter.Category(5L),
            breakdown = emptyList(),
        )
        assertThat(result).isEqualTo(HomeFilter.All)
    }

    @Test
    fun `non-category filters are never modified`() {
        listOf(HomeFilter.All, HomeFilter.Pending, HomeFilter.Unverified, HomeFilter.CurrencyReview).forEach { f ->
            val result = snapStaleHomeCategoryToAll(filter = f, breakdown = emptyList())
            assertThat(result).isEqualTo(f)
        }
    }
}
```

- [ ] **Step 2.2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.ui.home.CategoryFilterSnapBackTest" 2>&1 | tail -20`
Expected: FAIL (`snapStaleHomeCategoryToAll` doesn't exist).

- [ ] **Step 2.3: Write the helper**

Create `app/src/main/java/cy/txtracker/ui/home/CategoryFilterSnapBack.kt`:

```kotlin
package cy.txtracker.ui.home

import cy.txtracker.ui.foreign.ForeignFilter

/**
 * Returns `HomeFilter.All` when [filter] is a category whose id no longer appears in the
 * current [breakdown] (i.e., the filter would point at an absent chip after this month's
 * data refresh). Returns [filter] unchanged in every other case. Pure.
 */
internal fun snapStaleHomeCategoryToAll(
    filter: HomeFilter,
    breakdown: List<CategoryBreakdownEntry>,
): HomeFilter {
    if (filter !is HomeFilter.Category) return filter
    val visible = breakdown.any { it.category?.id == filter.id }
    return if (visible) filter else HomeFilter.All
}

/**
 * Foreign equivalent of [snapStaleHomeCategoryToAll]. Same rule, different sealed type.
 */
internal fun snapStaleForeignCategoryToAll(
    filter: ForeignFilter,
    breakdown: List<CategoryBreakdownEntry>,
): ForeignFilter {
    if (filter !is ForeignFilter.Category) return filter
    val visible = breakdown.any { it.category?.id == filter.id }
    return if (visible) filter else ForeignFilter.All
}
```

- [ ] **Step 2.4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.ui.home.CategoryFilterSnapBackTest" 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`, all 4 assertions pass.

- [ ] **Step 2.5: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/home/CategoryFilterSnapBack.kt app/src/test/java/cy/txtracker/ui/home/CategoryFilterSnapBackTest.kt
git commit -m "CategoryFilterSnapBack: pure helper to revert stale category filters"
```

---

### Task 3: Apply snap-back inside `HomeViewModel.buildState`

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt`

- [ ] **Step 3.1: Reorder `buildState` so `breakdown` is computed before filtering**

In `HomeViewModel.buildState` (currently lines 157-201), move the `breakdown` block above the `filtered` / `days` blocks. The reorder is needed because the snap-back rule consults `breakdown` to decide whether to keep the category filter.

The new order inside `buildState`:

```kotlin
val byId = categories.associateBy { it.id }
val joined = transactions.map { TransactionWithCategory(it, it.categoryId?.let(byId::get)) }

// 1. Compute breakdown FIRST so we can consult it for snap-back below.
val breakdown = totals
    .map { CategoryBreakdownEntry(category = it.categoryId?.let(byId::get), totalMinor = it.totalMinor) }
    .sortedWith(
        compareByDescending<CategoryBreakdownEntry> { it.category != null }
            .thenBy { it.category?.sortOrder ?: Int.MAX_VALUE },
    )

// 2. Snap a stale category filter back to All. Writes back to _filter so the next emit
//    sees the corrected value; this lambda's local `filter` is already captured.
val effectiveFilter = snapStaleHomeCategoryToAll(filter, breakdown)
if (effectiveFilter != filter) {
    _filter.value = effectiveFilter
}

// 3. Filter / group using the effective filter.
val filtered = when (effectiveFilter) {
    HomeFilter.All -> joined
    HomeFilter.Unverified -> joined.filter { it.transaction.categoryId == null }
    HomeFilter.Pending -> joined.filter { it.transaction.needsVerification }
    HomeFilter.CurrencyReview -> joined.filter { it.transaction.needsCurrencyConfirmation }
    is HomeFilter.Category -> joined.filter { it.transaction.categoryId == effectiveFilter.id }
}
val days = filtered
    .groupBy { it.transaction.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
    .toSortedMap(reverseOrder())
    .map { (date, list) -> DayGroup(date, list) }

return HomeUiState(
    yearMonth = yearMonth,
    filter = effectiveFilter,
    totalMinor = monthTotal,
    transactionCount = transactions.size,
    breakdown = breakdown,
    categories = categories,
    days = days,
    notesByMerchant = notes.associate { it.merchantNormalized to it.note },
    pendingCount = transactions.count { it.needsVerification },
    currencyReviewCount = currencyReviewCount,
    isLoading = false,
    bannerCurrency = banner,
)
```

(Replace the existing `buildState` body with the above. `buildCurrencyReviewState` is unaffected — its `breakdown` is always `emptyList()` and its `filter` is fixed to `HomeFilter.CurrencyReview`, neither of which interacts with snap-back.)

- [ ] **Step 3.2: Compile check**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3.3: Run unit tests to make sure nothing regressed**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3.4: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt
git commit -m "HomeViewModel: snap stale category filter back to All"
```

---

### Task 4: Rewrite `CategoryBreakdownRow` as clickable `FilterChip`

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt`

- [ ] **Step 4.1: Replace the `CategoryBreakdownRow` body**

In `HomeScreen.kt`, replace the existing `CategoryBreakdownRow` composable (lines 237-262) with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryBreakdownRow(
    breakdown: List<CategoryBreakdownEntry>,
    amountFormatter: (Long) -> String,
    isSelected: (CategoryBreakdownEntry) -> Boolean,
    onChipTap: (CategoryBreakdownEntry) -> Unit,
) {
    if (breakdown.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(breakdown) { entry ->
            FilterChip(
                selected = isSelected(entry),
                onClick = { onChipTap(entry) },
                leadingIcon = {
                    val color = entry.category?.color?.let(::Color) ?: MaterialTheme.colorScheme.outline
                    Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
                },
                label = {
                    val name = entry.category?.name ?: "Unverified"
                    Text("$name  ${amountFormatter(entry.totalMinor)}")
                },
            )
        }
    }
}
```

Imports needed (add at the top of the file if absent): `androidx.compose.material3.FilterChip` (already present for the old `FilterRow`); remove the `AssistChip` / `AssistChipDefaults` imports if no other call site references them — verify via grep before deleting.

- [ ] **Step 4.2: Compile check**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: `BUILD FAILED` — the call sites in `HomeScreen` and `ForeignRoute` pass the old `(breakdown, amountFormatter)` signature and now require two more args. This is expected and resolved by Tasks 6 and 9.

(No commit yet — checkpoint reached at Task 6 once Home compiles end-to-end.)

---

### Task 5: Add shared `StatusFilterRow` composable

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt`

- [ ] **Step 5.1: Add `StatusChipSpec` + `StatusFilterRow`**

In `HomeScreen.kt`, add immediately after `CategoryBreakdownRow`:

```kotlin
/**
 * Shared spec for the thin status-filter row above [CategoryBreakdownRow]. Each spec is
 * one chip; the row only renders when at least one spec is present (callers omit chips
 * whose underlying count is zero, so an empty list means "row hides itself").
 */
internal data class StatusChipSpec(
    val label: String,
    val selected: Boolean,
    val onTap: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusFilterRow(specs: List<StatusChipSpec>) {
    if (specs.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(specs) { spec ->
            FilterChip(
                selected = spec.selected,
                onClick = spec.onTap,
                label = { Text(spec.label) },
            )
        }
    }
}
```

- [ ] **Step 5.2: Compile check (expected still-failing at call sites)**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: still `BUILD FAILED` at HomeScreen call sites until Task 6 wires them.

(No commit yet.)

---

### Task 6: Rewire `HomeScreen` Column; delete old `FilterRow`

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt`

- [ ] **Step 6.1: Replace the `Column` body in `HomeScreen`**

In the `Scaffold { padding -> Column(...) { ... } }` block (lines 152-184), replace the body:

```kotlin
Column(modifier = Modifier.fillMaxSize().padding(padding)) {
    MonthTotalHeader(totalMinor = state.totalMinor, transactionCount = state.transactionCount)

    val onChipTap: (HomeFilter) -> Unit = { target ->
        onFilterChange(if (state.filter == target) HomeFilter.All else target)
    }

    val statusChips = buildList {
        if (state.pendingCount > 0) add(
            StatusChipSpec(
                label = "Pending (${state.pendingCount})",
                selected = state.filter is HomeFilter.Pending,
                onTap = { onChipTap(HomeFilter.Pending) },
            )
        )
        if (state.currencyReviewCount > 0) add(
            StatusChipSpec(
                label = "Currency review (${state.currencyReviewCount})",
                selected = state.filter is HomeFilter.CurrencyReview,
                onTap = { onChipTap(HomeFilter.CurrencyReview) },
            )
        )
    }
    StatusFilterRow(specs = statusChips)

    CategoryBreakdownRow(
        breakdown = state.breakdown,
        amountFormatter = ::formatMyr,
        isSelected = { entry ->
            val f = state.filter
            when {
                entry.category == null -> f is HomeFilter.Unverified
                else -> f is HomeFilter.Category && f.id == entry.category.id
            }
        },
        onChipTap = { entry ->
            val target = if (entry.category == null) {
                HomeFilter.Unverified
            } else {
                HomeFilter.Category(entry.category.id)
            }
            onChipTap(target)
        },
    )
    HorizontalDivider()
    state.bannerCurrency?.let { offer ->
        CurrencyReviewBanner(
            offer = offer,
            onStart = { onStartTrip(offer) },
            onDismiss = { onDismissBanner(offer.currency) },
        )
    }
    if (state.days.isEmpty()) {
        EmptyState(state)
    } else {
        TransactionList(
            days = state.days,
            notesByMerchant = state.notesByMerchant,
            contentPadding = PaddingValues(vertical = 8.dp),
            amountFormatter = ::formatMyr,
            onTransactionClick = onTransactionClick,
        )
    }
}
```

The old single `HorizontalDivider()` between FilterRow and the list is replaced by exactly one divider between `CategoryBreakdownRow` and the banner/list. No second divider.

- [ ] **Step 6.2: Delete the old `FilterRow` composable**

Delete the private `FilterRow` composable (lines 265-317 in `HomeScreen.kt`). After deletion, also remove any imports that exist only for `FilterRow` if they're now unused: `androidx.compose.material3.FilterChip` is still used by `StatusFilterRow` and `CategoryBreakdownRow`, so it stays. `androidx.compose.material3.AssistChip` and `AssistChipDefaults` are no longer used anywhere — remove their imports.

Verify with grep:
```bash
grep -n "AssistChip\|AssistChipDefaults" app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt
```
Expected: no matches.

- [ ] **Step 6.3: Compile check**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: `BUILD FAILED` only at `ForeignRoute.kt` (still passing old `CategoryBreakdownRow` signature). Home itself should be clean — confirm the error message points only to `foreign/ForeignRoute.kt`.

(No commit yet — wait until Foreign is wired so we commit one coherent UI change.)

---

### Task 7: Foreign — apply snap-back inside `ForeignViewModel.buildLoaded`

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt`

- [ ] **Step 7.1: Add a unit test for the foreign helper**

Append to `app/src/test/java/cy/txtracker/ui/home/CategoryFilterSnapBackTest.kt`:

```kotlin
    @Test
    fun `foreign category filter snaps to All when chip is gone`() {
        val result = snapStaleForeignCategoryToAll(
            filter = cy.txtracker.ui.foreign.ForeignFilter.Category(99L),
            breakdown = listOf(foodEntry),
        )
        assertThat(result).isEqualTo(cy.txtracker.ui.foreign.ForeignFilter.All)
    }

    @Test
    fun `foreign category filter survives when chip is present`() {
        val result = snapStaleForeignCategoryToAll(
            filter = cy.txtracker.ui.foreign.ForeignFilter.Category(5L),
            breakdown = listOf(foodEntry),
        )
        assertThat(result).isEqualTo(cy.txtracker.ui.foreign.ForeignFilter.Category(5L))
    }
```

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.ui.home.CategoryFilterSnapBackTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL` (helper already exists from Task 2).

- [ ] **Step 7.2: Reorder `buildLoaded` so `breakdown` precedes filtering**

In `ForeignViewModel.buildLoaded` (lines 84-149), the breakdown is computed AFTER the filter is applied. Reorder so it's computed first, then snap, then filter. Updated body:

```kotlin
val byId = categories.associateBy { it.id }
val joined = transactions.map { TransactionWithCategory(it, it.categoryId?.let(byId::get)) }

// Breakdown FIRST so snap-back can consult it.
val breakdown = transactions
    .filter { it.direction == cy.txtracker.data.Direction.OUT }
    .groupBy { it.categoryId }
    .map { (categoryId, rows) ->
        CategoryBreakdownEntry(category = categoryId?.let(byId::get), totalMinor = rows.sumOf { it.amountMinor })
    }
    .sortedWith(
        compareByDescending<CategoryBreakdownEntry> { it.category != null }
            .thenBy { it.category?.sortOrder ?: Int.MAX_VALUE },
    )

val effectiveFilter = snapStaleForeignCategoryToAll(filter, breakdown)
if (effectiveFilter != filter) {
    _filter.value = effectiveFilter
}

val filtered = when (effectiveFilter) {
    ForeignFilter.All -> joined
    ForeignFilter.Unverified -> joined.filter { it.transaction.categoryId == null }
    ForeignFilter.Pending -> joined.filter { it.transaction.needsVerification }
    is ForeignFilter.Category -> joined.filter { it.transaction.categoryId == effectiveFilter.id }
}

val days = filtered
    .groupBy { it.transaction.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
    .toSortedMap(reverseOrder())
    .map { (date, list) -> DayGroup(date, list) }

val total = transactions
    .filter { it.direction == cy.txtracker.data.Direction.OUT }
    .sumOf { it.amountMinor }

val symbol = Currencies.CODE_TO_DISPLAY_SYMBOL[trip.currency] ?: trip.currency

return ForeignUiState.Loaded(
    trip = TripDescriptor(
        tripId = trip.id,
        currency = trip.currency,
        displaySymbol = symbol,
        startAt = trip.startAt,
        endAt = trip.endAt,
    ),
    tripIndex = tripIndex,
    tripCount = trips.size,
    filter = effectiveFilter,
    totalMinor = total,
    transactionCount = transactions.size,
    breakdown = breakdown,
    categories = categories,
    days = days,
    notesByMerchant = notes.associate { it.merchantNormalized to it.note },
    pendingCount = transactions.count { it.needsVerification },
)
```

Add import: `import cy.txtracker.ui.home.snapStaleForeignCategoryToAll`.

- [ ] **Step 7.3: Compile check**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: still `BUILD FAILED` at `ForeignRoute.kt` (signature mismatch). Foreign VM itself should compile clean — confirm no error lines name `ForeignViewModel.kt`.

(No commit yet — held until Task 9 lands.)

---

### Task 8: Foreign — update `ForeignRoute` to use new composables

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/foreign/ForeignRoute.kt`

- [ ] **Step 8.1: Replace the `LoadedContent` Column body**

In `ForeignRoute.kt`, replace the `LoadedContent` composable body (lines 184-221) with:

```kotlin
@Composable
private fun LoadedContent(
    state: ForeignUiState.Loaded,
    onFilterChange: (ForeignFilter) -> Unit,
    onTransactionClick: (cy.txtracker.data.Transaction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TripTotalHeader(
            trip = state.trip,
            totalMinor = state.totalMinor,
            transactionCount = state.transactionCount,
        )

        val onChipTap: (ForeignFilter) -> Unit = { target ->
            onFilterChange(if (state.filter == target) ForeignFilter.All else target)
        }

        val statusChips = buildList {
            if (state.pendingCount > 0) add(
                StatusChipSpec(
                    label = "Pending (${state.pendingCount})",
                    selected = state.filter is ForeignFilter.Pending,
                    onTap = { onChipTap(ForeignFilter.Pending) },
                )
            )
        }
        StatusFilterRow(specs = statusChips)

        CategoryBreakdownRow(
            breakdown = state.breakdown,
            amountFormatter = { amt -> formatAmount(amt, state.trip.displaySymbol) },
            isSelected = { entry ->
                val f = state.filter
                when {
                    entry.category == null -> f is ForeignFilter.Unverified
                    else -> f is ForeignFilter.Category && f.id == entry.category.id
                }
            },
            onChipTap = { entry ->
                val target = if (entry.category == null) {
                    ForeignFilter.Unverified
                } else {
                    ForeignFilter.Category(entry.category.id)
                }
                onChipTap(target)
            },
        )
        HorizontalDivider()
        if (state.days.isEmpty()) {
            EmptyTripContent(state)
        } else {
            TransactionList(
                days = state.days,
                notesByMerchant = state.notesByMerchant,
                contentPadding = PaddingValues(vertical = 8.dp),
                amountFormatter = { amt -> formatAmount(amt, state.trip.displaySymbol) },
                onTransactionClick = onTransactionClick,
            )
        }
    }
}
```

Add imports: `import cy.txtracker.ui.home.StatusChipSpec` and `import cy.txtracker.ui.home.StatusFilterRow`.

- [ ] **Step 8.2: Delete the old `ForeignFilterRow` composable**

Delete the private `ForeignFilterRow` composable (lines 248-294 in the original file).

Also delete any imports left dangling (`Category` import may still be needed if used elsewhere — keep if so; remove only what no other code references). Verify via grep:

```bash
grep -n "ForeignFilterRow\|AssistChip" app/src/main/java/cy/txtracker/ui/foreign/ForeignRoute.kt
```
Expected: no matches.

- [ ] **Step 8.3: Compile check**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8.4: Run all unit tests to catch regressions**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`. All previously-passing tests still pass; the new snap-back tests pass.

- [ ] **Step 8.5: Commit the UI changes together**

```bash
git add app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt \
        app/src/main/java/cy/txtracker/ui/foreign/ForeignViewModel.kt \
        app/src/main/java/cy/txtracker/ui/foreign/ForeignRoute.kt \
        app/src/test/java/cy/txtracker/ui/home/CategoryFilterSnapBackTest.kt
git commit -m "Home + Foreign: collapse filter rows into clickable breakdown"
```

---

### Task 9: Manual verification on device or emulator (user-driven)

> **Note:** Per the user's `.claude/memory` rule, automated emulator / `connectedDebugAndroidTest` runs are off-limits. This task is a checklist the user runs themselves before considering the work shipped.

- [ ] **Step 9.1: Install + launch the app**

User runs from Android Studio or via `./gradlew :app:installDebug` followed by launching from the launcher. The plan does NOT run these for the user.

- [ ] **Step 9.2: Visual checklist on Home**

- [ ] Month total header renders as before.
- [ ] Below it, when there are pending or currency-review rows, a thin row of chip(s) appears. When both counts are 0, no row.
- [ ] Below that, the category breakdown row shows one chip per non-zero category with `Name  RM xx.xx` text and the category color dot.
- [ ] Tapping a category chip filters the list AND visually highlights the chip as selected.
- [ ] Tapping the selected category chip again removes the filter (no chip selected = All).
- [ ] No second row of bare category filter chips above the list. The old `FilterRow` is gone.
- [ ] Each `TransactionRow` still shows its `CategoryChip` next to the merchant area.

- [ ] **Step 9.3: Visual checklist on Foreign**

- [ ] Inside any trip, the layout matches Home's shape (header + status row + breakdown row + list).
- [ ] No `Currency review` chip on Foreign (intentional — that filter doesn't exist there).
- [ ] Tap/deselect behavior is identical to Home.

- [ ] **Step 9.4: Stale-filter edge case**

- [ ] In a month with at least one `Food` transaction, filter to `Food`. Delete the last `Food` transaction (via Edit sheet → Not a transaction).
- [ ] Expected: filter snaps back to All; the `Food` chip disappears from the breakdown row.

---

## Out-of-scope reminders

- No persisted user preferences change.
- The "Re-parse merchants from raw text" Settings entry (hidden in commit `f25374f`) is untouched.
- The `captureAllPackages` toggle, pool, tracked-apps screen, and rejected-source table from the capture-pool spec are NOT addressed here — that spec has its own plan.
