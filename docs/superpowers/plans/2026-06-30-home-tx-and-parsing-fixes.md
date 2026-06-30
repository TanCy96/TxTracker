# Home open-transaction UX + Maybank parsing fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pin the action bar on Home's open-transaction sheet (no gesture dismissal), reorder the transaction detail fields, and fix Maybank merchant truncation in the heuristic parser.

**Architecture:** Three independent edits. (1) A pure-regex parser fix with a JVM unit test (true TDD). (2)+(3) Two Jetpack Compose layout changes in one file — `EditTransactionSheet.kt` — gated by compile + existing unit suite + a manual verification checklist (instrumented Compose UI tests require a device and are out of scope per project policy).

**Tech Stack:** Kotlin, Jetpack Compose (Material3 `ModalBottomSheet`), Hilt, JUnit + Truth (JVM unit tests under `app/src/test`), Gradle.

## Global Constraints

- **No device/emulator tests.** Never run `connectedDebugAndroidTest` or instrumented Compose tests. Gating is compile (`:app:compileDebugKotlin`) + `:app:testDebugUnitTest` only.
- **Commit per task completion**, on the **current branch** (`main`). Never `git checkout -b`, never `cd` before commands (working dir is already the repo root).
- Commit message trailer (every commit): `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- Maybank merchant for the canonical example must parse to exactly `ILOVEYOO] PAV DAMANSAR` (the `]` artifact is kept; normalization handles display).
- Footer button labels are unchanged: needs-verification → `Not a transaction` + `Confirm`; saved → `Delete` + `Done`.
- "Only a button closes the open-transaction sheet" — swipe-down, scrim tap, and back all stop dismissing it (per the user's explicit choice).

---

### Task 1: Fix Maybank merchant truncation in the heuristic parser

**Files:**
- Modify: `app/src/main/java/cy/txtracker/parsing/HeuristicExtractor.kt:174`
- Test: `app/src/test/java/cy/txtracker/parsing/HeuristicExtractorTest.kt`

**Interfaces:**
- Consumes: `HeuristicExtractor().extract(text: String, packageName: String, now: Instant): ExtractResult?` — result exposes `merchantRaw: String`, `amountMinor: Long`, `direction: Direction` (existing API, unchanged).
- Produces: no new symbols. Behavior change only: the `"at MERCHANT"` recipient pattern now terminates the merchant capture at a following `with` word.

- [ ] **Step 1: Write the failing test**

Add to `HeuristicExtractorTest.kt` (after the existing `handles_at_merchant_bank_format` test, ~line 49):

```kotlin
    @Test
    fun handles_maybank_spent_at_merchant_with_card_clause() {
        // Maybank's "spent at X with your <card>" form: the merchant must stop at " with",
        // not run all the way to the final period (regression: it captured up to "ending XXXX").
        val text = "Maybank2u: Card Transaction You've just spent RM 28.20 at " +
            "ILOVEYOO] PAV DAMANSAR with your Maybank Debit Card MasterCard ending XXXX. " +
            "View your receipt now"
        val r = extractor.extract(text, "com.maybank2u.life", now)!!
        assertThat(r.merchantRaw).isEqualTo("ILOVEYOO] PAV DAMANSAR")
        assertThat(r.amountMinor).isEqualTo(2820L)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.parsing.HeuristicExtractorTest.handles_maybank_spent_at_merchant_with_card_clause"`
Expected: FAIL — `merchantRaw` is the long string `ILOVEYOO] PAV DAMANSAR with your Maybank Debit Card MasterCard ending XXXX` instead of `ILOVEYOO] PAV DAMANSAR`.

- [ ] **Step 3: Add `with` to the "at MERCHANT" terminator lookahead**

In `HeuristicExtractor.kt`, the `"at MERCHANT"` pattern is currently (line 174):

```kotlin
            // "at MERCHANT" — store-style ("paid at COFFEE BEAN")
            Regex(
                """\bat\s+(?<merchant>[^\.\n,]+?)(?=\s+(?:for|on|via|using|by|accepted|successfully|completed|processed)\b|[\.\n,]|\s*$)""",
                RegexOption.IGNORE_CASE,
            ),
```

Change the alternation to add `with` (insert after `by`):

```kotlin
            // "at MERCHANT" — store-style ("paid at COFFEE BEAN"). `with` terminates the
            // merchant for bank "spent at X with your <card>" forms (e.g. Maybank).
            Regex(
                """\bat\s+(?<merchant>[^\.\n,]+?)(?=\s+(?:for|on|via|using|by|with|accepted|successfully|completed|processed)\b|[\.\n,]|\s*$)""",
                RegexOption.IGNORE_CASE,
            ),
```

Only this one pattern changes. Leave the `to MERCHANT` pattern (line 169) untouched — out of scope.

- [ ] **Step 4: Run the new test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.parsing.HeuristicExtractorTest.handles_maybank_spent_at_merchant_with_card_clause"`
Expected: PASS.

- [ ] **Step 5: Run the full parser suite — verify no regressions**

Run: `./gradlew :app:testDebugUnitTest --tests "cy.txtracker.parsing.HeuristicExtractorTest"`
Expected: PASS (all existing cases, including `handles_at_merchant_bank_format` and `handles_charged_at_merchant_format`, still green — none contain a " with " clause).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/cy/txtracker/parsing/HeuristicExtractor.kt app/src/test/java/cy/txtracker/parsing/HeuristicExtractorTest.kt
git commit -m "Parser: stop 'at MERCHANT' capture at 'with' (Maybank truncation)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Reorder transaction detail fields (Category/Description/Note below Merchant)

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt` — `EditingContent()` body

**Interfaces:**
- Consumes: nothing new. Moves three existing UI blocks within the same `Column`.
- Produces: no symbol changes. The merchant-note save order in the `DisposableEffect` (lines 196–211) is untouched and remains correct because it runs on dispose, independent of visual order.

**Target order inside the scrollable field column:**
Header → Merchant → **Category → Description → Note** → divider → Currency → Funding Source → (SL Debit) → (Reimbursement) → (Improve-parsing button).

- [ ] **Step 1: Remove the Category/Description/Note block from its current location**

Delete these lines from `EditingContent()` (currently lines 480–514) — the leading divider plus the three blocks:

```kotlin
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(text = "Category", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        CategoryPicker(
            categories = state.categories,
            selectedCategoryId = tx.categoryId,
            onCategoryChange = onCategoryChange,
        )

        Spacer(Modifier.height(16.dp))
        Text(text = "Description", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            placeholder = { Text("e.g. lunch, petrol, coffee") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Text(text = "Note about this merchant", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = merchantNote,
            onValueChange = { merchantNote = it },
            placeholder = { Text("e.g. SS15 warung uncle, friend's TnG, …") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
```

After this deletion, the code that previously followed the Note block — the `showRewriteDialog` / "Improve parsing for this app" section (currently lines 516–540) — now sits directly after the Reimbursement section.

- [ ] **Step 2: Insert the three blocks immediately after the Merchant field**

The Merchant field ends at (currently) line 257–258:

```kotlin
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
```

Insert the Category/Description/Note blocks **between the Merchant field's `Spacer(Modifier.height(16.dp))` and the existing `HorizontalDivider()`**, so the result reads:

```kotlin
        // Merchant OutlinedTextField ends here (unchanged) ...
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Text(text = "Category", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        CategoryPicker(
            categories = state.categories,
            selectedCategoryId = tx.categoryId,
            onCategoryChange = onCategoryChange,
        )

        Spacer(Modifier.height(16.dp))
        Text(text = "Description", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            placeholder = { Text("e.g. lunch, petrol, coffee") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Text(text = "Note about this merchant", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = merchantNote,
            onValueChange = { merchantNote = it },
            placeholder = { Text("e.g. SS15 warung uncle, friend's TnG, …") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text(text = "Currency", style = MaterialTheme.typography.labelLarge)
        // ... Currency section continues unchanged ...
```

(The `HorizontalDivider()` that was already after Merchant now sits after the Note block, separating the important fields from Currency. The `Spacer(Modifier.height(16.dp))` + `Text("Currency"...)` that previously followed the divider are unchanged.)

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Fix any unused-import warnings only if they become errors; no imports should change.)

- [ ] **Step 4: Run the unit suite — verify no logic regressions**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. (No UI assertion here; this confirms the module still builds and existing logic tests are green.)

- [ ] **Step 5: Manual verification (no device test — visual confirmation only)**

Confirm by reading the final `EditingContent()` top-to-bottom that the order is: Header → Merchant → Category → Description → Note → divider → Currency → Funding Source → (SL Debit) → (Reimbursement) → (Improve-parsing button) → (pinned footer, added in Task 3). Confirm the `DisposableEffect` block (lines ~196–211) is unchanged.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt
git commit -m "Edit sheet: move Category/Description/Note directly below Merchant

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Pin the action bar and block gesture dismissal on the open-transaction sheet

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt` — `EditTransactionSheet()` (lines ~82–139) and `EditingContent()` (lines ~159–567)

**Interfaces:**
- Consumes: existing `onClose`, `onDelete`, `onConfirmVerification` callbacks (which drive `editingTxId = null` in `HomeRoute`, removing the sheet from composition). Closing is done by removing the composable, NOT by `sheetState.hide()`, so `confirmValueChange` blocking `Hidden` does not interfere with button-driven closing.
- Produces: no signature changes. Footer buttons move out of the scroll region into a pinned bar; gesture dismissal is disabled.

- [ ] **Step 1: Add the `SheetValue` import**

In the import block of `EditTransactionSheet.kt`, add (alphabetically near the other `androidx.compose.material3` imports):

```kotlin
import androidx.compose.material3.SheetValue
import androidx.compose.foundation.layout.fillMaxHeight
```

- [ ] **Step 2: Block swipe-to-hide and scrim/back dismissal in `EditTransactionSheet()`**

Change the sheet-state declaration (line 90) to reject settling to `Hidden`:

```kotlin
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
```

Change `onDismissRequest` (line 93) to a no-op so scrim tap and back press do not close the sheet (only a button closes it, via the callbacks below):

```kotlin
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
    ) {
```

Leave everything inside the `when (val s = state)` block unchanged — the content callbacks (`onClose = onDismiss`, `onConfirmVerification`, `onDelete`) already close the sheet by setting `editingTxId = null` in `HomeRoute`. `MissingContent(onClose = onDismiss)` is also unchanged (it stays dismissible via its own Close button, which is correct).

- [ ] **Step 3: Restructure `EditingContent()` into a scrollable area + pinned footer**

Replace the outer `Column(...) { ... }` (lines 213–566) so the fields scroll inside a weighted inner column and the footer is pinned below. The new skeleton:

```kotlin
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            // imePadding on the outer column keeps the pinned footer above the keyboard;
            // the inner scroll column (weight 1f) shrinks to make room.
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // === MOVE HERE, VERBATIM, the existing field content that currently lives
            //     between line 222 and line 540: the verification banner, header Row,
            //     Merchant, Category/Description/Note (post-Task-2 order), divider,
            //     Currency, Funding Source, SL Debit, Reimbursement, and the
            //     "Improve parsing for this app" button + ImproveParsingDialog block.
            //     Remove the `.verticalScroll(...)` / `.imePadding()` / `.padding(...)`
            //     that used to be on the OUTER column — they now live on the wrappers above. ===
        }

        // Pinned footer — never scrolls.
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        if (tx.needsVerification) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDelete) {
                    Text("Not a transaction", color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = onConfirmVerification) { Text("Confirm") }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onClose) { Text("Done") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
```

Concretely: the field composables that were at lines 223–540 move **verbatim** into the inner `weight(1f)` column; the footer `if (tx.needsVerification) { … } else { … }` block (previously lines 543–564) moves **out** of the scroll region to the position shown above; its old leading `Spacer(Modifier.height(20.dp))` (line 542) is replaced by the `Spacer + HorizontalDivider + Spacer` separator shown above.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If `weight` is unresolved, confirm the inner `Column` is a direct child of the outer `Column` (so it is in `ColumnScope`). If `fillMaxHeight`/`SheetValue` is unresolved, confirm Step 1 imports were added.

- [ ] **Step 5: Run the unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (build + existing logic tests green).

- [ ] **Step 6: Manual verification (no device test — visual/code confirmation)**

Confirm by code inspection:
- The footer `Row` is a sibling of (not inside) the `.verticalScroll(...)` inner column.
- The inner column has `Modifier.weight(1f)` and the scroll modifier; the outer column has `fillMaxHeight()` + `imePadding()`.
- `sheetState` uses `confirmValueChange = { it != SheetValue.Hidden }` and `onDismissRequest = {}`.
- Note in the task summary that **swipe-down, scrim tap, and system back no longer dismiss the sheet** (matches the chosen behavior: only a button closes it). If a back-to-close affordance is later wanted, add `BackHandler { onDismiss() }` inside the content lambda — out of scope here.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt
git commit -m "Edit sheet: pin action bar, block swipe/scrim dismissal on Home open-tx

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Fix 1 (pin action bar + block gesture dismissal) → Task 3. ✓
- Fix 2 (reorder Category/Description/Note below Merchant) → Task 2. ✓
- Fix 3 (generic `with` terminator + Maybank regression test) → Task 1. ✓
- Constraints (no device tests, commit per task on current branch, label/merchant exactness) → Global Constraints + per-task commits. ✓

**Placeholder scan:** No TBD/TODO. The one `// === MOVE HERE … ===` marker in Task 3 Step 3 is an explicit verbatim-move instruction for a 300-line block, not a placeholder — the exact source line range (223–540) and the surrounding skeleton are given.

**Type consistency:** `confirmValueChange = { it != SheetValue.Hidden }`, `onDismissRequest = {}`, `Modifier.weight(1f)`, `fillMaxHeight()`, `imePadding()` are used consistently across steps; imports for `SheetValue` and `fillMaxHeight` are added in Task 3 Step 1. Parser change touches only the line-174 `"at"` pattern; the test asserts the exact constants from Global Constraints (`ILOVEYOO] PAV DAMANSAR`, `2820L`).

**Decision note:** Per the user's explicit selection ("the only way to close is tapping a button"), back-press dismissal is intentionally disabled along with swipe/scrim. This supersedes the spec's softer "keep back working" default; flagged in Task 3 Step 6.
