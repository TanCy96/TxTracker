# Charts & Insights — design

**Date:** 2026-05-31
**Status:** Drafted from brainstorming — pending review

## Problem

The Home screen shows a headline total and a per-category breakdown **chip row**, but no
visual distribution of spending. "Where does my money go?" reads in a second from a pie;
"am I spending more this year?" needs a trend line, not manual month-flipping. FUTURE.md
item 4 sketched a charts feature; this design pins it down, with the emphasis the user
called out: **let users choose what they want to see** (time range, grouping dimension,
chart type) and **track spend against a budget**.

## Goal

A dedicated **Insights** tab (a new top-level destination alongside Home / Foreign /
Settings) that hosts a configurable set of charts:

1. **Category pie** — share of spend per category (or per funding-source) for a chosen range.
2. **Daily spending bar** — one bar per day, stacked by category (or funding-source).
3. **Month-over-month trend line** — total spend per month over a rolling window.
4. **Per-category trend line** — one selected category over the same window.
5. **Spending vs. budget** — progress against a user-set monthly budget.

Users pick the **time range**, the **grouping dimension**, and the **chart type**, and those
choices persist across launches.

## Decisions (from brainstorming)

1. **Placement:** a dedicated **Insights** top-level tab, not inline-on-Home. Reuses the
   Foreign-tab navigation pattern and scales to many chart types without crowding Home.
2. **Configurable options:** a **time-range selector** + a **grouping toggle**
   (by category vs by funding-source) + a **chart-type switch**. Chosen defaults persist
   via an `InsightsPrefs` (SharedPreferences, the project's existing pattern).
3. **Chart types for v1:** all five listed above (the budget view was the user's addition).
4. **Library:** **Vico** (`com.patrykandpatrick.vico:compose-m3`) for the bar/line charts;
   the pie is a hand-rolled Compose `Canvas` (~50 LOC) — Vico is cartesian-focused and has
   no first-class pie. This corrects FUTURE.md's assumption that Vico ships a pie.
5. **Branch policy (unchanged):** built on `main`; `main` later merges **into**
   `feature/share-debit`, resolving conflicts in favour of SL Debit. See the dedicated
   section below — the feature is designed so that merge is a one-line edit.

## Architecture overview

New package `ui/insights/`, mirroring the existing `Route → Screen → ViewModel → UiState`
split used by `ui/foreign/` and `ui/home/`. All calendar/range logic lives in `domain/`
(pure, JVM-unit-testable, no Android deps), next to `YearMonth`.

| File | Role |
|---|---|
| `domain/InsightsPeriod.kt` | `InsightsPeriod` enum + pure `resolveInsightsPeriod(...) → InsightsRange`. The unit-test surface for range math. |
| `ui/insights/InsightsRoute.kt` | `hiltViewModel()`, collects state (as Home/Foreign do via `collectAsState`), hosts the budget-edit + custom-range dialogs, delegates to `InsightsScreen`. Mirrors `ForeignRoute`. |
| `ui/insights/InsightsScreen.kt` | Stateless screen: TopAppBar, the adaptive options row, and a `when(chartType)` dispatch to the active chart. |
| `ui/insights/InsightsViewModel.kt` | `@HiltViewModel`; `combine(options + prefs) → flatMapLatest → repo flows → stateIn`. Mirrors `HomeViewModel`. |
| `ui/insights/InsightsUiState.kt` | `InsightsUiState` sealed interface + option enums + chart data-model types. |
| `ui/insights/InsightsAggregator.kt` | **Pure** functions: `List<Transaction>` + lookup maps → chart models. The other unit-test surface. |
| `ui/insights/charts/CategoryPieChart.kt` | Hand-rolled `Canvas` pie + legend. |
| `ui/insights/charts/DailyStackedBarChart.kt` | Vico stacked-column chart. |
| `ui/insights/charts/MonthlyTrendLineChart.kt` | Vico line chart (total). |
| `ui/insights/charts/CategoryTrendLineChart.kt` | Vico line chart (one category). |
| `ui/insights/charts/BudgetProgressCard.kt` | Material3 `LinearProgressIndicator` + spend/budget text. |
| `ui/insights/charts/ChartTheme.kt` | Maps Material3 `colorScheme` + `Category.color` into Vico styling. |
| `service/InsightsPrefs.kt` | `@Singleton @Inject` SharedPreferences wrapper — copy of `NotificationPrefs`. No Hilt module needed. |

Edits to existing files: `ui/AppRoute.kt` (nav wiring), `gradle/libs.versions.toml` +
`app/build.gradle.kts` (Vico). Optionally lift `ExportRangePickerDialog` out of
`SettingsScreen.kt` into `ui/common/` for shared reuse by the Custom-range picker.

## Time-range model — `domain/InsightsPeriod.kt`

```kotlin
enum class InsightsPeriod { THIS_MONTH, LAST_MONTH, LAST_3_MONTHS, LAST_6_MONTHS, THIS_YEAR, ALL_TIME, CUSTOM }

/** Resolved half-open instant window [startInclusive, endExclusive). */
data class InsightsRange(val startInclusive: Instant, val endExclusive: Instant)

fun resolveInsightsPeriod(
    period: InsightsPeriod,
    customRange: ExportDateRange? = null,   // consulted only when CUSTOM
    earliestTransaction: Instant? = null,   // consulted only when ALL_TIME
    zone: TimeZone = MalaysiaTimeZone,
    clock: Clock = Clock.System,
): InsightsRange
```

Resolution reuses existing plumbing (`YearMonth`, `MalaysiaTimeZone`, `malaysiaDateRangeBounds`):

- `THIS_MONTH` / `LAST_MONTH` → matching `YearMonth.start()/endExclusive()`.
- `LAST_3_MONTHS` / `LAST_6_MONTHS` → end = `YearMonth.current().endExclusive()` (includes the
  in-progress month); start = roll back N−1 months via `.previous()` then `.start()`. Whole
  calendar months.
- `THIS_YEAR` → Jan-start .. next-Jan-start of the current year.
- `ALL_TIME` → start = `earliestTransaction ?: Instant.DISTANT_PAST`; end = `clock.now()`. The
  ViewModel already holds the in-range rows, so it passes `txs.minOfOrNull { it.occurredAt }`
  for sane axis bounds; `DISTANT_PAST` is the documented empty-data fallback.
- `CUSTOM` → `malaysiaDateRangeBounds(customRange!!)` (the existing export helper), wrapped.

Injectable `clock`/`zone` make it deterministic in tests — same convention as `YearMonth.current`.

**Custom picker** reuses Material3 `DateRangePicker`. The cleanest path is to lift
`ExportRangePickerDialog` and its `Long.toUtcLocalDate()` / `LocalDate.toUtcMidnightMillis()`
UTC-bridge helpers (currently private in `SettingsScreen.kt`) into a shared
`ui/common/DateRangePickerDialog.kt`, called by both Settings and Insights. (Alternative:
duplicate ~35 lines with a `TODO(dedupe)`.)

## Aggregation — Kotlin-side, reusing range-scoped queries

**Decision: no new SQL `GROUP BY` / date-bucketing queries.** `Transaction.occurredAt` is
stored as epoch-millis (`Long`), and every calendar bucketing in this codebase already happens
in Kotlin (`HomeViewModel`, `ForeignViewModel`, `CsvExporter.buildCsv` all group by
`occurredAt.toLocalDateTime(MalaysiaTimeZone).date`). We follow that.

Data sources — all already exist on `TransactionRepository`:

- `observeMyrTransactionsBetween(start, end): Flow<List<Transaction>>` — the row source for the
  daily bar, per-category trend, and the pie's day/month folds.
- `observeCategoryTotalsBetween(start, end): Flow<List<CategoryTotal>>` — pre-summed per-category
  totals (used for the pie's category grouping; **already netted on the share-debit branch**).
- `observeTotalBetween(start, end): Flow<Long>` — window total (budget spend; **already netted**).
- `observeAllCategories()` / `observeFundingSources()` — labels, colors, `fundingSourceId → kind`.

Rationale: (1) day/month buckets must be in `MalaysiaTimeZone` — Kotlin reuses tested logic,
SQLite `strftime` would duplicate and risk the zone math; (2) single-user MYR-only OUT-only
volume is tiny, folded once per option change under `WhileSubscribed`; (3) it keeps the
share-debit merge to one line (see below); (4) it factors the category-breakdown grouping —
currently duplicated in `HomeViewModel` and `ForeignViewModel` — into one tested place.

Pure aggregator API (`ui/insights/InsightsAggregator.kt`), all taking in-range rows
pre-filtered to `direction == OUT`:

```kotlin
fun groupedBreakdown(txs, groupBy, categoriesById, fundingKindLabels): List<BreakdownSlice>  // pie + legend
fun dailyStacked(txs, groupBy, zone = MalaysiaTimeZone): List<DayBucket>                       // bar
fun monthlyTotals(txs, zone = MalaysiaTimeZone): List<MonthBucket>                             // total trend
fun monthlyTotalsForCategory(txs, categoryId, zone = MalaysiaTimeZone): List<MonthBucket>      // category trend
fun budgetProgress(spentMinor, budgetMinor): BudgetProgress?                                   // budget
```

Grouping/sort matches existing code: group by `categoryId`, sum `amountMinor`, sort
`compareByDescending { category != null }.thenBy { category?.sortOrder ?: Int.MAX_VALUE }`.
Day bucket = `toLocalDateTime(MalaysiaTimeZone).date`; month bucket = `YearMonth(year, month)`.

## Options & state model

```kotlin
enum class GroupBy { CATEGORY, FUNDING_SOURCE }
enum class InsightsChartType { CATEGORY_PIE, DAILY_BAR, MONTHLY_TREND, CATEGORY_TREND, BUDGET }

sealed interface InsightsUiState {
    data object Loading : InsightsUiState
    data class Loaded(
        val chartType: InsightsChartType,
        val period: InsightsPeriod,
        val rangeLabel: String,                 // "Last 6 months"
        val groupBy: GroupBy,
        val categories: List<Category>,         // for the CATEGORY_TREND picker
        val selectedCategoryId: Long?,
        val breakdown: List<BreakdownSlice>,     // pie / legend
        val daily: List<DayBucket>,              // stacked bar
        val monthly: List<MonthBucket>,          // total trend
        val categoryTrend: List<MonthBucket>,    // per-category trend
        val totalMinor: Long,                    // headline total for the range
        val budget: BudgetProgress?,             // overall monthly budget; null when none set
        val categoryBudgets: List<CategoryBudgetProgress>,  // per-category; empty when none set
        val isEmpty: Boolean,
    ) : InsightsUiState
}
```

`InsightsPrefs` is the **source of truth for the persisted options** (`period`, last-confirmed
`customRange`, `groupBy`, `chartType`, `overallBudgetMinor`, `categoryBudgetsMinor`); the ViewModel reads those flows and
setters write through. The only session-only state is `selectedCategoryId` (depends on which
categories currently exist; snapped back to the first available one when stale, reusing the
idea in `ui/home/CategoryFilterSnapBack.kt`).

ViewModel flow mirrors `HomeViewModel` (`combine → flatMapLatest → stateIn(... WhileSubscribed(5_000) ...)`).
`combine` arity > 5 follows the existing house idiom (nested `combine { … }` or a small data class).

**Which options apply to which chart** (the options row adapts; irrelevant controls hide):

| Chart | Range selector | Grouping toggle | Category picker |
|---|---|---|---|
| Category pie | ✔ | ✔ | — |
| Daily bar | ✔ | ✔ (stacking) | — |
| Monthly trend | rolling 6-mo* | — | — |
| Category trend | rolling 6-mo* | — | ✔ |
| Budget | this month | — | — |

\* **Trend charts use a fixed rolling 6-month window**, independent of the range selector — a
trend is inherently multi-month, and a daily bar over 6 months would be unusable. The range
selector drives the pie, the daily bar, and the headline total. (Coupling trends to the
selector, and weekly/monthly auto-bucketing of the daily bar for long ranges, are noted as
future refinements.)

**Grouping by funding-source** buckets by `FundingSourceKind` (CREDIT_CARD / E_WALLET /
DEBIT_BANK / CASH), consistent with Home's existing funding-bucket chips; rows with a null
`fundingSourceId` fall into an "Unattributed" bucket. Per-individual-source grouping is future.
Kinds have no stored color, so they draw from a small fixed palette (mirroring
`DefaultCategoryColors`); category slices use `Color(category.color)`.

## The five views

- **Category pie** (`CategoryPieChart.kt`) — `Canvas` drawing one `drawArc(useCenter = true)`
  per `BreakdownSlice`, `sweep = 360f * slice.minor / total`. A separate legend column shows
  label + `formatMyr` + percentage (reusing `CategoryBreakdownRow` styling). Null/Unverified
  slice = `colorScheme.outline`. No dependency.
- **Daily bar** (`DailyStackedBarChart.kt`) — Vico `rememberColumnCartesianLayer` with a
  stacking column provider, one series per grouping key. Bottom axis = day-of-month via a
  `CartesianValueFormatter`; colors from the slices.
- **Monthly trend** (`MonthlyTrendLineChart.kt`) — Vico `rememberLineCartesianLayer`, single
  series; bottom-axis labels via `formatYearMonth`.
- **Category trend** (`CategoryTrendLineChart.kt`) — same, single colored line; parent owns the
  category dropdown.
- **Budget** — see below.

Y-axis formatter divides minor units by 100 and reuses MYR formatting. All charts pull colors
from `MaterialTheme.colorScheme` via `ChartTheme.kt` — never hardcoded — so they stay correct
under dynamic color / dark mode (`ui/theme/Theme.kt`).

## Budget (the user's addition) — overall + per-category

v1 scope: **an optional overall monthly budget AND optional per-category monthly budgets**, all
in minor units, stored in `InsightsPrefs`. Both are evaluated against the **current calendar
month**.

```kotlin
// InsightsPrefs
val overallBudgetMinor: StateFlow<Long?>            // null = no overall budget
val categoryBudgetsMinor: StateFlow<Map<Long, Long>> // categoryId -> budgetMinor; empty = none
fun setOverallBudget(minor: Long?)                  // null clears
fun setCategoryBudget(categoryId: Long, minor: Long?)  // null removes that category's budget

// InsightsAggregator (pure)
data class BudgetProgress(val spentMinor: Long, val budgetMinor: Long, val fraction: Float, val overBudget: Boolean)
data class CategoryBudgetProgress(val category: Category, val progress: BudgetProgress)

fun budgetProgress(spentMinor: Long, budgetMinor: Long?): BudgetProgress? =
    budgetMinor?.takeIf { it > 0 }?.let { BudgetProgress(spentMinor, it, spentMinor.toFloat() / it, spentMinor > it) }

/** Joins this-month per-category spend with the saved category budgets; existing categories only, over-budget first. */
fun categoryBudgetProgress(
    monthCategorySpend: Map<Long, Long>,    // categoryId -> spent this month (from observeCategoryTotalsBetween)
    budgets: Map<Long, Long>,               // categoryId -> budgetMinor
    categoriesById: Map<Long, Category>,
): List<CategoryBudgetProgress>
```

- **Storage:** prefs, not a Room table. The overall budget is a `Long` pref; the per-category map
  is a JSON-encoded `Map<String, Long>` (categoryId-as-string → minor) via the kotlinx.serialization
  already used by `Backup`. This deliberately avoids a schema bump + migration + backup change —
  which keeps the **share-debit merge trivial** (no v13 migration colliding with the branch's v12)
  and treats budgets as device-local config, consistent with `NotificationPrefs` / `CloudSyncPrefs`.
- **Both budgets evaluate against the current calendar month**, not the selected range — comparing
  a monthly target against a "Last 6 months" total is meaningless. The ViewModel runs a dedicated
  this-month flow: `observeTotalBetween(monthStart, monthEnd)` (overall spend) +
  `observeCategoryTotalsBetween(monthStart, monthEnd)` (per-category spend), where
  `monthStart/monthEnd = resolveInsightsPeriod(THIS_MONTH)`. Both queries already net the SL share
  on the branch, so the budget feature needs **zero** merge edits.
- **Stale categories:** a deleted category's budget entry is simply ignored on read
  (`categoryBudgetProgress` joins only against existing categories) and pruned opportunistically.
- **UI** (`BudgetProgressCard` + a budget list): an "Overall monthly budget" row at top (progress
  bar if set, else a "Set budget" affordance), then one row per category that has a budget set or
  has spend this month — color dot + name + spent/budget + a mini `LinearProgressIndicator`
  (`progress = fraction.coerceAtMost(1f)`; `colorScheme.error` + overage text when over). A
  "+ Add category budget" affordance opens a picker of the remaining categories. Editing any
  budget opens a small amount dialog hoisted in `InsightsRoute`; the typed ringgit string parses
  to minor units via the existing `ui/manual` `parseAmountMinor` helper. Clearing the field removes
  the budget.

## Charting library (Vico) & navigation

**Gradle.** `gradle/libs.versions.toml`: add a `vico` version (confirm the current stable 2.x
against the project's Compose BOM + Kotlin version before pinning) and a
`vico-compose-m3 = { group = "com.patrykandpatrick.vico", name = "compose-m3", version.ref = "vico" }`
library; `app/build.gradle.kts`: `implementation(libs.vico.compose.m3)`. No new plugin, no KSP
impact, ~150 KB APK.

**Navigation** — four edits to `ui/AppRoute.kt`, matching the "add a tab" recipe the file
already documents:

1. `Routes`: add `const val INSIGHTS = "insights"`.
2. `TOP_LEVEL_ROUTES`: add `Routes.INSIGHTS` (so the bottom bar shows; it auto-hides on Settings
   sub-routes, which is unchanged).
3. `NavigationBar`: add a `NavigationBarItem` between Foreign and Settings —
   `Icons.Outlined.BarChart` (from the already-present `material-icons-extended`), label
   "Insights". Order becomes Home / Foreign / Insights / Settings.
4. `NavHost`: `composable(Routes.INSIGHTS) { InsightsRoute(onSettingsClick = { navigateTopLevel(nav, Routes.SETTINGS) }) }`.

## feature/share-debit: what's there & merge strategy

The user asked this design to account for the long-lived `feature/share-debit` branch. It is
**~24 commits ahead of `main`, ~1 behind**. Branch policy (per the branch's own design doc and
the project memory): **charts are built on `main`; `main` is merged INTO `feature/share-debit`,
never the reverse, and conflicts resolve in favour of keeping SL Debit intact.**

**What "SL Debit" adds** (the relevant parts for charts):

- A prepaid **pool account** a third party funds. The user logs deposits, and **shares** a
  portion (default 40%) of selected MYR transactions: the pool pays SL's share, the user pays
  the net. Negative inflows are synthesized only at export/aggregation time — never stored.
- **Schema (v11 → v12):** a nullable `slShareMinor: Long?` column on `Transaction` (invariant:
  null, or `0 < slShareMinor ≤ amountMinor`, MYR + OUT only); two new tables
  (`sl_debit_account`, `sl_debit_deposit`); backup bumped to v9.
- **The two aggregation queries were rewritten to net the share:**
  `observeCategoryTotalsBetween` and `observeTotalBetween` use
  `SUM(amountMinor - COALESCE(slShareMinor, 0))`. So on the branch, the Home total and category
  breakdown already show **net** amounts (an RM100 transaction shared 40% counts as RM60).
- **UI/other:** a Home SL-Debit balance card, a Settings → SL Debit management screen, a
  `RowAmount` composable showing net/original/share, an SL Debit CSV column, and pure math
  helpers in `domain/SlDebit.kt`. `slShareMinor` is cleared when a row's currency leaves MYR.

**Why charts must care:** for totals to match Home after the merge, **chart spend must also be
net of the share**. Since `slShareMinor` does not exist on `main`, new chart code on `main` can
only sum `amountMinor` — which would over-count shared transactions once merged.

**Merge strategy — designed to be a one-line edit:**

1. **Aggregate in Kotlin, not new SQL** (above) — there are no new `SUM(...)` queries to
   hand-net at merge time.
2. **Route every spend sum through one function**, never reading `tx.amountMinor` directly in a
   fold:
   ```kotlin
   /** Effective spend for charts. On main this is just amountMinor.
    *  MERGE-POINT(share-debit): change to amountMinor - (slShareMinor ?: 0). */
   internal fun Transaction.chartAmountMinor(): Long = amountMinor
   ```
   On the branch, the merge edits exactly this one line and every Kotlin-aggregated chart nets
   correctly. The marker is greppable (`MERGE-POINT(share-debit)`).
3. **Prefer the pre-summed repo queries where they fit** — the pie's category grouping and the
   range/budget totals can use `observeCategoryTotalsBetween` / `observeTotalBetween`, which
   **already** carry the netting on the branch, so they need no merge edit at all.
4. **Document the convention** at the top of `InsightsAggregator.kt`: all spend sums go through
   `chartAmountMinor()`; never read `amountMinor` directly.

Net effect: the only branch-side edit for the whole Insights feature is one line in
`chartAmountMinor()`. Navigation (`AppRoute.kt`) and Home layout also changed on the branch
(the SL-Debit card), so the `AppRoute.kt` nav additions here should merge cleanly as long as
the new `INSIGHTS` route is added without reordering the existing entries.

## Testing

Everything load-bearing is pure Kotlin in `domain/` or `InsightsAggregator.kt` (no Android
types), so it runs under `./gradlew :app:testDebugUnitTest` — **no emulator / instrumentation**
(per project policy). Match the Truth + JUnit4 style of `YearMonthTest` / `FilterByRangeTest`.

- **`InsightsPeriodTest`** — for each enum value, assert the resolved `InsightsRange` bounds with
  a fixed `Clock` + `MalaysiaTimeZone` (reuse the known `2026-05 → 2026-04-30T16:00:00Z` MYT
  fixture): THIS/LAST month == matching `YearMonth`; LAST_3/LAST_6 span N whole months ending at
  current month-end; THIS_YEAR = Jan..next-Jan; ALL_TIME with supplied earliest vs DISTANT_PAST
  fallback; CUSTOM delegates to `malaysiaDateRangeBounds`.
- **`InsightsAggregatorTest`** — `Transaction` fixtures like `FilterByRangeTest.tx(...)`. Assert:
  breakdown sums + sort order (uncategorized last) + null-category → Unverified slice;
  `dailyStacked` buckets into the correct **Malaysia-local** day (reuse the
  `2026-01-31T17:00:00Z → Feb 1 MYT` boundary case) with correct per-series sub-totals;
  `monthlyTotals` / `monthlyTotalsForCategory` bucket into the right `YearMonth` and include only
  `direction == OUT`; `chartAmountMinor()` returns `amountMinor` on main (guards the MERGE-POINT).
- **`BudgetProgressTest`** — `budgetProgress` fraction / `overBudget` flag / null-or-zero-budget → null;
  and `categoryBudgetProgress` joins this-month spend with saved budgets, drops categories with no
  budget, drops budgets whose category no longer exists, and orders over-budget first.

Not unit-tested (UI/integration, verified by compile + manual run, consistent with how
`NotificationPrefs` is treated): the Vico composables, the Canvas pie, `InsightsPrefs`
SharedPreferences I/O, and nav wiring.

Definition of done: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` green.

## Out of scope / future

- **Inline-on-Home pie** (FUTURE.md's "first chunk") — superseded by the dedicated tab.
- **Budget history** (month-over-month budget vs actual) and **backup round-trip of budgets**
  (v1 keeps budgets as device-local config, like notification settings).
- **Trend charts following the range selector**, and **weekly/monthly auto-bucketing** of the
  daily bar for long ranges.
- **Per-individual-funding-source grouping** (v1 groups by kind).
- **Foreign-currency charts** — Insights is MYR-only for v1 (the Foreign tab covers per-currency
  views; no FX/MYR-equivalent math, consistent with FUTURE.md item 1's deferral).
- **IN/income direction** — v1 charts are OUT-only, matching the rest of the app.
```
