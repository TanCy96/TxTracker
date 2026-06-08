# Reject Stickiness + Undo for "Not a transaction" — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop rejected notification sources (e.g. Gmail) from silently un-rejecting themselves and reappearing on home/pool, and add an Undo snackbar for the destructive edit-sheet "Not a transaction" delete.

**Architecture:** Part 1 pushes a `isRejected` flag into the pure `CapturePipeline.decide()` so a confidently-parsed notification from a rejected package is routed to the (hidden) pool instead of becoming a home transaction — which also means the listener's auto-track call is never reached for rejected packages. Part 2 changes the edit-sheet delete from fire-and-forget to optimistic-delete-with-snapshot, surfacing the snapshot to `HomeRoute` which shows a snackbar whose Undo action re-inserts the transaction (and its reimbursement children) with the original id.

**Tech Stack:** Kotlin, Room, Hilt, Jetpack Compose (Material3), kotlinx-coroutines, JUnit + Truth + mockk.

**Spec:** `docs/superpowers/specs/2026-06-08-reject-stickiness-and-undo-delete-design.md`

**Testing convention in this repo:** Pure logic and repository logic are JVM unit tests (`testDebugUnitTest`) using Truth + mockk. ViewModels and Compose are NOT unit-tested here; they are verified by compilation (`compileDebugKotlin`) plus manual testing. Per project policy: never run `connectedDebugAndroidTest` or boot an emulator.

**Windows note:** Commands use `.\gradlew.bat`. All paths use backslashes where shown for the shell, forward slashes in `git`.

---

## File Map

**Part 1 — reject stickiness**
- Modify: `app/src/main/java/cy/txtracker/service/CapturePipeline.kt` — `decide()` gains `isRejected: Boolean = false`; pools instead of parsing when rejected.
- Modify: `app/src/test/java/cy/txtracker/service/CapturePipelineTest.kt` — new tests.
- Modify: `app/src/main/java/cy/txtracker/data/RejectedSourceDao.kt` — add `isRejected()`.
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` — add `isPackageRejected()`.
- Modify: `app/src/main/java/cy/txtracker/service/TxNotificationListener.kt` — fetch + pass `isRejected`.
- Modify: `app/src/test/java/cy/txtracker/data/PromotePoolEntryTest.kt` — reuse `makeRepo()`? No — add a tiny separate test file instead (below) to avoid touching the promote test.
- Create: `app/src/test/java/cy/txtracker/data/IsPackageRejectedTest.kt` — repo passthrough test.

**Part 2 — undo delete**
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` — `restoreTransaction()` + `restoreTransactionBody()`.
- Create: `app/src/test/java/cy/txtracker/data/RestoreTransactionTest.kt` — body test.
- Create: `app/src/main/java/cy/txtracker/ui/edit/DeletedTransaction.kt` — snapshot carrier.
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt` — `delete()` surfaces snapshot.
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt` — `onDeleted` callback param + wiring.
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt` — `restoreTransaction()`.
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt` — hoist `SnackbarHostState`, show Undo snackbar.

---

## Task 1: CapturePipeline routes rejected-package parses to the pool

**Files:**
- Modify: `app/src/main/java/cy/txtracker/service/CapturePipeline.kt`
- Test: `app/src/test/java/cy/txtracker/service/CapturePipelineTest.kt`

- [ ] **Step 1: Write the failing tests**

Add these two tests to `CapturePipelineTest` (after the existing `rewritten_text_is_used_for_parsing_and_raw_text_is_retained` test, before the closing brace):

```kotlin
    @Test
    fun rejected_package_parseable_notification_goes_to_pool_not_parsed() {
        val decision = pipeline.decide(
            packageName = "com.google.android.gm",
            rawText = "Paid RM12.00 to Coffee Shop",
            rewrittenText = "Paid RM12.00 to Coffee Shop",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
            isRejected = true,
        )

        assertThat(decision).isInstanceOf(CaptureDecision.Pooled::class.java)
        val pooled = decision as CaptureDecision.Pooled
        assertThat(pooled.packageName).isEqualTo("com.google.android.gm")
        assertThat(pooled.amountMinor).isEqualTo(1200L)
        assertThat(pooled.currency).isEqualTo("MYR")
        assertThat(pooled.rawText).isEqualTo("Paid RM12.00 to Coffee Shop")
        // rawText == rewrittenText, so the stored rewrittenText is null.
        assertThat(pooled.rewrittenText).isNull()
    }

    @Test
    fun non_rejected_package_parseable_notification_still_parses() {
        val decision = pipeline.decide(
            packageName = "com.google.android.gm",
            rawText = "Paid RM12.00 to Coffee Shop",
            rewrittenText = "Paid RM12.00 to Coffee Shop",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
            isRejected = false,
        )

        assertThat(decision).isInstanceOf(CaptureDecision.Parsed::class.java)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.service.CapturePipelineTest"`
Expected: COMPILE FAILURE — `decide()` has no `isRejected` parameter yet.

- [ ] **Step 3: Implement the change**

Replace the body of `CapturePipeline.decide` in `CapturePipeline.kt`. The new signature adds `isRejected: Boolean = false` (defaulted so the four existing tests and any other callers compile unchanged), and a confident heuristic match is converted to a `Pooled` decision when the package is rejected:

```kotlin
    fun decide(
        packageName: String,
        rawText: String,
        rewrittenText: String,
        postedAt: Instant,
        symbolDefaults: Map<String, String>,
        capturedAt: Instant,
        isRejected: Boolean = false,
    ): CaptureDecision {
        val heuristic = heuristicExtractor.extract(
            text = rewrittenText,
            sourceApp = packageName,
            postedAt = postedAt,
            symbolDefaults = symbolDefaults,
        )?.copy(rawText = rawText)
        if (heuristic != null) {
            // Non-rejected: a confident parse becomes a (verifiable) home transaction.
            if (!isRejected) return CaptureDecision.Parsed(heuristic)
            // Rejected: keep the spec's hidden safety net — pool the entry (hidden by the
            // PENDING-minus-rejected filter) instead of surfacing it on home, and never reach
            // the listener's auto-track call.
            return CaptureDecision.Pooled(
                packageName = packageName,
                postedAt = postedAt,
                amountMinor = heuristic.amountMinor,
                currency = heuristic.currency,
                rawText = rawText,
                rewrittenText = rewrittenText.takeIf { it != rawText },
                capturedAt = capturedAt,
            )
        }

        val amount = NotificationAmountParser.findFirst(rewrittenText, symbolDefaults)
            ?: return CaptureDecision.Dropped

        return CaptureDecision.Pooled(
            packageName = packageName,
            postedAt = postedAt,
            amountMinor = amount.amountMinor,
            currency = amount.currency,
            rawText = rawText,
            rewrittenText = rewrittenText.takeIf { it != rawText },
            capturedAt = capturedAt,
        )
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.service.CapturePipelineTest"`
Expected: PASS (all 6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/service/CapturePipeline.kt app/src/test/java/cy/txtracker/service/CapturePipelineTest.kt
git commit -m "Capture: pool (hidden) instead of parsing for rejected packages"
```

---

## Task 2: Wire rejection check into the DAO, repository, and listener

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/RejectedSourceDao.kt`
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` (add `isPackageRejected`)
- Modify: `app/src/main/java/cy/txtracker/service/TxNotificationListener.kt`
- Test: `app/src/test/java/cy/txtracker/data/IsPackageRejectedTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/cy/txtracker/data/IsPackageRejectedTest.kt`:

```kotlin
package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `isPackageRejected` is a thin passthrough to [RejectedSourceDao.isRejected]; this guards the
 * wiring (the listener relies on it to decide whether to pool-instead-of-parse).
 */
class IsPackageRejectedTest {

    private val rejectedDao = mockk<RejectedSourceDao>(relaxed = true)

    @Test
    fun returns_true_when_dao_reports_rejected() = runTest {
        coEvery { rejectedDao.isRejected("com.google.android.gm") } returns true
        assertThat(makeRepo().isPackageRejected("com.google.android.gm")).isTrue()
    }

    @Test
    fun returns_false_when_dao_reports_not_rejected() = runTest {
        coEvery { rejectedDao.isRejected("com.bank") } returns false
        assertThat(makeRepo().isPackageRejected("com.bank")).isFalse()
    }

    private fun makeRepo(): TransactionRepository = TransactionRepository(
        database = mockk(relaxed = true),
        transactionDao = mockk(relaxed = true),
        categoryDao = mockk(relaxed = true),
        merchantMappingDao = mockk(relaxed = true),
        descriptionMappingDao = mockk(relaxed = true),
        merchantNoteDao = mockk(relaxed = true),
        userFacingSourceDao = mockk(relaxed = true),
        approvedSourceDao = mockk(relaxed = true),
        capturedNotificationDao = mockk(relaxed = true),
        rejectedSourceDao = rejectedDao,
        trackedCurrencyDao = mockk(relaxed = true),
        tripWindowDao = mockk(relaxed = true),
        packageTextRewriteDao = mockk(relaxed = true),
        fundingSourceDao = mockk(relaxed = true),
        reimbursementEntryDao = mockk(relaxed = true),
        categorizationEngine = mockk(relaxed = true),
        descriptionEngine = mockk(relaxed = true),
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk(relaxed = true),
    )
}
```

NOTE: if the `TransactionRepository` constructor parameter list differs from the above (it is copied from `PromotePoolEntryTest.makeRepo()` as of this writing), copy the current parameter list from `PromotePoolEntryTest.kt` verbatim.

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.data.IsPackageRejectedTest"`
Expected: COMPILE FAILURE — `RejectedSourceDao.isRejected` and `TransactionRepository.isPackageRejected` do not exist yet.

- [ ] **Step 3: Add the DAO query**

In `RejectedSourceDao.kt`, add this method inside the interface (after `getAllPackageNamesOnce`):

```kotlin
    @Query("SELECT EXISTS(SELECT 1 FROM rejected_sources WHERE packageName = :packageName)")
    suspend fun isRejected(packageName: String): Boolean
```

- [ ] **Step 4: Add the repository passthrough**

In `TransactionRepository.kt`, add next to the other rejected-source methods (just below `observeRejectedPackages`, around line 278):

```kotlin
    /** True when the user has explicitly rejected this package as a notification source. */
    suspend fun isPackageRejected(packageName: String): Boolean =
        rejectedSourceDao.isRejected(packageName)
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.data.IsPackageRejectedTest"`
Expected: PASS.

- [ ] **Step 6: Wire the listener**

In `TxNotificationListener.kt`, inside the `scope.launch { try { ... } }` block, fetch the rejection status and pass it into `decide()`. Replace the `when (val decision = capturePipeline.decide(...))` call's argument list to include `isRejected`. The edited region (starting at the existing `val symbolDefaults = ...` line) becomes:

```kotlin
                val rewritten = rewriteEngine.apply(sbn.packageName, rawText)
                val symbolDefaults = trackedCurrencyDao.getDefaultsForSymbol()
                    .associate { it.displaySymbol to it.code }
                val isRejected = repository.isPackageRejected(sbn.packageName)

                when (val decision = capturePipeline.decide(
                    packageName = sbn.packageName,
                    rawText = rawText,
                    rewrittenText = rewritten,
                    postedAt = postedAt,
                    symbolDefaults = symbolDefaults,
                    capturedAt = Clock.System.now(),
                    isRejected = isRejected,
                )) {
```

Leave the rest of the `when` (the `Parsed`/`Pooled`/`Dropped` branches) unchanged. The `Parsed` branch — which calls `repository.trackPackage(...)` — is now only reachable when `isRejected == false`, so a rejected package can no longer auto-un-reject itself.

- [ ] **Step 7: Compile-gate the listener change**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/cy/txtracker/data/RejectedSourceDao.kt app/src/main/java/cy/txtracker/data/TransactionRepository.kt app/src/main/java/cy/txtracker/service/TxNotificationListener.kt app/src/test/java/cy/txtracker/data/IsPackageRejectedTest.kt
git commit -m "Listener: consult rejected_sources so rejection is never auto-reversed"
```

---

## Task 3: Repository restoreTransaction (re-insert with original id + children)

**Files:**
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt`
- Test: `app/src/test/java/cy/txtracker/data/RestoreTransactionTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/cy/txtracker/data/RestoreTransactionTest.kt`:

```kotlin
package cy.txtracker.data

import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * Drives [TransactionRepository.restoreTransactionBody] directly to avoid mocking Room's
 * `withTransaction` extension (same approach as PromotePoolEntryTest). Asserts the parent
 * transaction is re-inserted before its reimbursement children (FK ordering) and that the
 * original ids are preserved so the children re-link.
 */
class RestoreTransactionTest {

    private val now = Instant.parse("2026-06-08T12:00:00Z")
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val reimbursementDao = mockk<ReimbursementEntryDao>(relaxed = true)

    private val tx = Transaction(
        id = 77L,
        amountMinor = 5000L,
        currency = "MYR",
        merchantRaw = "Coffee Shop",
        merchantNormalized = "COFFEE SHOP",
        categoryId = null,
        description = null,
        occurredAt = now,
        timeBucket = TimeBucket.AFTERNOON,
        sourceApp = "com.bank",
        rawText = "Paid RM50.00 to Coffee Shop",
        direction = Direction.OUT,
        createdAt = now,
        notificationDedupeKey = "dedupe-77",
        needsVerification = true,
    )
    private val entry = ReimbursementEntry(
        id = 5L,
        transactionId = 77L,
        amountMinor = 2000L,
        destinationKind = FundingSourceKind.CASH,
        personLabel = "Alex",
        createdAt = now,
    )

    @Test
    fun restore_reinserts_transaction_then_children_with_original_ids() = runTest {
        makeRepo().restoreTransactionBody(tx, listOf(entry))

        coVerifyOrder {
            txDao.insert(match { it.id == 77L })
            reimbursementDao.insert(match { it.id == 5L && it.transactionId == 77L })
        }
    }

    @Test
    fun restore_with_no_children_only_reinserts_transaction() = runTest {
        makeRepo().restoreTransactionBody(tx, emptyList())

        coVerify(exactly = 1) { txDao.insert(match { it.id == 77L }) }
        coVerify(exactly = 0) { reimbursementDao.insert(any()) }
    }

    private fun makeRepo(): TransactionRepository = TransactionRepository(
        database = mockk(relaxed = true),
        transactionDao = txDao,
        categoryDao = mockk(relaxed = true),
        merchantMappingDao = mockk(relaxed = true),
        descriptionMappingDao = mockk(relaxed = true),
        merchantNoteDao = mockk(relaxed = true),
        userFacingSourceDao = mockk(relaxed = true),
        approvedSourceDao = mockk(relaxed = true),
        capturedNotificationDao = mockk(relaxed = true),
        rejectedSourceDao = mockk(relaxed = true),
        trackedCurrencyDao = mockk(relaxed = true),
        tripWindowDao = mockk(relaxed = true),
        packageTextRewriteDao = mockk(relaxed = true),
        fundingSourceDao = mockk(relaxed = true),
        reimbursementEntryDao = reimbursementDao,
        categorizationEngine = mockk(relaxed = true),
        descriptionEngine = mockk(relaxed = true),
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk(relaxed = true),
    )
}
```

NOTE: if `Transaction`, `ReimbursementEntry`, or the `TransactionRepository` constructor differ from the above, copy current field/parameter names from the source files. `TimeBucket.AFTERNOON` and `FundingSourceKind.CASH` are placeholders — use any valid enum constant (check `Entities.kt` / `FundingSourceKind`).

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.data.RestoreTransactionTest"`
Expected: COMPILE FAILURE — `restoreTransactionBody` does not exist.

- [ ] **Step 3: Implement restoreTransaction + body**

In `TransactionRepository.kt`, add next to the other delete/restore-style methods (e.g. just below `suspend fun delete(txId: Long)` around line 994):

```kotlin
    /**
     * Re-inserts a transaction that was just deleted, preserving its original id so any
     * restored reimbursement children re-link. Parent is inserted before children to satisfy
     * the reimbursement_entries → transactions foreign key. Used by the edit-sheet "Not a
     * transaction" Undo. `transactionDao.insert` uses IGNORE-on-conflict, which is fine here:
     * the row was deleted, so there is no conflict.
     */
    suspend fun restoreTransaction(tx: Transaction, reimbursements: List<ReimbursementEntry>) =
        database.withTransaction { restoreTransactionBody(tx, reimbursements) }

    internal suspend fun restoreTransactionBody(
        tx: Transaction,
        reimbursements: List<ReimbursementEntry>,
    ) {
        transactionDao.insert(tx)
        reimbursements.forEach { reimbursementEntryDao.insert(it) }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "cy.txtracker.data.RestoreTransactionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/data/TransactionRepository.kt app/src/test/java/cy/txtracker/data/RestoreTransactionTest.kt
git commit -m "Repo: restoreTransaction re-inserts a deleted tx with original id + children"
```

---

## Task 4: DeletedTransaction snapshot + EditTransactionViewModel.delete surfaces it

**Files:**
- Create: `app/src/main/java/cy/txtracker/ui/edit/DeletedTransaction.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt`

(No unit test — this repo does not unit-test ViewModels; verified by compile + manual.)

- [ ] **Step 1: Create the snapshot carrier**

Create `app/src/main/java/cy/txtracker/ui/edit/DeletedTransaction.kt`:

```kotlin
package cy.txtracker.ui.edit

import cy.txtracker.data.ReimbursementEntry
import cy.txtracker.data.Transaction

/**
 * In-memory snapshot of a transaction (and its reimbursement children) captured the moment it
 * is deleted via the edit sheet, so the Home screen can offer a 5-second Undo that restores it.
 */
data class DeletedTransaction(
    val transaction: Transaction,
    val reimbursements: List<ReimbursementEntry>,
)
```

- [ ] **Step 2: Change `delete` to surface the snapshot**

In `EditTransactionViewModel.kt`, replace the existing `delete` function:

```kotlin
    fun delete(transactionId: Long, onDeleted: (DeletedTransaction?) -> Unit) {
        viewModelScope.launch {
            // The "Not a transaction" button is only shown over an Editing state, so the
            // snapshot is already in memory — no extra DB read. If state isn't Editing for
            // this id (defensive), we still delete but offer no undo (null snapshot).
            val snapshot = (_state.value as? EditUiState.Editing)
                ?.takeIf { it.transaction.id == transactionId }
                ?.let { DeletedTransaction(it.transaction, it.reimbursements) }
            repository.delete(transactionId)
            onDeleted(snapshot)
        }
    }
```

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: COMPILE FAILURE in `EditTransactionSheet.kt` at the `onDelete` call site (`viewModel.delete(transactionId, onDone = onDismiss)` no longer matches). That is expected and fixed in Task 5. To verify *this* task in isolation, confirm the only error is that call site.

- [ ] **Step 4: Commit (with Task 5)**

Do not commit yet — Task 5 fixes the call site this task breaks. Commit at the end of Task 5.

---

## Task 5: EditTransactionSheet onDeleted callback

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt`

- [ ] **Step 1: Add `onDeleted` to the composable signature**

In `EditTransactionSheet.kt`, change the `EditTransactionSheet` function signature to add an `onDeleted` parameter:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionSheet(
    transactionId: Long,
    onDismiss: () -> Unit,
    onDeleted: (DeletedTransaction) -> Unit = {},
    viewModel: EditTransactionViewModel = hiltViewModel(),
) {
```

- [ ] **Step 2: Wire the delete call site**

In the same file, find the `onDelete = { viewModel.delete(transactionId, onDone = onDismiss) }` block (around line 122) and replace it with:

```kotlin
                onDelete = {
                    viewModel.delete(transactionId) { snapshot ->
                        if (snapshot != null) onDeleted(snapshot)
                        onDismiss()
                    }
                },
```

`DeletedTransaction` is in the same package (`cy.txtracker.ui.edit`), so no import is needed.

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Task 4's break is now resolved; `HomeRoute` still calls `EditTransactionSheet` without `onDeleted`, which is fine because it has a default — it gets wired in Task 6).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/edit/DeletedTransaction.kt app/src/main/java/cy/txtracker/ui/edit/EditTransactionViewModel.kt app/src/main/java/cy/txtracker/ui/edit/EditTransactionSheet.kt
git commit -m "Edit sheet: surface a DeletedTransaction snapshot on delete"
```

---

## Task 6: Home Undo snackbar + restore wiring

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt`

- [ ] **Step 1: Add `restoreTransaction` to HomeViewModel**

In `HomeViewModel.kt`, add an import for the snapshot type at the top with the other imports:

```kotlin
import cy.txtracker.ui.edit.DeletedTransaction
```

Then add this function (e.g. after `dismissBanner`):

```kotlin
    fun restoreTransaction(snapshot: DeletedTransaction) {
        viewModelScope.launch {
            repository.restoreTransaction(snapshot.transaction, snapshot.reimbursements)
        }
    }
```

- [ ] **Step 2: Add the required imports to HomeScreen**

In `HomeScreen.kt`, add these imports alongside the existing Material3 / runtime imports:

```kotlin
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import cy.txtracker.ui.edit.DeletedTransaction
import kotlinx.coroutines.launch
```

- [ ] **Step 3: Hoist a SnackbarHostState in HomeRoute and wire onDeleted**

In `HomeScreen.kt`, in `HomeRoute`, add the snackbar state and coroutine scope near the other `remember` state (after `var tripDialogOffer ...`):

```kotlin
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
```

Pass the host state into `HomeScreen` by adding `snackbarHostState = snackbarHostState,` to the `HomeScreen(...)` call (e.g. right after `state = state,`).

Then update the `EditTransactionSheet` call inside `HomeRoute` to handle deletion:

```kotlin
    editingTxId?.let { id ->
        EditTransactionSheet(
            transactionId = id,
            onDismiss = { editingTxId = null },
            onDeleted = { snapshot ->
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Transaction deleted",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.restoreTransaction(snapshot)
                    }
                }
            },
        )
    }
```

- [ ] **Step 4: Accept the host state in HomeScreen and mount it in the Scaffold**

In `HomeScreen.kt`, add the parameter to the `HomeScreen` composable signature (after `state: HomeUiState,`):

```kotlin
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
```

Then in the `Scaffold(...)` (around line 126), add a `snackbarHost`:

```kotlin
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
```

- [ ] **Step 5: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run the full unit-test suite (regression)**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/home/HomeViewModel.kt app/src/main/java/cy/txtracker/ui/home/HomeScreen.kt
git commit -m "Home: Undo snackbar restores a deleted transaction"
```

---

## Manual verification (post-implementation, on device by the user)

Per project policy these are NOT run by the agent (no emulator/instrumented runs):

1. **Reject stickiness:** Reject Gmail in Tracked Apps. Trigger Gmail notifications (including ones that previously parsed as transactions). Confirm: nothing new appears on Home; Gmail stays in the Rejected list; pool PENDING view does not show Gmail. Un-reject Gmail and confirm the accumulated hidden entries reappear in the pool (safety net intact).
2. **Undo delete:** Open a pending transaction on Home → "Not a transaction". Confirm the row disappears and a "Transaction deleted · Undo" snackbar shows. Tap Undo → the transaction reappears with its category/amount/reimbursements intact. Repeat without tapping Undo → row stays deleted after the snackbar times out.

---

## Self-Review

- **Spec coverage:** Part 1 root-cause fix → Tasks 1–2. "Hidden safety net" behavior → Task 1 (pool instead of parse) + unchanged pool filter. Never auto-untrack → Task 2 (Parsed branch unreachable when rejected). Part 2 restore → Task 3; snapshot → Task 4; sheet callback → Task 5; snackbar + restore → Task 6. Out-of-scope items (pool noise undo, trash UI, retention changes) are not implemented. ✓
- **Placeholder scan:** No TBD/TODO; every code step has full code. The two NOTEs explicitly instruct copying current signatures if they drift — not placeholders for missing logic. ✓
- **Type consistency:** `DeletedTransaction(transaction, reimbursements)` used identically in Tasks 4, 5, 6. `restoreTransaction(tx, reimbursements)` / `restoreTransactionBody(...)` consistent across Tasks 3 and 6. `delete(transactionId, onDeleted: (DeletedTransaction?) -> Unit)` consistent across Tasks 4 and 5. `isRejected` param name consistent across Tasks 1 and 2. `isPackageRejected` / `isRejected` (DAO) consistent in Task 2. ✓
