# Design: Home open-transaction UX + Maybank parsing fixes

**Date:** 2026-06-30
**Status:** Approved (design)
**Scope:** Three independent fixes, one spec. Each can be implemented and tested in isolation.

## Summary

1. **Pin the action bar** on the Home open-transaction sheet so the buttons are always
   visible and the sheet can't be dismissed by swipe/scrim — only by a button.
2. **Reorder** the transaction detail page so Category, Description, and Note sit directly
   below Merchant.
3. **Fix Maybank merchant truncation** generically in the heuristic parser so
   "spent at X **with your** card" captures only `X`.

Standing constraints: do not run device/emulator tests (`connectedDebugAndroidTest`); gating
is compile + `testDebugUnitTest` only. Commit per task completion on the current branch.

---

## Fix 1 — Pin action bar on Home's open transaction

**File:** `app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt`

### Current behavior
- `EditTransactionSheet()` (~lines 82–139) wraps content in a full-height `ModalBottomSheet`
  (`rememberModalBottomSheetState(skipPartiallyExpanded = true)`).
- `EditingContent()` (~lines 159–567) renders all fields **and** the footer buttons inside a
  single `Column` with `.verticalScroll(rememberModalBottomSheetState…)` (~lines 213–221).
- Footer buttons (~lines 542–566) are at the **end** of that scrollable column, so they scroll
  out of view. Labels are state-dependent:
  - `tx.needsVerification` → **"Not a transaction"** (error TextButton) + **"Confirm"** (Button)
  - otherwise → **"Delete"** (error TextButton) + **"Done"** (TextButton)
- Sheet dismisses on swipe-down and scrim tap (standard `ModalBottomSheet`).

### Change
- Restructure the sheet body into two regions:
  - **Scrollable field area:** the existing fields move into an inner `Column` with
    `Modifier.weight(1f).verticalScroll(...)`.
  - **Pinned footer:** the footer button `Row` moves out of the scroll region into a sibling
    below it, so it never scrolls.
- **Keep existing state-aware labels** (decision): needs-verification → "Not a transaction" +
  "Confirm"; saved → "Delete" + "Done". No label/text changes.
- **Block gesture dismissal** (decision: "block swipe"):
  - Swipe-down: `rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })`.
  - Scrim tap: must **not** close the sheet. Note `ModalBottomSheet`'s single `onDismissRequest`
    fires for scrim tap, back press, *and* settle-to-hidden alike and cannot distinguish origin —
    so the exact mechanism is an implementation detail to resolve during planning (candidates:
    swallow `onDismissRequest` entirely and close only via button handlers; or set
    `properties = ModalBottomSheetProperties(...)` and a transparent/non-dismissing scrim).
  - Button handlers (`onClose`, `onDelete`, `onConfirmVerification`) remain the way to close,
    via the existing `editingTxId = null` path in `HomeRoute`.
  - **Decision needed at planning time:** whether the system back button still closes the sheet.
    Default preference: keep back working; disabling it is out of scope unless requested. If the
    chosen mechanism (swallowing `onDismissRequest`) also blocks back, surface that tradeoff.
- Apply `imePadding()` to the outer container so the pinned footer floats above the soft
  keyboard when a text field is focused.

### Acceptance criteria
- Opening a transaction from Home shows the action buttons pinned to the bottom regardless of
  scroll position.
- Swiping the sheet down and tapping the scrim do **not** close it.
- Tapping a footer button closes the sheet as before.
- With the keyboard open, the pinned footer sits above the keyboard.
- Existing `EditTransactionSheet` / Home tests still pass.

---

## Fix 2 — Reorder transaction detail fields

**File:** `app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt` (`EditingContent()`)

### Current order (top → bottom)
Header (date/amount) → Merchant (~248–257) → Currency → Funding Source → (SL Debit, conditional)
→ (Reimbursement, conditional) → **Category (~484–490)** → **Description (~492–502)** →
**Note (~504–514)** → (Improve Parsing button, conditional) → footer.

### Change
Move the Category, Description, and Note blocks up to sit **immediately below Merchant**, giving:

> Header → Merchant → **Category → Description → Note** → Currency → Funding Source →
> (SL Debit) → (Reimbursement) → (Improve Parsing button) → footer

- Adjust the surrounding `HorizontalDivider`s so section separation still reads cleanly after
  the move (e.g., divider after the Note block, before Currency).
- The "Improve parsing for this app" button stays in its current location.

### Why this is safe
The merchant-note persistence order lives in the `DisposableEffect` (~lines 196–211) and is
determined by the **code order inside that block** (merchant saved first, then description, then
note — keyed on `merchantNormalized`), which runs on dispose. It is **independent of visual
layout**, so moving the composables changes nothing about persistence.

### Acceptance criteria
- Category, Description, and Note render directly under Merchant.
- Editing both merchant and note in one session still saves correctly (note sees updated
  merchant row).
- No behavioral change to any field; only vertical position changes.

---

## Fix 3 — Generic Maybank merchant-truncation fix

**File:** `app/src/main/java/cy/txtracker/parsing/HeuristicExtractor.kt`

### Root cause
The `"at MERCHANT"` regex (~line 174, in `RECIPIENT_PATTERNS`) is:

```
\bat\s+(?<merchant>[^\.\n,]+?)(?=\s+(?:for|on|via|using|by|accepted|successfully|completed|processed)\b|[\.\n,]|\s*$)
```

The terminator lookahead does not include `with`, so for Maybank's
`"...spent RM 28.20 at ILOVEYOO] PAV DAMANSAR with your Maybank Debit Card MasterCard ending XXXX. View your receipt now"`
the non-greedy capture runs past `"with your..."` to the final period, yielding the whole tail
as the merchant.

### Change
Add `with` to the terminator alternation:

```
\bat\s+(?<merchant>[^\.\n,]+?)(?=\s+(?:for|on|via|using|by|with|accepted|successfully|completed|processed)\b|[\.\n,]|\s*$)
```

Result: merchant = `ILOVEYOO] PAV DAMANSAR`, amount = `2820` minor units. Fixes every bank using
the "spent at X **with your** card" phrasing without per-user training.

### Tradeoff (accepted)
A merchant literally containing `" with "` (e.g. `"BED BATH WITH BEYOND"`) would truncate at
`" with "`. Rare; acceptable for the gain. The `]` artifact in `ILOVEYOO]` is left as-is
(display handled by `MerchantNormalizer`); out of scope to strip.

### Tests
- Add a Maybank regression test in
  `app/src/test/java/cy/txtracker/parsing/HeuristicExtractorTest.kt` using the exact example
  text, asserting `merchantRaw == "ILOVEYOO] PAV DAMANSAR"` and `amountMinor == 2820L`.
- Run `testDebugUnitTest` to confirm the existing 26 cases (esp. the `at MERCHANT … on <date>`
  case) still pass.

### Acceptance criteria
- The Maybank example parses to merchant `ILOVEYOO] PAV DAMANSAR`.
- No regression in existing `HeuristicExtractorTest` cases.

---

## Out of scope
- Stripping the `]`/bracket artifacts from merchant raw text.
- Adding a Maybank-specific `PackageTextRewrite` rule (generic fix chosen instead).
- Disabling the system back button on the open-transaction sheet.
- Any change to the Pool screen's equivalent sheet (unless it shares the same composable; verify
  during planning).

## Verification plan
- Compile: assemble/compile gate.
- Unit: `testDebugUnitTest` (parser regression + any affected UI logic tests).
- No device/emulator instrumented runs.
