# App-sent push notifications

**Date:** 2026-05-12
**Scope:** Phases 3a + 3b + 3d of FUTURE.md item 3. Foundation
(POST_NOTIFICATIONS permission, three notification channels, deep-link
plumbing, Settings → Notifications sub-screen) plus the pending-row
reminder and the daily/weekly/monthly spending summary. Phase 3c
(foreign-currency detection notification) is pre-stubbed (channel +
notification id constant) but its producer ships on the FX branch.

---

## Problem

The app consumes notifications from finance apps but never posts any of
its own. Two flows currently bottleneck on the user opening TxTracker:

- A heuristic-captured row needing verification sits in the Pending
  filter for days because the user forgot. The home-screen chip is the
  only signal.
- A user who wants a weekly spending recap has to remember to look at
  the month total. No proactive surface.

A real OS-level notification — same shade the user already checks for
GWallet / CIMB — solves both. The foundation also unblocks the FX-branch
push escalation (item 3c) later.

## Goal

Opt-in notifications for two flows:

1. **Pending verification reminder.** A daily-ish WorkManager job posts
   a single aggregated notification when unverified rows older than 24h
   exist. Tap deep-links to Home with the Pending filter active.
   Swipe-dismiss imposes a 12h cooldown. Auto-cancels when the queue
   clears.
2. **Spending summary.** User picks frequency (Daily / Weekly / Monthly)
   and an hour-of-day. The worker fires once per cadence, computes
   total + top-2 categories for the period, posts a summary.

Both default **off**. Both trigger the `POST_NOTIFICATIONS` request
contextually when first enabled (Android 13+). The channel registration
runs on every cold start regardless.

## Non-goals

- **Foreign-currency detection notification (3c).** Producer lives on
  the FX branch. The channel `txtracker.foreign` and notification id
  `NotificationIds.FOREIGN` are reserved here so the FX merge wires its
  worker into an existing channel without a registration migration.
- **Per-row pending notifications.** Single aggregated only.
- **Onboarding integration.** Permission is requested contextually when
  the user flips a Settings toggle, never on cold start.
- **AlarmManager / precise-hour scheduling.** WorkManager periodic
  flex-window scheduling is good enough for "around the chosen hour".
- **Backup round-trip of notification prefs.** Local state only.
- **Multi-currency summary bodies.** MYR-only until the FX merge.
- **Customizable thresholds** (24h, 12h cooldown). Hardcoded.

---

## Design

### Persistence — `NotificationPrefs`

A new `service/NotificationPrefs.kt` singleton, SharedPreferences-backed,
mirroring `CurrencyPrefs` / `CapturePrefs` / `LockPrefs`.

```kotlin
enum class SummaryCadence { OFF, DAILY, WEEKLY, MONTHLY }

@Singleton
class NotificationPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    val pendingEnabled: StateFlow<Boolean>            // default false
    val pendingDismissedUntil: StateFlow<Instant?>    // null = no cooldown
    val summaryCadence: StateFlow<SummaryCadence>     // default OFF
    val summaryHour: StateFlow<Int>                   // default 20 (8pm)

    fun setPendingEnabled(value: Boolean)
    fun setPendingDismissedUntil(at: Instant?)
    fun setSummaryCadence(cadence: SummaryCadence)
    fun setSummaryHour(hour: Int)
}
```

Dismissal-cooldown state lives here, not in the DB, because it's a UI
artifact, not user data worth round-tripping in backups.

### Channels — `notify/NotificationChannels.kt`

```kotlin
object NotificationChannels {
    const val PENDING = "txtracker.pending"
    const val FOREIGN = "txtracker.foreign"  // FX-branch producer
    const val SUMMARY = "txtracker.summary"

    fun registerAll(context: Context) { /* eager creation of all three */ }
}
```

Called from `TxApp.onCreate()`. Channels are cheap to create (Android
dedupes by id); registering all three eagerly means the FX-branch merge
needs no migration. `PENDING` and `FOREIGN` use
`IMPORTANCE_DEFAULT`; `SUMMARY` uses `IMPORTANCE_LOW` (informational,
no sound/vibration).

### Permission flow

Manifest adds:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

`MainActivity` (already `FragmentActivity`) registers an Activity Result
launcher for `RequestPermission`. A `NotificationPermissionBridge`
`@Singleton` exposes:

```kotlin
@Singleton
class NotificationPermissionBridge @Inject constructor(...) {
    suspend fun request(): Boolean   // returns true on grant or pre-Tiramisu
    fun onResult(granted: Boolean)   // invoked by the activity
}
```

The activity wires the launcher to `onResult`; ViewModels call
`request()` from a coroutine. The bridge avoids leaking the activity
reference into ViewModels.

Android 12 and below: `request()` returns `true` immediately;
POST_NOTIFICATIONS isn't a runtime permission below Tiramisu.

Workers defend at post-time with
`NotificationManagerCompat.from(ctx).areNotificationsEnabled()` — if
the user denied OS-level, the worker no-ops instead of throwing.

### Workers

**`notify/PendingReminderWorker.kt`** — `@HiltWorker` `CoroutineWorker`.
Runs daily-ish (24h interval, 1h flex window).

```
suspend doWork():
    if (!prefs.pendingEnabled) → cancel notification, success
    count = repository.countPendingOlderThan(now - 24h)
    cooledDown = prefs.pendingDismissedUntil != null && now < dismissedUntil
    when:
        count == 0           → cancel notification, clear cooldown, success
        cooledDown           → success (skip posting)
        else                 → post aggregated notification
```

New DAO method:

```kotlin
@Query(
    "SELECT COUNT(*) FROM transactions WHERE needsVerification = 1 AND createdAt < :cutoff"
)
suspend fun countPendingOlderThan(cutoff: Instant): Int
```

`TransactionRepository.countPendingOlderThan(cutoff)` is a thin
passthrough.

**`notify/SummaryWorker.kt`** — `@HiltWorker` `CoroutineWorker`. Runs at
the cadence-configured interval. Body sketch:

```
suspend doWork():
    if (prefs.summaryCadence == OFF) → success
    (start, end, label) = rangeFor(cadence, now)
    rows = repository.getAllTransactionsBetween(start, end)
        .filter { currency == "MYR" && direction == OUT }
    if (rows.isEmpty()) → success     // no spend, skip
    total = rows.sumOf { amountMinor }
    topCategories = top-2 by sum, with category name resolved
    post summary notification (rangeLabel, count, total, topCategories)
    return success
```

`rangeFor(cadence, now)` returns `Triple<Instant, Instant, String>`:

| Cadence  | Range start            | Range end | Label        |
|----------|------------------------|-----------|--------------|
| DAILY    | start-of-day(now)      | now       | "Today"      |
| WEEKLY   | start-of-Monday(week)  | now       | "This week"  |
| MONTHLY  | start-of-month         | now       | "This month" |

All in `MalaysiaTimeZone`.

Top-categories rendering: groups `rows` by `categoryId`, picks the
two largest sums, resolves names via `categoryDao.getById`. Uncategorized
rows roll into an "Uncategorized" bucket.

### Scheduler — `notify/NotificationScheduler.kt`

`@Singleton`. Observes prefs; reconciles `WorkManager` unique-work
state on every change. Started from `TxApp.onCreate()` using
`ProcessLifecycleOwner.lifecycleScope`.

```kotlin
@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext context: Context,
    prefs: NotificationPrefs,
) {
    fun start(scope: CoroutineScope) {
        combine(prefs.pendingEnabled, prefs.summaryCadence, prefs.summaryHour) {
            p, c, h -> Triple(p, c, h)
        }.onEach { (p, c, h) ->
            reconcilePending(p)
            reconcileSummary(c, h)
        }.launchIn(scope)
    }

    private fun reconcilePending(enabled: Boolean) { /* enqueue or cancel */ }
    private fun reconcileSummary(cadence: SummaryCadence, hour: Int) {
        /* if OFF: cancel; else PeriodicWorkRequest with cadence-appropriate
           interval/flex + initialDelay = millisUntilNextFiring(...) */
    }
}
```

WorkManager unique names: `"pending-reminder"`, `"summary"`. Policy:
`ExistingPeriodicWorkPolicy.UPDATE` so re-enqueueing on prefs change
recomputes the schedule. Cadence-to-interval mapping:

- DAILY: 24h interval, 1h flex.
- WEEKLY: 168h (7d) interval, 6h flex.
- MONTHLY: 720h (30d) interval, 24h flex. Calendar months drift; v1 accepts the
  slip. Acceptable since cadence is informational, not date-precise.

`millisUntilNextFiring(cadence, hour)` computes the local-time delay to
the next firing in MalaysiaTimeZone. For WEEKLY, "next firing" means
next Monday at `hour`. For MONTHLY, next 1st-of-month at `hour`.

### Notifications and deep-linking

**`notify/NotificationIds.kt`** — single source of truth:

```kotlin
object NotificationIds {
    const val PENDING = 1001
    const val FOREIGN = 1002  // FX merge populates
    const val SUMMARY = 1003
}
```

**`notify/Notifications.kt`** — pure builder functions:

- `buildPendingNotification(context, count): Notification` — title
  pluralizes ("1 transaction needs verification" vs "5 transactions
  need verification"), body "Tap to review.", channel `PENDING`,
  `setAutoCancel(true)`, tap intent deep-links via `EXTRA_DEEPLINK`,
  delete intent fires `PendingDismissReceiver`.
- `buildSummaryNotification(context, rangeLabel, count, total,
  topCategories): Notification` — title interpolates count + total,
  body uses `BigTextStyle` for the top-categories line, channel
  `SUMMARY`, `setAutoCancel(true)`.

Both use `PendingIntent.FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`.

**Deep-link plumbing**

`MainActivity` gains:

```kotlin
enum class Deeplink(val tag: String) {
    PendingFilter("pending");
    companion object { fun fromTag(tag: String?): Deeplink? = ... }
}
companion object { const val EXTRA_DEEPLINK = "deeplink" }
```

A new `notify/DeeplinkBus.kt` `@Singleton` holds a
`MutableSharedFlow<Deeplink>(replay = 0, extraBufferCapacity = 1,
onBufferOverflow = DROP_OLDEST)`. `MainActivity.onCreate` and
`onNewIntent` read the extra and emit. `AppRoute` collects, dispatching
to `HomeViewModel.setFilter(CurrencyReview)` etc. The shared-flow bus
exists because deeplinks arrive at activity level (before NavHost is
composed) and need replay-or-drop semantics.

**`notify/PendingDismissReceiver.kt`** — `@AndroidEntryPoint`
`BroadcastReceiver`. Single responsibility: write
`prefs.setPendingDismissedUntil(now + 12.hours)`. Registered in the
manifest as `android:exported="false"`.

### UI — Settings → Notifications sub-screen

Routes added to `AppRoute.kt`:

```
SETTINGS_NOTIFICATIONS = "settings/notifications"
```

`SettingsScreen` gains an `onNotificationsClick: () -> Unit` parameter
and a new `ListItem`:

```
Notifications
Pending reminders, spending summaries
```

**`ui/settings/notifications/NotificationsScreen.kt`** +
`NotificationsViewModel.kt`. Layout:

- Top hint banner (only when
  `NotificationManagerCompat.areNotificationsEnabled() == false`):
  *"OS notifications are off for TxTracker. [Open system settings]"*
  Tapping the button launches
  `Settings.ACTION_APP_NOTIFICATION_SETTINGS`.
- **Pending verification** section: header text + body ("Get a
  notification when transactions sit unverified for more than a day.")
  + Switch.
- **Spending summary** section: header text +
  `ExposedDropdownMenuBox` for cadence (Off / Daily / Weekly /
  Monthly) + clickable "Time" row showing `08:00 PM` etc., disabled
  when cadence = OFF. Tapping the time row opens a Material 3
  `TimePicker` wrapped in an `AlertDialog`. Confirm writes
  `summaryHour` (minute ignored for v1).

The OS-disabled banner re-evaluates on `Lifecycle.Event.ON_RESUME` so
toggling OS settings and returning updates the UI without a manual
refresh.

**Permission ask** triggers when a toggle transitions OFF → ON. The
ViewModel calls `permissionBridge.request()` from `viewModelScope`. If
denied, the prefs toggle stays ON (user intent captured) and the
OS-disabled banner becomes visible next time it polls.

### `TxApp.onCreate` changes

Two lines added, after existing setup:

```kotlin
NotificationChannels.registerAll(this)
notificationScheduler.start(ProcessLifecycleOwner.get().lifecycleScope)
```

`notificationScheduler` is `@Inject lateinit`. `cloudSyncScheduler` is
unaffected.

### Manifest changes

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<receiver
    android:name=".notify.PendingDismissReceiver"
    android:exported="false" />
```

No new services. No new providers.

---

## Testing

### Unit (JVM)

- `NotificationsTest` — `buildPendingNotification` title pluralization
  (count = 1 vs N); `buildSummaryNotification` body interpolation;
  pending tap-intent carries `EXTRA_DEEPLINK = "pending"`.
- `SummaryRangeTest` — `rangeFor(cadence, now)` returns correct ranges
  for DAILY / WEEKLY / MONTHLY in MalaysiaTimeZone, including boundary
  cases (now = start-of-day, now = start-of-Monday, now =
  start-of-month).
- `MillisUntilNextFiringTest` — computes the right delay for DAILY at
  20:00 (now = 19:59 vs 20:01), WEEKLY at 20:00 Sunday, MONTHLY at
  20:00 first-of-month.
- `SummaryFormattingTest` — RM-formatting of minor units, pluralization
  of "transactions" / "transaction", top-categories joining.

### Instrumentation (compile-only — not auto-run)

- `PendingReminderWorkerTest` (with `WorkManagerTestInitHelper`) —
  seed DB with rows of varying `createdAt`; run worker; assert
  notification posted with correct count.
- `PendingReminderCooldownTest` — `prefs.pendingDismissedUntil = now +
  6h`; run worker; assert no notification.
- `PendingReminderAutoCancelTest` — zero qualifying rows + pre-posted
  notification; run worker; assert cancellation.
- `SummaryWorkerTest` — seed OUT rows in this-week range; cadence =
  WEEKLY; run worker; assert notification posted on summary channel.
- `SummaryWorkerNoOpWhenOffTest` — cadence = OFF; worker returns
  success without posting.
- `NotificationSchedulerTest` — flip prefs, assert
  `WorkManager.getWorkInfosForUniqueWork(...)` transitions ENQUEUED ↔
  empty.

### Manual smoke (on device, per project policy)

- Fresh install on Android 13+. Settings → Notifications. Toggle
  Pending ON. OS prompt appears.
- Deny prompt → OS-disabled banner shows. Tap "Open system settings",
  grant, return → banner clears.
- Force-run worker with `adb shell cmd jobscheduler run`. Confirm
  aggregated notification posts when stale rows exist. Tap → Home
  opens with Pending filter selected.
- Swipe-dismiss notification. Re-run worker within 12h → no
  notification. After 12h → re-fires.
- Set summary cadence DAILY, time = current hour + ~2 minutes. Wait
  for fire. Confirm body shows correct count / total / top-2
  categories.
- Change summary time from 8pm to 7am → confirm WorkManager reschedules
  cleanly.

---

## Files most likely to change

**New:**
- `service/NotificationPrefs.kt`
- `notify/NotificationChannels.kt`
- `notify/NotificationIds.kt`
- `notify/NotificationPermissionBridge.kt`
- `notify/Notifications.kt` (builder functions)
- `notify/NotificationScheduler.kt`
- `notify/PendingReminderWorker.kt`
- `notify/SummaryWorker.kt`
- `notify/PendingDismissReceiver.kt`
- `notify/DeeplinkBus.kt`
- `ui/settings/notifications/NotificationsScreen.kt`
- `ui/settings/notifications/NotificationsViewModel.kt`

**Modified:**
- `app/src/main/AndroidManifest.xml` — `POST_NOTIFICATIONS`,
  `PendingDismissReceiver` declaration.
- `TxApp.kt` — channel registration + scheduler start.
- `ui/MainActivity.kt` — permission launcher + deeplink extra dispatch.
- `ui/AppRoute.kt` — `SETTINGS_NOTIFICATIONS` route, deeplink
  collection.
- `ui/settings/SettingsScreen.kt` — new "Notifications" list entry +
  callback.
- `data/TransactionDao.kt` — `countPendingOlderThan` query.
- `data/TransactionRepository.kt` — passthrough +
  `getAllTransactionsBetween` (if not already present).
- `ui/home/HomeViewModel.kt` — accept `Deeplink.PendingFilter` as
  filter-change input (already supports `Pending` filter; just needs a
  programmatic setter path).

**Drawable:**
- `res/drawable/ic_notification.xml` — monochrome small-icon. Can
  initially reuse the app's monochrome launcher icon.

---

## Phased implementation

The spec covers 3a + 3b + 3d. Suggested order of work
(implementation-plan input):

1. **`NotificationPrefs`.** SharedPreferences singleton + StateFlow
   mirrors. Unit-tested via in-memory replacement.
2. **`NotificationChannels` + manifest permission.** Channels
   register on cold start. `POST_NOTIFICATIONS` declared.
3. **`NotificationPermissionBridge` + `MainActivity` launcher.**
   Suspend-friendly request API.
4. **`Notifications.kt` builders + `NotificationIds`.** Pure functions,
   unit-testable.
5. **`PendingReminderWorker` + DAO `countPendingOlderThan`.** Worker
   logic + instrumentation tests.
6. **`SummaryWorker` + `rangeFor` + `millisUntilNextFiring`.** Worker
   logic + unit tests for the time math + instrumentation tests.
7. **`PendingDismissReceiver` + manifest entry.** Cooldown wiring.
8. **`NotificationScheduler`.** Reconciliation logic + instrumentation
   tests.
9. **`DeeplinkBus` + `MainActivity` extra dispatch + `AppRoute`
   collect.** Wire pending tap → Home/Pending filter.
10. **`NotificationsScreen` + ViewModel.** Settings sub-screen, OS-
    disabled banner, TimePicker integration.
11. **`SettingsScreen` entry.** New row.
12. **`TxApp.onCreate` integration.** Channel registration + scheduler
    start.

Each step lands in its own commit so review and revert are localized.

---

## Open questions and follow-ups

- **Snooze custom durations.** Currently 12h-after-dismissal is
  hardcoded. If the user wants "remind me tomorrow" vs "remind me in
  an hour", a richer action set on the notification would help. Defer.
- **Multi-currency summary bodies.** Once the FX branch merges, the
  weekly summary could include foreign-currency totals (per currency,
  no MYR conversion). Out of scope here.
- **Insights-screen deep-link from summary.** When item 4 (charts) ships
  a dedicated Insights screen, the summary tap should land there with
  the matching range selected.
- **Daily summary "rest day" suppression.** Today's design fires the
  daily summary even on zero-spend days, then skips because `rows
  isEmpty()`. Could surface a "RM 0 today — nice" message for
  positive reinforcement; opinionated, deferred.
- **Re-notification when count grows.** If the user has been dismissing
  for two days and a third stale row arrives, the count changes from 2
  to 3, but the cooldown still suppresses. Acceptable for v1; a "fire
  again when count crosses N" rule could be added later.
- **POST_NOTIFICATIONS permanently denied state.** Android offers a way
  to detect "don't ask again". The current design just shows the
  OS-disabled banner with a system-settings deep-link — works in all
  states. No special-casing for permanent denial.
