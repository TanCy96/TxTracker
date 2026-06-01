# Insights follow-ups — design

**Date:** 2026-05-31
**Status:** All three follow-ups (budget alerts, drill-down, foreign-currency charts) implemented 2026-05-31

Three improvements to the shipped Insights feature
(`docs/superpowers/specs/2026-05-31-insights-charts-design.md`), chosen from the options menu:

1. **Budget alerts** (push) — surface budgets through the existing notification system.
2. **Tap-to-drill-down** — tap a chart series to its underlying transactions.
3. **Foreign-currency charts** — extend Insights beyond MYR.

Each section: motivation, design, files, effort, and the open decision(s) worth confirming.

---

## Shared groundwork: an Insights deep-link

Today `DeeplinkBus` + `MainActivity.Deeplink` only target Home (`PendingFilter`, `CurrencyReview`),
and `AppRoute` routes both there. Budget alerts (and any future Insights push) need a deep-link
**into** the Insights tab. Minimal addition, reused by feature 1:

- `MainActivity.Deeplink` gains `InsightsBudget` (and a generic `Insights`).
- `AppRoute`'s `LaunchedEffect(deeplinkBus)` routes it via `navigateTopLevel(nav, Routes.INSIGHTS)`.
- To open on a specific chart, set `InsightsPrefs.setChartType(BUDGET)` before navigating (the
  screen already reads `chartType` from prefs — no new VM state needed). Clean and stateless.

Files: `ui/MainActivity.kt`, `notify/DeeplinkBus.kt`, `ui/AppRoute.kt`.

---

## 1. Budget alerts (push) — P1 + P2 — ✅ implemented

**Why.** Budgets shipped, but they're passive — the user must open Insights to notice they're
over. A push when a budget crosses 80% / 100% closes the loop, reusing the notification stack
(`NotificationChannels`, `NotificationPrefs`, WorkManager, `NotificationScheduler`, `DeeplinkBus`).

**Design.**

- **Pure policy first (TDD).** `notify/BudgetAlerts.kt`: `fun budgetAlertsToFire(monthSpend: Long,
  overallBudget: Long?, categorySpend: Map<Long,Long>, categoryBudgets: Map<Long,Long>,
  alreadyFired: Set<String>, yearMonth: YearMonth): List<BudgetAlert>`. An alert key is
  `"<ym>:<scope>:<threshold>"` (e.g. `2026-05:cat:1:100`), so each threshold fires once per month and
  resets when the month rolls over. Thresholds: 80 and 100 (constants for v1). Fully unit-testable.
- **Worker.** `notify/BudgetAlertWorker.kt` (daily WorkManager job, mirrors `PendingReminderWorker`):
  resolves the current month via `resolveInsightsPeriod(THIS_MONTH)`, reads overall + per-category
  spend (one-shot `getAllTransactionsBetween` aggregated, or `observeCategoryTotalsBetween().first()`)
  and the budgets from `InsightsPrefs`, calls `budgetAlertsToFire`, posts each new alert, and persists
  the fired-keys set in `InsightsPrefs` (or `NotificationPrefs`).
- **Channel + prefs + UI.** New `txtracker.budget` channel; `NotificationPrefs.budgetAlertsEnabled`
  (default off, like the other flows) + a toggle in Settings → Notifications; `NotificationScheduler`
  reconciles the worker against the toggle on app start / pref change.
- **Content + deep-link.** "Food: RM 320 / RM 300 (107%)" / "80% of your monthly budget" → tap opens
  Insights → Budget (shared groundwork above).
- **P2 (cheap win).** `SummaryWorker`/`Notifications` already sends "spent X this period"; when an
  overall budget is set, append "· RM Z left (NN%)". Pure text enrichment of an existing flow.

**Files.** `notify/BudgetAlerts.kt` (new, pure), `notify/BudgetAlertWorker.kt` (new),
`service/NotificationChannels.kt`, `service/NotificationPrefs.kt`, `notify/NotificationScheduler.kt`,
`notify/Notifications.kt` (builder + summary text), `ui/settings/notifications/NotificationsScreen.kt`,
plus the shared deep-link files. Reuses `InsightsPrefs`, `resolveInsightsPeriod`, repository totals.

**Effort.** M (~1.5 days). **Open decision:** daily check (simple, matches existing flows —
recommended) vs. on-transaction-commit via Room `InvalidationTracker` debounce (near-real-time, like
cloud sync, more moving parts).

---

## 2. Tap-to-drill-down — C1 — ✅ implemented

**Why.** Charts are read-only; tapping a category/source to see the transactions behind it is the
natural next gesture and was in the original charts sketch (tappable pie slice).

**Design.**

- **Tap surfaces.** Make `BreakdownLegend` rows **clickable** (works for both pie and bar uniformly,
  no Vico tap-target API needed), plus hit-test the **pie** in `CategoryPieChart` (`detectTapGestures`
  → compute the tapped angle relative to centre → find the containing slice). Per-bar tap on the Vico
  column chart is deferred (the legend covers it).
- **Target = bottom sheet over Insights.** Tapping a series key opens a `ModalBottomSheet` listing
  the transactions for that key **within the currently selected range** (the key point — Home can't do
  this, it's month-scoped). Reuses the shared `TransactionList` composable; tapping a row opens the
  existing `EditTransactionSheet`.
- **Plumbing.** `InsightsViewModel` keeps the in-range rows it already fetches; add a
  `drillKey: StateFlow<String?>` and expose the filtered `List<TransactionWithCategory>` for that key
  (category id or funding kind) in the Loaded state (or a sibling flow). The filter-by-key is a small
  pure function — unit-testable.

**Files.** `ui/insights/charts/CategoryPieChart.kt` (tap hit-test), `ChartTheme.kt`
(`BreakdownLegend` clickable + `onKeyTap`), `InsightsUiState.kt` (+drill data), `InsightsViewModel.kt`
(+`drillKey` + filtered list), `InsightsScreen.kt` / `InsightsRoute.kt` (host the sheet, reuse
`TransactionList` + `EditTransactionSheet`).

**Effort.** M (~1.5 days). **Open decision:** drill target = **bottom sheet over Insights**
(recommended — least disruptive, range-aware) vs. a dedicated filtered sub-screen. (Reusing the Home
tab is rejected: Home is month-scoped, the Insights range isn't.)

---

## 3. Foreign-currency charts — X2 — ✅ implemented

**Why.** Insights is MYR-only; the Foreign tab lists per-currency/per-trip rows but has no charts.
Trip spend benefits from a category breakdown / daily bar in the trip's own currency.

**Design.**

- **No FX / no cross-currency totals** (consistent with the foreign-currency feature's deferral).
  Foreign insights are **per single currency**.
- **Placement = a currency selector on the Insights screen (recommended).** A currency chip/dropdown
  (MYR + each tracked currency). Picking a non-MYR currency swaps the VM's data source from
  `observeMyrTransactionsBetween` to `observeBetweenForCurrency(currency, start, end)` — and that's
  almost the whole change, because **the aggregator is currency-agnostic** (it sums
  `chartAmountMinor` over whatever rows it's given). The pie / daily bar / trends all "just work".
- **Formatting.** Amounts render in the currency's symbol via `formatAmount(minor, symbol)` +
  `Currencies.CODE_TO_DISPLAY_SYMBOL` instead of `formatMyr`; the chart axis formatter becomes
  symbol-aware (today's `RinggitAxisFormatter` → a `currencyAxisFormatter(symbol)`).
- **Scope.** Budget is MYR-only → hide the Budget chart when a non-MYR currency is selected. Range +
  grouping still apply. Gate the currency selector behind "has ≥1 tracked currency".

**Files.** `ui/insights/InsightsUiState.kt` (+`selectedCurrency`, `+displaySymbol`),
`InsightsViewModel.kt` (currency state → swap the range query; pass symbol; observe tracked
currencies), `InsightsScreen.kt` (currency chip row; symbol-aware totals; hide Budget for non-MYR),
`charts/ChartTheme.kt` + the two Vico charts (symbol-aware axis formatter). The **aggregator is
unchanged**.

**Effort.** L (~2–3 days) — currency plumbing, symbol formatting, and testing across currencies.
**Open decision:** per-currency on the Insights tab (recommended) vs. per-trip charts hosted on the
Foreign tab (more trip-contextual, but duplicates chart hosting).

---

## Suggested order

1. **Budget alerts** — highest value, best fit for "use the notification system", mostly reuse;
   also lays the Insights deep-link groundwork. *(Includes the cheap P2 summary enrichment.)*
2. **Tap-to-drill-down** — high UX value, self-contained, reuses `TransactionList`.
3. **Foreign-currency charts** — biggest; do last once the above settle.

## Open decisions to confirm

1. **Budget alerts trigger:** daily check (recommended) vs on-commit.
2. **Drill-down target:** bottom sheet over Insights (recommended) vs dedicated screen.
3. **Foreign charts placement:** currency selector on Insights (recommended) vs charts on the Foreign tab.
