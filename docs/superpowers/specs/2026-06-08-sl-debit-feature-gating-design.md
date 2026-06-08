# Gate SL Debit behind a runtime unlock — Design

Date: 2026-06-08

## Problem

SL Debit lives on a long-lived `feature/share-debit` branch that must repeatedly
merge `main` in. That divergence is painful to maintain and — because the two
branches independently assigned different schemas to the same Room version
numbers (v12, v13) — it caused a silent, destructive data wipe on update (Room
hit `fallbackToDestructiveMigration()` when it could not reconcile the
cross-lineage schema). See the wipe diagnosis discussion; identity-hash proof:
`v12`/`v13` differ between `main` and `feature/share-debit`.

## Goal

Ship SL Debit inside the single public APK but keep it **invisible and
unreachable** until the developer unlocks it on their own device. This collapses
the branch (no more merge ritual) and unifies the schema (no more wipes).

This is **gating, not secrecy** — the SL Debit code may ship in everyone's APK;
it just must not be visible or reachable in the UI when locked.

## Core principle

**Feature flags gate UI and behavior, never the database schema.**

The SL Debit tables and the `transactions.slShareMinor` column stay
unconditionally in the Room schema for every build, locked or not. They are
inert when locked: with the entry points hidden, no SL Debit account or shares
are ever created, so `slShareMinor` stays null and changes no totals, rows, or
charts. One schema → one monotonic version line → updates never trigger
destructive migration again.

## Architecture

### Workstream A — Feature flag + gating (code; spec'd here)

**Flag store.** A persisted boolean `slDebitUnlocked`, default `false`, stored in
the existing app-prefs mechanism (follow the `CurrencyPrefs` SharedPreferences
pattern). Exposed as:
- a `Flow<Boolean>` (or `StateFlow`) the UI observes, and
- a suspend/sync setter to toggle it.

**Unlock gesture.** In Settings, tapping the version line
(`Tally <versionName> (<versionCode>)`, `SettingsScreen.kt:383`) 7 times reveals
a hidden **Advanced** section. Behaviour mirrors Android developer options:
- A tap counter held in ephemeral Compose state (NOT persisted).
- After a few taps, show a countdown toast ("N taps away from Advanced").
- On reaching the threshold, set `slDebitUnlocked = true` (and surface the
  Advanced section). Only the resulting flag persists; the counter resets.

**Advanced section.** A new Settings sub-section, visible only once unlocked,
containing an SL Debit on/off `Switch` bound to `slDebitUnlocked` — so the
developer can re-lock too. (The version-tap is the *reveal*; the switch is the
authoritative control. Tapping to reveal an already-unlocked state just shows
the section.)

**Gated surfaces.** When `slDebitUnlocked == false`, hide the entry points:
- Settings → SL Debit management entry and its navigation route.
- Home → SL Debit balance card.
- Edit sheet → the SL Debit share input.
- Defensively, the Home-row net/original/share triple and the Insights SL Debit
  netting (already inert without SL Debit data, but gate visibility for
  cleanliness/consistency).

The gate reads the same `slDebitUnlocked` flow. Locked users see plain behaviour
identical to a build without the feature.

### Workstream B — Branch collapse (operational; guided, not code)

One-time, separate from the code change:
1. Standardize on the **`feature/share-debit` v14 schema lineage** as canonical
   (it is the superset — already contains the reject/undo work plus SL Debit).
2. Merge `feature/share-debit` into `main` (reversing the old
   "share-debit never merges to main" policy, which becomes obsolete).
3. Retire `feature/share-debit`; do all future work on `main`. SL Debit changes
   become ordinary commits gated by the flag.
4. Update/remove the obsolete branch-policy note.

**Migration caveat:** the unified build uses the share-debit v14 schema. The
developer's install is already on that lineage → updates with no wipe. Any device
still on an old *main-lineage* DB would wipe once on its first update to the
unified build, then be stable. (Acceptable: effectively only the developer's
install matters here.)

### Guardrail (folded in)

Scope `fallbackToDestructiveMigration()` (`DatabaseModule.kt:99`) to **debug
builds only** (gate on `BuildConfig.DEBUG`). Release builds then fail loudly with
a Room exception on an unhandled migration instead of silently wiping user data —
turning the class of bug that caused this incident into a catchable error.

## Components / files (anticipated; finalized in the plan)

- New: a `FeatureFlags`/`DeveloperPrefs` holder (SharedPreferences-backed) exposing
  `slDebitUnlocked`.
- Modify: `SettingsScreen.kt` — version-tap counter, Advanced section, SL Debit
  toggle, gate the SL Debit management entry.
- Modify: Home (SL Debit balance card), edit sheet (SL Debit input), and the
  nav route — gate on the flag.
- Modify: `DatabaseModule.kt` — `fallbackToDestructiveMigration()` debug-only.

## Testing

- Unit-test the tap-counter / unlock-threshold logic (pure) and the flag store
  (read/write/default-false), following the repo's existing unit-test patterns.
- ViewModel/Compose gating verified by compile + manual (repo convention).
- Manual: locked build shows no SL Debit anywhere; 7 taps reveal Advanced; toggle
  shows/hides all SL Debit surfaces; flag persists across app restarts.

## Out of scope

- Secrecy / removing SL Debit code from the APK (explicitly gating, not hiding).
- Remote config or per-user server flags.
- A general multi-flag framework — build exactly the one flag now (YAGNI); a
  second flag can generalize later.
- Backfilling/clearing SL Debit data when re-locking (toggling off just hides UI;
  data persists).
