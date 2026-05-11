# Cloud sync via Google Drive AppData

**Date:** 2026-05-11
**Scope:** New feature: auto-upload the existing Backup v4/v5 JSON to Google
Drive's AppData folder so a wipe/reinstall doesn't lose user data. Includes a
configurable year-month floor on transactions so old history can be excluded.
Single-device reinstall-safety case only; multi-device active sync is out of
scope.

---

## Problem

The app produces a versioned JSON backup of its learning state (categories,
mappings, descriptions, merchant notes, source-tier list, approved-source list).
Today this only lives on-device. A reinstall, factory reset, or device switch
loses everything captured so far. The user has been manually exporting + sharing
the JSON via the existing flow; cloud sync removes that ongoing chore.

Transactions are currently excluded from backup. This design adds them in,
configurable via a year-month floor (back up transactions from `<YYYY-MM>`
onward).

## Goal

When the user signs in to Google Drive once, the app silently maintains a
single canonical backup file in their app-private Drive AppData folder. Each
backup-relevant DB change re-arms a 15-minute upload timer; one upload fires
after the most recent change. A fresh install detects the backup on sign-in and
prompts to restore. Local data is always the source of truth — cloud is a copy.

## Non-goals

- **Multi-device active sync.** Two devices both writing concurrently. Out of
  scope; existing merge rules + dedupe-key IGNORE happen to handle it
  functionally, but there's no explicit pull-from-cloud beyond startup
  detection.
- **Snapshot history in Drive.** v1 stores ONE file (`txtracker-backup.json`).
  Each upload overwrites. Future rotation is FUTURE.md material.
- **Compression / gzip.** Skip until file size becomes a real concern.
- **End-to-end encryption of cloud copy.** Relies on Drive's at-rest
  encryption + Google account security.
- **Multi-account.** One signed-in account at a time. Sign out to switch.
- **Selective restore.** All-or-nothing per existing merge rules.
- **Foreign currency, push notifications, charts, etc.** — other FUTURE.md
  items, untouched.

## Design

### 1. Architecture

Four new components, mostly Hilt-singletons:

- **`CloudSyncPrefs`** (`service/CloudSyncPrefs.kt`) — persists user state via
  `SharedPreferences`, exposes `StateFlow`s. Mirrors the `LockPrefs` pattern.
  Fields: `enabled`, `paused`, `accountEmail`, `lastSyncAt`, `lastSyncError`,
  `transactionCutoff`.
- **`DriveClient`** (`cloud/DriveClient.kt`) — thin OkHttp wrapper around the
  Drive REST endpoints for `appDataFolder` space. Owns access-token acquisition
  via Google Sign-In's silent refresh; no token storage of our own.
- **`CloudSyncWorker`** (`cloud/CloudSyncWorker.kt`, `CoroutineWorker`) —
  runs the actual upload. Reads `CloudSyncPrefs`, builds JSON via
  `BackupExporter.exportToJsonString(cutoff)`, calls `DriveClient.upload`,
  updates `lastSyncAt` / `lastSyncError`.
- **`CloudSyncScheduler`** (`cloud/CloudSyncScheduler.kt`, `@Singleton`) —
  observes Room's `InvalidationTracker` for backup-relevant tables;
  on any change, enqueues a `OneTimeWorkRequest` with a 15-minute initial
  delay using `enqueueUniqueWork(WORK_NAME, REPLACE, ...)`. The REPLACE policy
  produces trailing-edge debounce: only one upload fires 15 minutes after the
  most recent write.

Start the scheduler in `TxApp.onCreate()` via Hilt injection. Lives for the
application lifetime; works while the notification listener captures rows in
the background without the UI being open.

### 2. Data flow

```
Notification arrives ──► TxIngestor.ingest() ──► transactions table write
                                                       │
                              InvalidationTracker callback (Room executor)
                                                       │
                                                       ▼
                                CloudSyncScheduler.scheduleDebouncedUpload()
                                                       │
                                       enqueueUniqueWork(REPLACE) — 15-min delay
                                                       │
                                                       ▼
                                               CloudSyncWorker (after delay)
                                                       │
                                          BackupExporter.exportToJsonString(cutoff)
                                                       │
                                                       ▼
                                              DriveClient.upload(json)
                                                       │
                                              CloudSyncPrefs.setLastSync(...)
```

User-initiated edits (verify, set category, set description, set merchant
note, etc.) all touch the watched tables and follow the same path.

### 3. Auth + sign-in flow

**Scope:** `https://www.googleapis.com/auth/drive.appdata` (non-sensitive; no
app verification required).

**Library:** `com.google.android.gms:play-services-auth` for the
sign-in/consent activity flow. Tokens managed by Play Services; we get an
access token at the start of each `DriveClient` call via
`GoogleAuthUtil.getToken(account, scope)`.

**Placement:** Settings only. Not in onboarding. Cloud sync is opt-in; the
critical permission (notification listener) already lives in onboarding and
shouldn't carry more weight.

**Sign-in steps:**

1. Settings → "Sync to Google Drive" toggle (off state) → launches
   `GoogleSignInClient.signInIntent` via Activity Result API.
2. User picks Google account → grants Drive AppData consent (one-time).
3. On success: `CloudSyncPrefs.setEnabled(true)` and `setAccountEmail(...)`.
4. Continue into first-launch detection (§4).
5. On failure/cancel: `enabled` stays false. Snackbar with error.

**Sign-out:** confirmation dialog with three options:
- **Sign out** — clear local sign-in state. Cloud file untouched.
- **Sign out and delete cloud backup** — also `DriveClient.delete()`.
- **Cancel** — no-op.

### 4. Fresh-install restore prompt

After successful sign-in, before declaring "synced":

1. Call `DriveClient.exists()` → does AppData have `txtracker-backup.json`?
2. **No backup exists** → mark `enabled = true`. Nothing else. The next 15-min
   trigger seeds cloud with current local state.
3. **Backup exists**:
   - Check fresh-install heuristic: `transactionDao.count() == 0` AND
     `merchantMappingDao.count() == 0`. Seed categories don't count.
   - **Fresh install** → show dialog: *"Found a backup from `<exportedAt>`.
     Restore your data?"* with **Restore** / **Skip**.
     - **Restore** → save pre-restore local snapshot (§9) → download JSON →
       `BackupImporter.import(...)` → snackbar with counters.
     - **Skip** → no-op. Cloud file stays put until the next upload overwrites
       it. "Restore from cloud" remains available in Settings if the user
       changes their mind.
   - **Not fresh install** (you already have data) → don't auto-prompt. Local
     data is the source of truth.

### 5. Upload trigger and worker

**Scheduler.** A single `InvalidationTracker.Observer` on all backup-relevant
tables, registered in `TxApp.onCreate()`:

```kotlin
private val watchedTables = arrayOf(
    "transactions", "categories",
    "merchant_mappings", "merchant_description_mappings",
    "category_description_mappings", "merchant_notes",
    "user_facing_sources", "approved_sources",
)
```

On `onInvalidated`, if `enabled && !paused`, enqueue:

```kotlin
val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
    .setInitialDelay(15, TimeUnit.MINUTES)
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build(),
    )
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    .build()
WorkManager.getInstance(context).enqueueUniqueWork(
    WORK_NAME, ExistingWorkPolicy.REPLACE, request,
)
```

The REPLACE policy is the heart of the trailing-edge debounce. Each new write
resets the timer; one upload fires 15 minutes after the most recent write.

**Worker.**

```kotlin
@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val prefs: CloudSyncPrefs,
    private val backupExporter: BackupExporter,
    private val driveClient: DriveClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!prefs.enabled.value || prefs.paused.value) return Result.success()
        return try {
            val json = backupExporter.exportToJsonString(
                transactionCutoff = prefs.transactionCutoff.value,
            )
            driveClient.upload(json).getOrThrow()
            prefs.setLastSync(success = true, error = null)
            Result.success()
        } catch (e: AuthExpiredException) {
            prefs.setLastSync(success = false, error = "Sign in expired — re-authenticate")
            Result.failure() // not retry — user action required
        } catch (e: TransientNetworkException) {
            prefs.setLastSync(success = false, error = e.message)
            Result.retry()
        } catch (t: Throwable) {
            prefs.setLastSync(success = false, error = t.message ?: "Upload failed")
            Result.failure()
        }
    }
}
```

Manual "Sync now" path: same worker with `setInitialDelay(0)` and the same
unique-work name. Tapping "Sync now" cancels any pending debounced work and
fires immediately.

### 6. DriveClient

OkHttp + Google Sign-In access token. Four real REST endpoints, all against
`https://www.googleapis.com/`:

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `drive/v3/files?spaces=appDataFolder&q=name='txtracker-backup.json'&fields=files(id)` | Find existing file id |
| POST | `upload/drive/v3/files?uploadType=multipart` | Create new (with `parents=['appDataFolder']` metadata) |
| PATCH | `upload/drive/v3/files/<id>?uploadType=media` | Update existing |
| GET | `drive/v3/files/<id>?alt=media` | Download content |
| DELETE | `drive/v3/files/<id>` | Delete (sign-out-and-delete path) |

```kotlin
@Singleton
class DriveClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val signInState: GoogleSignInStateProvider, // wraps GoogleSignIn.getLastSignedInAccount
) {
    suspend fun upload(jsonContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = currentAccessToken() ?: return@withContext Result.failure(NotSignedIn)
        val existingId = findExistingFileId(token)
        if (existingId != null) updateFile(token, existingId, jsonContent)
        else createFile(token, jsonContent)
    }

    suspend fun download(): Result<String?> = withContext(Dispatchers.IO) {
        val token = currentAccessToken() ?: return@withContext Result.failure(NotSignedIn)
        val fileId = findExistingFileId(token) ?: return@withContext Result.success(null)
        Result.success(getFileContent(token, fileId))
    }

    suspend fun exists(): Result<Boolean> = withContext(Dispatchers.IO) {
        val token = currentAccessToken() ?: return@withContext Result.failure(NotSignedIn)
        Result.success(findExistingFileId(token) != null)
    }

    suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) { /* … */ }
    // currentAccessToken / findExistingFileId / createFile / updateFile / getFileContent
}
```

**File naming.** Single canonical file: `txtracker-backup.json`. No history.
Drive lets multiple files share a name; we always take the first match from
the find query, which means even if a duplicate accidentally exists we pick a
deterministic one — and `update` keeps overwriting it.

**Network constraint.** `NetworkType.CONNECTED` (not WiFi-only). The JSON is
small even with transactions (~300 bytes/row → ~3 MB at 10k rows). 15-min
debounce caps the upload rate; cellular cost is negligible.

### 7. Backup format v5 — transactions inclusion

Bump `Backup.CURRENT_VERSION` from 4 to 5. New optional fields:

```kotlin
@Serializable
data class Backup(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Instant,
    val transactionCutoff: YearMonth? = null,   // null = no cutoff (all included)
    val categories: List<BackupCategory>,
    val merchantMappings: List<BackupMerchantMapping>,
    val merchantDescriptionMappings: List<BackupMerchantDescriptionMapping>,
    val categoryDescriptionMappings: List<BackupCategoryDescriptionMapping>,
    val merchantNotes: List<BackupMerchantNote> = emptyList(),
    val userFacingSources: List<BackupUserFacingSource> = emptyList(),
    val approvedSources: List<BackupApprovedSource> = emptyList(),
    val transactions: List<BackupTransaction> = emptyList(),
)
```

`BackupTransaction` mirrors `Transaction` except `categoryName: String?`
instead of `categoryId: Long?` (portable across installs):

```kotlin
@Serializable
data class BackupTransaction(
    val amountMinor: Long,
    val currency: String,
    val merchantRaw: String,
    val merchantNormalized: String,
    val categoryName: String?,   // null = uncategorized
    val description: String?,
    val occurredAt: Instant,
    val timeBucket: TimeBucket,
    val sourceApp: String,
    val rawText: String?,
    val direction: Direction,
    val createdAt: Instant,
    val notificationDedupeKey: String,
    val needsVerification: Boolean,
)
```

`YearMonth` needs a custom kotlinx-serialization wrapper (~10 lines) — encode
as `"YYYY-MM"` string. Place in `export/YearMonthSerializer.kt`.

### 8. Exporter and applyBackup changes

**Exporter.** Add `exportToJsonString(transactionCutoff: YearMonth? = null):
String` — the new entry point. The existing `export()` (writes a shareable
file) delegates to `exportToJsonString(null)` — manual exports always include
everything; cutoff only applies to cloud uploads.

`TransactionRepository`/`TransactionDao` need a new method to fetch
transactions from a cutoff:

```kotlin
@Query("SELECT * FROM transactions WHERE occurredAt >= :cutoffStart ORDER BY occurredAt ASC")
suspend fun getAllFrom(cutoffStart: Instant): List<Transaction>
```

`YearMonth` → start-of-month `Instant` conversion uses `MalaysiaTimeZone`.

**applyBackup.** New pass 9 (after the existing 8 passes):

```kotlin
// 9. Transactions. Insert with IGNORE on the unique notificationDedupeKey index —
//    local transactions always win on conflict. Backup transactions whose
//    categoryName doesn't resolve to a local category get inserted with
//    categoryId = null (Unverified, same as a fresh capture).
var transactionsAdded = 0
for (bt in backup.transactions) {
    val categoryId = bt.categoryName?.let { categoriesByName[it]?.id }
    val rowId = transactionDao.insert(
        Transaction(
            amountMinor = bt.amountMinor,
            currency = bt.currency,
            merchantRaw = bt.merchantRaw,
            merchantNormalized = bt.merchantNormalized,
            categoryId = categoryId,
            description = bt.description,
            occurredAt = bt.occurredAt,
            timeBucket = bt.timeBucket,
            sourceApp = bt.sourceApp,
            rawText = bt.rawText,
            direction = bt.direction,
            createdAt = bt.createdAt,
            notificationDedupeKey = bt.notificationDedupeKey,
            needsVerification = bt.needsVerification,
        ),
    )
    if (rowId >= 0) transactionsAdded++
}
```

`ImportResult` gains `transactionsAdded: Int = 0` so the existing import
snackbar shows the count.

### 9. Safety: rollback snapshots

Single helper:

```kotlin
/** Saves a full no-cutoff backup to cacheDir/exports/<name>-<ts>.json so the
 *  user can roll back via the existing "Restore from backup" Settings row. */
suspend fun saveLocalRollbackSnapshot(name: String): Uri
```

Called from:
- `applyBackup` from cloud restore (auto-detect prompt or "Restore from
  cloud") — name `pre-cloud-restore`.
- `setTransactionCutoff(...)` — name `pre-cutoff-change`. Fires on EVERY
  cutoff change regardless of direction (always-snapshot is simpler than
  detecting tightening; ~few KB per change is harmless).

Retention: cap at 5 snapshots per name prefix. Cheap loop at write time —
delete the oldest if the current count exceeds 5.

The user reverts via the existing "Restore from backup" Settings row
(`ActivityResultContracts.OpenDocument`) and picks the snapshot file from the
file picker.

### 10. Settings UI

New "Cloud sync" section between **App** and **About**.

**Signed-out state (one row):**
- **Sync to Google Drive** — supporting text "Off · Tap to sign in and back up
  your data". Tap → launches sign-in.

**Signed-in state (six rows):**
1. **Sync to Google Drive** — toggle ON. Supporting text shows
   `Signed in as <email> · Last synced <relative time>` (or last error in
   error color). Toggling off opens the three-option sign-out confirmation.
2. **Sync now** — manual trigger. Tap → enqueues `CloudSyncWorker` with no
   delay, REPLACE policy. Trailing slot shows `CircularProgressIndicator`
   during upload.
3. **Pause sync** — `Switch`. Flips `prefs.paused`. Visual cue elsewhere
   when paused.
4. **Transaction backup cutoff** — tap → opens cutoff dialog:
   - Radio options: "All transactions" / "From `[YYYY]` `[MM]` ▼"
   - Supporting text: *"Older transactions will be excluded from your cloud
     backup. They stay on this device, and a local snapshot is saved before
     any change in case you want to revert."*
   - On **Save**: write `pre-cutoff-change` snapshot → update prefs. Does not
     auto-trigger sync — user taps "Sync now" if immediate.
5. **Restore from cloud** — explicit alternative to the fresh-install
   prompt. Confirmation: *"Replace local data with the cloud backup from
   `<exportedAt>`? A local snapshot is saved first."* → save snapshot →
   download → `applyBackup` → snackbar.
6. **Sign out** — three-option dialog.

The row supporting texts surface state (last-synced, last-error,
paused-state) without needing a separate diagnostics screen.

### 11. OAuth client setup (one-time, manual, your side)

1. Open Google Cloud Console.
2. Create (or reuse) a project for personal apps.
3. Enable the Drive API.
4. Create an **OAuth 2.0 Client ID** of type **Android**.
5. Add the debug-keystore's SHA-1 fingerprint and `cy.txtracker` package
   name. To get the SHA-1:
   `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`
6. The `drive.appdata` scope is non-sensitive; no consent-screen verification
   is required for personal use.
7. The OAuth client config is then implicitly available to
   `play-services-auth` once the SHA-1 matches the signing key.

If you ship a different signing key later (e.g., a release build), add its
SHA-1 to the same OAuth client.

## File inventory

**New:**
- `app/src/main/java/cy/txtracker/service/CloudSyncPrefs.kt`
- `app/src/main/java/cy/txtracker/cloud/DriveClient.kt`
- `app/src/main/java/cy/txtracker/cloud/CloudSyncScheduler.kt`
- `app/src/main/java/cy/txtracker/cloud/CloudSyncWorker.kt`
- `app/src/main/java/cy/txtracker/cloud/GoogleSignInStateProvider.kt` (thin
  wrapper for testability)
- `app/src/main/java/cy/txtracker/cloud/CloudSyncErrors.kt` (sealed exception
  types: `NotSignedIn`, `AuthExpiredException`, `TransientNetworkException`)
- `app/src/main/java/cy/txtracker/export/YearMonthSerializer.kt`
- `app/src/main/java/cy/txtracker/ui/settings/cloud/CloudSyncSection.kt`
  (the new Settings section, extracted for clarity)
- `app/src/main/java/cy/txtracker/ui/settings/cloud/CutoffPickerDialog.kt`
- Tests: `app/src/test/.../DriveClientTest.kt` (mocked OkHttp),
  `BackupSerializationTest` extensions for transactions and cutoff,
  `app/src/androidTest/.../ApplyBackupTest.kt` extensions.

**Modified:**
- `app/src/main/java/cy/txtracker/TxApp.kt` — start `CloudSyncScheduler` in
  `onCreate()`.
- `app/src/main/java/cy/txtracker/export/Backup.kt` — bump to v5, add fields
  + `BackupTransaction`, integrate `YearMonthSerializer`.
- `app/src/main/java/cy/txtracker/export/BackupExporter.kt` — new
  `exportToJsonString(cutoff)`; new `saveLocalRollbackSnapshot(name)` helper;
  existing `export()` delegates.
- `app/src/main/java/cy/txtracker/data/TransactionDao.kt` — new
  `getAllFrom(cutoffStart)`.
- `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` — wrap
  `getAllTransactionsOnceFrom(cutoff)`.
- `app/src/main/java/cy/txtracker/data/TransactionRepository.applyBackup` —
  new pass 9 for transactions. `ImportResult` gains `transactionsAdded`.
- `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt` — inject
  `CloudSyncPrefs`, `DriveClient`, `BackupImporter` for cloud flows; new
  state + setters for sync controls.
- `app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt` — render
  new "Cloud sync" section between App and About.
- `app/build.gradle.kts` — add `play-services-auth` dependency, OkHttp (if
  not already present; the existing `kotlinx-serialization` covers JSON).
- `app/src/main/AndroidManifest.xml` — `INTERNET` permission (likely already
  present), `ACCESS_NETWORK_STATE` for WorkManager constraints.

## Tests

- **Unit (`DriveClientTest`):** mocked OkHttp `MockWebServer`. Verify
  `upload` does create on absent / update on present, `download` returns
  null when missing, etc. Token acquisition mocked at the
  `GoogleSignInStateProvider` boundary.
- **Unit (`BackupSerializationTest`):** transactions round-trip;
  `transactionCutoff` round-trips as `"YYYY-MM"` string; v4 backup file
  parses cleanly with empty `transactions`; `version == 5`.
- **Unit (`CloudSyncWorkerTest`):** mock prefs and DriveClient; verify
  bail-on-disabled, bail-on-paused, success path updates lastSyncAt,
  transient failure → retry, auth-expired → failure.
- **AndroidTest (`ApplyBackupTest`):** transactions insert with IGNORE on
  dedupe key, categoryName resolution to id and fallback to null,
  `transactionsAdded` counter, restore preserves local transactions on
  conflict.
- **AndroidTest (`CloudSyncSchedulerTest`, optional):** DB write triggers
  WorkManager enqueue; multiple writes within window result in one enqueued
  job (REPLACE).
- **Manual smoke** (no automation): sign-in / sign-out flow, fresh-install
  prompt with a Drive backup present, cutoff change → snapshot lands in
  cacheDir/exports, pause toggle gates upload.

## Effort

~1.5–2 days focused work:
- DriveClient + auth wiring: ~half day
- Scheduler + Worker: ~half day
- Backup v5 (transactions + cutoff): ~half day
- Settings UI: ~half day
- Tests + smoke verification: ~half day

Plus your one-time Google Cloud Console setup (~15 min).

## Open questions to revisit during implementation

- `kotlinx-datetime` `YearMonth` exists? It does in `0.6+` as an experimental
  API. If not stable in our version, use a private `YearMonth(year, month)`
  data class. Decide at implementation time.
- Should the Settings supporting-text "Last synced: 2h ago" use a relative
  formatter or absolute? Probably relative — easier to glance at. Add a
  small `formatRelativeTime(Instant)` helper.
- Concurrency between the InvalidationTracker callback (Room executor) and
  the WorkManager thread for `enqueueUniqueWork`: WorkManager's API is
  thread-safe, but verify there's no surprise.
- `applyBackup` is already wrapped in `database.withTransaction { }`. Pass 9
  joins the same transaction — pre-existing behavior.
- Whether `play-services-auth` is sufficient or if we need
  `com.google.android.gms:play-services-base` too. Pin at impl time.

## Out of scope (deferred to FUTURE.md)

- Multi-device active sync (pull-on-foreground, periodic downloads).
- Snapshot rotation in Drive (last N snapshots vs one).
- Compression / gzip.
- End-to-end encryption.
- Multi-account switching.
- "Show me what's in the cloud" diagnostics screen.
- Selective restore (only categories / only mappings / etc.).
