# SL Debit Feature Gating — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide SL Debit behind a persisted runtime flag, unlocked by a version-tap gesture, so it ships inert in every build (one schema, one branch) and is invisible until the developer unlocks it.

**Architecture:** A `@Singleton` `FeatureFlags` (SharedPreferences-backed) holds `slDebitUnlocked` (default false). Settings reveals a hidden Advanced toggle after 7 taps on the version line; the flag gates the three SL Debit entry points (Settings entry, Home balance card, edit-sheet share input). The Room schema is untouched — SL Debit tables/columns stay unconditional, so there's a single schema version line. A guardrail scopes `fallbackToDestructiveMigration()` to debug builds.

**Tech Stack:** Kotlin, Hilt, Jetpack Compose (Material3), kotlinx-coroutines (`StateFlow`), Room, JUnit + Truth.

**Spec:** `docs/superpowers/specs/2026-06-08-sl-debit-feature-gating-design.md`

**Branch:** Implement on `feature/share-debit` (current branch). Commit each task; do NOT push. Windows/PowerShell; gradle wrapper `.\gradlew.bat`. Never run `connectedDebugAndroidTest` / an emulator — only `testDebugUnitTest` and compile gates.

**Testing convention:** Pure logic is unit-tested (`testDebugUnitTest`, Truth). Context-bound prefs classes (e.g. `CurrencyPrefs`, `LockPrefs`) and ViewModel/Compose wiring are NOT unit-tested here — verified by compile (`compileDebugKotlin`) + manual. This plan follows that.

---

## File Map

- Create: `app/src/main/java/cy/txtracker/service/FeatureFlags.kt` — persisted `slDebitUnlocked` flag.
- Create: `app/src/main/java/cy/txtracker/ui/settings/UnlockTap.kt` — pure version-tap counter logic.
- Test: `app/src/test/java/cy/txtracker/ui/settings/UnlockTapTest.kt`.
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt` — expose flag + setter.
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt` — version-tap, Advanced section, gate SL Debit entry.
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt` — expose flag.
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt` — gate Home balance card.
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt` — flag into Editing state.
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt` — gate share input.
- Modify: `app/src/main/java/cy/txtracker/di/DatabaseModule.kt` — `fallbackToDestructiveMigration()` debug-only.

**Out of scope (operational, not code):** the branch collapse (merge `feature/share-debit` → `main`, retire the branch). Described in the appendix; not a task here.

---

## Task 1: FeatureFlags store

**Files:**
- Create: `app/src/main/java/cy/txtracker/service/FeatureFlags.kt`

No unit test — Context-bound prefs class, same convention as `CurrencyPrefs.kt` (which has no test). Verified by compile + later usage.

- [ ] **Step 1: Create the file**

```kotlin
package cy.txtracker.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted runtime feature flags. Flags gate UI/behavior ONLY — never the Room schema —
 * so the SL Debit tables stay unconditional and the database has a single version line.
 *
 * [slDebitUnlocked] defaults to false: SL Debit is hidden and unreachable until the developer
 * unlocks it via the hidden version-tap gesture in Settings. Mirrors the [CurrencyPrefs]
 * SharedPreferences pattern.
 */
@Singleton
class FeatureFlags @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _slDebitUnlocked = MutableStateFlow(prefs.getBoolean(KEY_SL_DEBIT, false))
    val slDebitUnlocked: StateFlow<Boolean> = _slDebitUnlocked.asStateFlow()

    fun setSlDebitUnlocked(value: Boolean) {
        prefs.edit().putBoolean(KEY_SL_DEBIT, value).apply()
        _slDebitUnlocked.value = value
    }

    private companion object {
        const val FILE = "feature_flags"
        const val KEY_SL_DEBIT = "sl_debit_unlocked"
    }
}
```

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Hilt provides it via constructor injection — no module needed, same as `CurrencyPrefs`.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/cy/txtracker/service/FeatureFlags.kt
git commit -m "FeatureFlags: persisted slDebitUnlocked flag (default off)"
```

---

## Task 2: Pure version-tap counter

**Files:**
- Create: `app/src/main/java/cy/txtracker/ui/settings/UnlockTap.kt`
- Test: `app/src/test/java/cy/txtracker/ui/settings/UnlockTapTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/cy/txtracker/ui/settings/UnlockTapTest.kt`:

```kotlin
package cy.txtracker.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnlockTapTest {

    @Test
    fun early_taps_do_not_unlock_and_show_no_hint() {
        val r = registerUnlockTap(currentCount = 0, alreadyUnlocked = false)
        assertThat(r.unlocked).isFalse()
        assertThat(r.newCount).isEqualTo(1)
        assertThat(r.hintRemaining).isNull()
    }

    @Test
    fun last_two_taps_before_threshold_emit_a_hint() {
        // threshold 7: after the 5th tap, 2 remain; after the 6th, 1 remains.
        val fifth = registerUnlockTap(currentCount = 4, alreadyUnlocked = false)
        assertThat(fifth.unlocked).isFalse()
        assertThat(fifth.hintRemaining).isEqualTo(2)

        val sixth = registerUnlockTap(currentCount = 5, alreadyUnlocked = false)
        assertThat(sixth.unlocked).isFalse()
        assertThat(sixth.hintRemaining).isEqualTo(1)
    }

    @Test
    fun seventh_tap_unlocks_and_resets_count() {
        val r = registerUnlockTap(currentCount = 6, alreadyUnlocked = false)
        assertThat(r.unlocked).isTrue()
        assertThat(r.newCount).isEqualTo(0)
        assertThat(r.hintRemaining).isNull()
    }

    @Test
    fun taps_when_already_unlocked_are_noops() {
        val r = registerUnlockTap(currentCount = 3, alreadyUnlocked = true)
        assertThat(r.unlocked).isFalse()
        assertThat(r.newCount).isEqualTo(0)
        assertThat(r.hintRemaining).isNull()
    }
}
```

- [ ] **Step 2: Run the test, verify COMPILE FAILURE (`registerUnlockTap` undefined)**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.ui.settings.UnlockTapTest"`

- [ ] **Step 3: Implement**

Create `app/src/main/java/cy/txtracker/ui/settings/UnlockTap.kt`:

```kotlin
package cy.txtracker.ui.settings

/**
 * Result of one tap on the hidden version-line unlock gesture (Android developer-options style).
 *
 * @property newCount the tap count to carry forward in ephemeral UI state (0 once unlocked or when
 *   already unlocked — nothing persists except the resulting [FeatureFlags] flag).
 * @property unlocked true on the tap that reaches the threshold.
 * @property hintRemaining non-null on the final taps before unlock, for a "N taps away" toast.
 */
data class TapResult(
    val newCount: Int,
    val unlocked: Boolean,
    val hintRemaining: Int?,
)

/**
 * Pure tap accounting. Holds no state; the caller keeps [currentCount] in `remember`. When
 * [alreadyUnlocked] is true the gesture is a no-op. Reaching [threshold] taps returns
 * `unlocked = true` and resets the count.
 */
fun registerUnlockTap(
    currentCount: Int,
    alreadyUnlocked: Boolean,
    threshold: Int = 7,
): TapResult {
    if (alreadyUnlocked) return TapResult(newCount = 0, unlocked = false, hintRemaining = null)
    val next = currentCount + 1
    if (next >= threshold) return TapResult(newCount = 0, unlocked = true, hintRemaining = null)
    val remaining = threshold - next
    val hint = if (remaining in 1..2) remaining else null
    return TapResult(newCount = next, unlocked = false, hintRemaining = hint)
}
```

- [ ] **Step 4: Run the test, verify all 4 PASS**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.ui.settings.UnlockTapTest"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/settings/UnlockTap.kt app/src/test/java/cy/txtracker/ui/settings/UnlockTapTest.kt
git commit -m "Settings: pure version-tap unlock counter"
```

---

## Task 3: Settings — expose flag, version-tap reveal, Advanced toggle, gate the SL Debit entry

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt`

(Compile + manual; no unit test per convention.)

- [ ] **Step 1: Expose the flag from the ViewModel**

In `SettingsViewModel.kt`, add the import near the other `cy.txtracker.service` imports:
```kotlin
import cy.txtracker.service.FeatureFlags
```
Add `featureFlags` to the constructor parameter list (e.g. right after `cloudSyncPrefs: CloudSyncPrefs,`):
```kotlin
    private val featureFlags: FeatureFlags,
```
Add these members near `lockEnabled` (around line 61):
```kotlin
    val slDebitUnlocked: StateFlow<Boolean> = featureFlags.slDebitUnlocked

    fun setSlDebitUnlocked(value: Boolean) = featureFlags.setSlDebitUnlocked(value)
```

- [ ] **Step 2: Collect the flag in SettingsScreen**

In `SettingsScreen.kt`, add alongside the other `collectAsState()` lines (after line 81's `lockEnabled`):
```kotlin
    val slDebitUnlocked by viewModel.slDebitUnlocked.collectAsState()
```

- [ ] **Step 3: Gate the SL Debit list entry**

In `SettingsScreen.kt`, wrap the existing `ListItem` + trailing `HorizontalDivider` for SL Debit (currently at lines 266–272 — the `ListItem` with `headlineContent = { Text("SL Debit") }` and the `HorizontalDivider()` directly after it) in a flag check:
```kotlin
            if (slDebitUnlocked) {
                ListItem(
                    headlineContent = { Text("SL Debit") },
                    supportingContent = { Text("Prepaid pool — deposits, balance, and default share %.") },
                    modifier = Modifier.fillMaxWidth().clickableRow(onSlDebitClick),
                )
                HorizontalDivider()
            }
```

- [ ] **Step 4: Make the version line tappable + add the Advanced section**

In `SettingsScreen.kt`, the "About" section currently renders (lines 377–387):
```kotlin
            SectionHeader("About")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Tally ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```
Replace that block with a tappable version line plus the conditional Advanced section. Add `var versionTaps by remember { mutableIntStateOf(0) }` near the other `remember` state at the top of the composable (around line 96), then:
```kotlin
            SectionHeader("About")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Tally ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable {
                        val r = registerUnlockTap(versionTaps, slDebitUnlocked)
                        versionTaps = r.newCount
                        if (r.unlocked) {
                            viewModel.setSlDebitUnlocked(true)
                            Toast.makeText(context, "Advanced unlocked", Toast.LENGTH_SHORT).show()
                        } else if (r.hintRemaining != null) {
                            Toast.makeText(
                                context,
                                "${r.hintRemaining} tap(s) from Advanced",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }

            if (slDebitUnlocked) {
                SectionHeader("Advanced")
                ListItem(
                    headlineContent = { Text("SL Debit") },
                    supportingContent = { Text("Show the SL Debit prepaid-pool feature.") },
                    trailingContent = {
                        Switch(
                            checked = slDebitUnlocked,
                            onCheckedChange = { viewModel.setSlDebitUnlocked(it) },
                        )
                    },
                )
                HorizontalDivider()
            }
```
Add the imports if missing (check the existing import block first):
```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableIntStateOf
import android.widget.Toast
import cy.txtracker.ui.settings.registerUnlockTap
```
(`context`, `Modifier`, `remember`, `getValue`/`setValue`, `ListItem`, `Switch` may already be imported — only add what's missing. `registerUnlockTap` is in the same package so its import is optional.)

NOTE on re-locking: turning the Advanced switch off sets `slDebitUnlocked = false`, which immediately hides both the SL Debit entry AND the Advanced section itself (the section is `if (slDebitUnlocked)`). That's intended — to re-show it, tap the version line 7× again.

- [ ] **Step 5: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt
git commit -m "Settings: version-tap reveals Advanced SL Debit toggle; gate SL Debit entry"
```

---

## Task 4: Gate the Home SL Debit balance card

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt`

- [ ] **Step 1: Expose the flag from HomeViewModel**

In `HomeViewModel.kt`, add the import:
```kotlin
import cy.txtracker.service.FeatureFlags
```
Add to the constructor (after `deeplinkBus: DeeplinkBus,`):
```kotlin
    private val featureFlags: FeatureFlags,
```
Add this member (e.g. after the `_slDebitName` declaration, around line 77):
```kotlin
    val slDebitUnlocked: StateFlow<Boolean> = featureFlags.slDebitUnlocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)
```
(`stateIn`, `SharingStarted`, `STOP_TIMEOUT_MS` are already used in this file.)

- [ ] **Step 2: Thread it into HomeScreen via HomeRoute**

In `HomeScreen.kt` `HomeRoute` (around line 71, where `val state by viewModel.state.collectAsState()` is), add:
```kotlin
    val slDebitUnlocked by viewModel.slDebitUnlocked.collectAsState()
```
Then in the `HomeScreen(...)` call (around line 76), pass it — add this argument near `onSlDebitClick = onSlDebitClick`:
```kotlin
        slDebitUnlocked = slDebitUnlocked,
```

- [ ] **Step 3: Add the param to HomeScreen and gate the card**

In `HomeScreen.kt`, the `HomeScreen` composable signature (around line 148, which already has `onSlDebitClick: () -> Unit = {},`) — add:
```kotlin
    slDebitUnlocked: Boolean = false,
```
Then wrap the existing `SlDebitBalanceCard(...)` call (lines 186–190) in a flag check:
```kotlin
            if (slDebitUnlocked) {
                SlDebitBalanceCard(
                    name = state.slDebitName,
                    balanceMinor = state.slDebitBalanceMinor,
                    onClick = onSlDebitClick,
                )
            }
```

- [ ] **Step 4: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt
git commit -m "Home: gate SL Debit balance card behind the unlock flag"
```

---

## Task 5: Gate the edit-sheet SL Debit share input

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt`

- [ ] **Step 1: Add the flag to the Editing state**

In `EditTransactionViewModel.kt`:
- Add import: `import cy.txtracker.service.FeatureFlags`
- Add `private val featureFlags: FeatureFlags,` to the constructor (after `repository: TransactionRepository,`).
- Add a field to `EditUiState.Editing` (the data class around line 25), after `slDebitAccount`:
```kotlin
        /** When false, the SL Debit share input is hidden (feature gated/locked). */
        val slDebitUnlocked: Boolean = false,
```
- In `load(...)` where `EditUiState.Editing(...)` is constructed (around line 53), set the field:
```kotlin
                    slDebitUnlocked = featureFlags.slDebitUnlocked.value,
```

- [ ] **Step 2: Gate the share block in the sheet**

In `EditTransactionSheet.kt`, the SL Debit share block currently starts (line 357):
```kotlin
        if (tx.currency == "MYR") {
            Text(text = "Share with SL Debit", style = MaterialTheme.typography.labelLarge)
```
Change the condition to also require the flag (the `state` here is `EditUiState.Editing`, which now carries `slDebitUnlocked`):
```kotlin
        if (tx.currency == "MYR" && state.slDebitUnlocked) {
            Text(text = "Share with SL Debit", style = MaterialTheme.typography.labelLarge)
```
Leave the entire block body unchanged.

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt
git commit -m "Edit sheet: gate SL Debit share input behind the unlock flag"
```

---

## Task 6: Guardrail — scope `fallbackToDestructiveMigration()` to debug builds

**Files:**
- Modify: `app/src/main/java/cy/txtracker/di/DatabaseModule.kt`

- [ ] **Step 1: Make the destructive fallback debug-only**

In `DatabaseModule.kt`, add the import (near the top with the other `cy.txtracker` imports):
```kotlin
import cy.txtracker.BuildConfig
```
The builder currently ends (around lines 98–100):
```kotlin
            )
            .fallbackToDestructiveMigration()
            .build()
```
Replace those three lines with a conditional that only applies the destructive fallback in debug builds, so release builds throw a loud Room error on an unhandled migration instead of silently wiping user data:
```kotlin
            )
            .apply {
                // DEBUG only: a missing/incompatible migration recreates the DB destructively,
                // convenient while iterating. RELEASE deliberately omits this so an unhandled
                // migration fails loudly (catchable) instead of silently wiping user data —
                // the cause of the prior data-loss incident.
                if (BuildConfig.DEBUG) fallbackToDestructiveMigration()
            }
            .build()
```

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Full unit-test regression**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (all tests pass, including `UnlockTapTest`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/cy/txtracker/di/DatabaseModule.kt
git commit -m "DB: scope fallbackToDestructiveMigration to debug builds (no silent release wipes)"
```

---

## Manual verification (developer, on device — not run by the agent)

1. Fresh/locked build: SL Debit appears **nowhere** — no Settings entry, no Home balance card, no "Share with SL Debit" in the edit sheet.
2. In Settings, tap the `Tally …` version line 7×: toasts count down the last couple taps; on the 7th, "Advanced unlocked" appears and an **Advanced** section with an SL Debit switch shows.
3. With the switch ON: the Settings SL Debit entry, Home balance card, and edit-sheet share input all appear and work.
4. Toggle the switch OFF: all three vanish again, and the Advanced section hides. Tapping the version line 7× re-reveals it.
5. Kill and relaunch the app: the unlocked/locked state persists (it's in SharedPreferences).
6. Confirm a normal update does NOT wipe data (single schema; nothing schema-related changed in this plan).

---

## Appendix — Branch collapse (operational, do AFTER this plan lands)

Not code; a one-time git/release step:
1. Verify SL Debit is hidden by default in a release build of `feature/share-debit` (manual checks above).
2. Standardize on the `feature/share-debit` **v14** schema lineage as canonical (it's the superset).
3. Merge `feature/share-debit` into `main` (one-time; reverses the old "never merge to main" policy, now obsolete).
4. Retire `feature/share-debit`; do all future work on `main`. SL Debit changes are ordinary flag-gated commits.
5. Remove/replace the obsolete `project_share_debit_branch_policy` memory note.

Caveat (from spec): the unified build uses the v14 share-debit schema. The developer's install is already on that lineage → no wipe. Any device still on an old *main-lineage* DB would wipe once on first update, then be stable.

---

## Self-Review

- **Spec coverage:** flag store → Task 1; unlock gesture → Tasks 2 (logic) + 3 (UI); Advanced toggle/re-lock → Task 3; gate entry points (Settings/Home/edit-sheet) → Tasks 3/4/5; unconditional schema → unchanged by design (no task needed — explicitly nothing touches schema); debug-only destructive fallback guardrail → Task 6; branch collapse → appendix (operational). ✓
- **Placeholder scan:** every step has concrete code/commands; "add the import if missing" instructions name the exact imports. No TBD/TODO. ✓
- **Type consistency:** `FeatureFlags.slDebitUnlocked: StateFlow<Boolean>` + `setSlDebitUnlocked(Boolean)` used identically in Tasks 1/3/4/5. `registerUnlockTap(currentCount, alreadyUnlocked, threshold=7): TapResult(newCount, unlocked, hintRemaining)` consistent between Tasks 2 and 3. `EditUiState.Editing.slDebitUnlocked: Boolean` consistent between Task 5 steps. `HomeScreen(... slDebitUnlocked: Boolean)` consistent between Task 4 steps. ✓
