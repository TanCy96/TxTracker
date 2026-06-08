# Reject stickiness + Undo for "Not a transaction" — Design

Date: 2026-06-08

Two related fixes to the capture/verification flow:

1. **Bug:** rejected packages (e.g. Gmail) keep reappearing on the home page and
   in the pool even after the user rejected them.
2. **Feature:** an Undo affordance for the destructive "Not a transaction" action
   in the edit sheet, to recover from a misclick.

---

## Part 1 — Rejected packages stay rejected

### Root cause

`TxNotificationListener.onNotificationPosted` runs each notification through
`CapturePipeline.decide()`. On a `CaptureDecision.Parsed` outcome (the heuristic
extractor recognized a transaction) the listener does:

```kotlin
is CaptureDecision.Parsed -> {
    val rowId = insert(parsed, packageName, needsVerification = true)
    if (rowId != null) {
        repository.trackPackage(sbn.packageName)   // ← un-rejects the package
    }
}
```

`trackPackage()` (`TransactionRepository.kt:518`) deletes the package from
`rejected_sources` and inserts it into `approved_sources`. So **every time a
notification from a rejected package happens to parse confidently, the package is
silently un-rejected.** Once un-rejected:

- the parsed transaction surfaces on home as a `needsVerification` row, and
- the pool's `PENDING` filter — which hides entries whose package is in
  `rejected_sources` (`TransactionRepository.kt:244-246`) — stops hiding the
  package, so the whole backlog of quietly-captured entries floods back into view.

This is a drift from the original spec
(`docs/superpowers/specs/2026-05-25-capture-pool-design.md:182-187`), whose
listener flow has **no** `trackPackage` call on the parse path. Auto-tracking was
only ever intended on explicit **promote** (`TransactionRepository.kt:587-588`).

### Chosen behavior

Preserve the existing "hidden safety net" design: rejected packages keep being
captured into the pool but stay hidden, never appear on home, and are pruned by
the existing 30-day retention worker. Rejection is reversed **only** by an
explicit user action (un-reject in Tracked Apps, or promote a pool entry).

### Design

Push rejection awareness into `CapturePipeline.decide()` so the policy is
covered by the existing `CapturePipelineTest` unit tests, rather than living
untested in the listener.

1. **New DAO method** — `RejectedSourceDao.isRejected(packageName: String): Boolean`
   (suspend; `SELECT EXISTS(SELECT 1 FROM rejected_sources WHERE packageName = :packageName)`).

2. **`CapturePipeline.decide(...)` gains an `isRejected: Boolean` parameter.**
   When `isRejected == true`:
   - A confident heuristic match returns **`CaptureDecision.Pooled`** built from
     the parsed fields (`amountMinor`, `currency`, `rawText`, and the rewritten
     text), instead of `CaptureDecision.Parsed`. This routes it into the pool
     (hidden) and never reaches the listener's `trackPackage` call.
   - The plain amount path already returns `Pooled` — unchanged.
   - No amount → `Dropped` — unchanged.

   When `isRejected == false`, behavior is exactly as today.

3. **Listener** fetches `isRejected = rejectedSourceDao.isRejected(sbn.packageName)`
   once before calling `decide()`, and passes it in. The `Parsed → trackPackage`
   branch is now only reachable for non-rejected packages.

### Why not gate in the listener directly

Keeping the branch logic in `decide()` keeps it pure and unit-testable. The
listener stays a thin I/O shell.

### Tests (Part 1)

- `CapturePipelineTest`: a heuristic-parseable notification with `isRejected = true`
  returns `Pooled`, not `Parsed`.
- `CapturePipelineTest`: same notification with `isRejected = false` still returns
  `Parsed` (regression guard).
- `CapturePipelineTest`: amount-only and no-amount cases are unaffected by
  `isRejected`.

---

## Part 2 — Undo for "Not a transaction"

### Scope

Only the edit-sheet "Not a transaction" button
(`EditTransactionSheet.kt:481`), which today calls `EditTransactionViewModel.delete`
→ `repository.delete(txId)` — a **hard delete** with no recovery. The pool's
"mark as noise" action is already reversible and is out of scope.

### Pattern

Optimistic delete + 5-second snackbar Undo. (A deferred "delete after a timer"
approach was rejected: it fights the Compose/bottom-sheet lifecycle and needs
extra home-list filtering state. Optimistic-delete-then-restore is the standard,
simpler pattern.)

### Flow

1. User taps "Not a transaction".
2. `EditTransactionViewModel.delete` snapshots the row — both the `Transaction`
   and its reimbursement entries are already held in `EditUiState.Editing`, so no
   extra DB read — then hard-deletes and reports the snapshot upward.
3. The sheet dismisses.
4. `HomeRoute` shows a snackbar: `Transaction deleted · Undo`.
5. Tapping **Undo** re-inserts the `Transaction` with its **original id** plus any
   child reimbursement entries (original id matters so child rows re-link).

### Components

1. **`TransactionRepository.restoreTransaction(tx: Transaction, reimbursements: List<ReimbursementEntry>)`**
   — atomic (`database.withTransaction`) re-insert that preserves the original id.
2. **`EditTransactionViewModel.delete`** — captures the snapshot from the current
   `EditUiState.Editing` state, deletes, and surfaces the snapshot via its
   `onDone`/callback so the host can offer Undo.
3. **`EditTransactionSheet`** — new `onDeleted: (DeletedTransaction) -> Unit`
   callback parameter (carrying the snapshot); the "Not a transaction" button
   wires delete → report → dismiss.
4. **`HomeRoute` / `HomeScreen`** — hoist a `SnackbarHostState` into the existing
   `Scaffold` (`HomeScreen.kt:126`) and trigger the Undo snackbar when a deletion
   is reported.
5. **`HomeViewModel.restoreTransaction(snapshot)`** — delegates to the repository.

A small carrier type (e.g. `DeletedTransaction(tx, reimbursements)`) passes the
snapshot from the sheet up to `HomeRoute`.

### Tradeoff

With optimistic delete, if the app process is killed within the 5-second window
the row is gone permanently. Acceptable: this guards against a *misclick*, it is
not a full trash/recovery system.

### Tests (Part 2)

- Repository test: `restoreTransaction` re-inserts the transaction with the same
  id and restores its reimbursement entries.
- ViewModel test: `delete` surfaces a snapshot containing the transaction and its
  reimbursement entries.

---

## Out of scope

- Undo for the pool "mark as noise" action (already reversible).
- A persistent trash / recycle-bin surface.
- Changing the 30-day retention policy for rejected-package pool entries.
