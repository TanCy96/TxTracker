# Cloud Sync Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent the cloud-overwrite data-loss scenario reported in ISSUE.md #1 by (B) rotating cloud backups across multiple dated files with retention, and (C) refusing to upload when the local row count shrank dramatically.

**Architecture:** Two layered defenses on top of the existing `CloudSyncWorker` → `DriveClient` pipeline.

- **B — Drive-side rotation.** Replace the single canonical `txtracker-backup.json` with timestamped filenames (`txtracker-backup-<ISO>.json`). Sort lexicographically = chronologically. Retention: keep a file iff rank ≤ 20 (newest-first) OR age < 30 days. After each successful upload, prune older ones. Legacy single-filename file is treated as one of the backups and ages out naturally.
- **C — Pre-upload size-shrink guard.** Cache the last-uploaded transaction row count in `CloudSyncPrefs`. Before each upload, compare current local row count to the cached value. If local is empty *and* baseline > 0, skip the upload and surface a banner in Settings ("Sync paused: local data unexpectedly empty"). User clicks "Resume sync" to override. Guard is bypassed when no baseline exists yet (first sync after sign-in).

**Tech Stack:** Kotlin, Room, WorkManager, Hilt, Jetpack Compose, Google Drive REST v3 (existing). Tests use JUnit + Truth + MockWebServer (existing patterns in `DriveClientTest`).

**Out of scope:**
- Migrating to a different backup format (gzip, encryption) — separate FUTURE.md item.
- Fixing the underlying `fallbackToDestructiveMigration()` footgun — discussed and rejected as optional layer A.
- Multi-device active sync — separate FUTURE.md item.

---

## File Structure

**Create:**
- `app/src/main/java/cy/txtracker/cloud/BackupRetentionPolicy.kt` — pure function `selectToDelete(files, now)`.
- `app/src/main/java/cy/txtracker/cloud/CloudSyncGuard.kt` — pure function `evaluate(currentRowCount, baselineRowCount): Decision`.
- `app/src/test/java/cy/txtracker/cloud/BackupRetentionPolicyTest.kt`
- `app/src/test/java/cy/txtracker/cloud/CloudSyncGuardTest.kt`
- `app/src/test/java/cy/txtracker/cloud/CloudSyncWorkerTest.kt` — new tests for the rewritten worker.

**Modify:**
- `app/src/main/java/cy/txtracker/cloud/DriveClient.kt` — add `listAll()`, `uploadDated()`, overloads `download(id)`/`delete(id)`; change `download()`/`delete()`/`exists()` semantics to operate over the new file model (newest / all).
- `app/src/main/java/cy/txtracker/cloud/CloudSyncWorker.kt` — rewrite `execute()` to use guard + dated upload + prune.
- `app/src/main/java/cy/txtracker/service/CloudSyncPrefs.kt` — add `lastUploadedRowCount` and `syncBlockedReason` fields.
- `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt` — surface `syncBlockedReason`, add `resumeSync()`, expose backup list for picker.
- `app/src/main/java/cy/txtracker/ui/settings/cloud/CloudSyncSection.kt` — sync-blocked banner + restore-picker bottom sheet.
- `app/src/test/java/cy/txtracker/cloud/DriveClientTest.kt` — extend with listAll/uploadDated/delete-by-id tests.

**Note on backward compatibility:** Existing users have a single legacy `txtracker-backup.json` in their Drive AppData. The new `listAll()` matches `txtracker-backup*.json` so the legacy file is picked up as one of the backups. The first new upload after this lands writes a dated file alongside the legacy one. The legacy file is pruned naturally once it falls outside the retention window (rank > 20 AND age ≥ 30 days). No explicit migration step.

---

## Task 1: BackupRetentionPolicy — pure function

**Files:**
- Create: `app/src/main/java/cy/txtracker/cloud/BackupRetentionPolicy.kt`
- Test: `app/src/test/java/cy/txtracker/cloud/BackupRetentionPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package cy.txtracker.cloud

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

class BackupRetentionPolicyTest {

    private val now = Instant.parse("2026-05-15T12:00:00Z")

    private fun file(id: String, daysAgo: Long): BackupFile =
        BackupFile(id = id, name = "txtracker-backup-x.json", modifiedAt = now.minus(daysAgo, kotlinx.datetime.DateTimeUnit.DAY, kotlinx.datetime.TimeZone.UTC))

    @Test
    fun keeps_everything_when_below_count_and_age_limits() {
        // 5 files, all fresh — nothing should be deleted.
        val files = (1..5).map { file("id-$it", daysAgo = it.toLong()) }
        val ids = BackupRetentionPolicy.selectToDelete(files, now)
        assertThat(ids).isEmpty()
    }

    @Test
    fun keeps_top_20_when_all_old() {
        // 25 files, all 60+ days old — keep the 20 newest, delete the 5 oldest.
        val files = (1..25).map { file("id-$it", daysAgo = 60L + it) }
        val ids = BackupRetentionPolicy.selectToDelete(files, now)
        assertThat(ids).hasSize(5)
        // The 5 deleted are id-21..id-25 (oldest by daysAgo).
        assertThat(ids).containsExactly("id-21", "id-22", "id-23", "id-24", "id-25")
    }

    @Test
    fun keeps_files_younger_than_30_days_even_beyond_top_20() {
        // 30 files all 10 days old — rank-wise 20 of them would be deleted, but the
        // OR-30-days rule keeps everything because all are < 30 days.
        val files = (1..30).map { file("id-$it", daysAgo = 10L) }
        val ids = BackupRetentionPolicy.selectToDelete(files, now)
        assertThat(ids).isEmpty()
    }

    @Test
    fun mixed_age_keeps_recent_and_top_20() {
        // 15 files at 5 days old + 10 files at 100 days old.
        // Recent 15 all kept by age. Old 10: 5 are within top-20-by-recency overall (slots 16-20), kept by rank.
        // Old 10's slots 21-25 are pruned.
        val recent = (1..15).map { file("recent-$it", daysAgo = 5L) }
        val old = (1..10).map { file("old-$it", daysAgo = 100L + it) }
        val ids = BackupRetentionPolicy.selectToDelete(recent + old, now)
        assertThat(ids).containsExactly("old-6", "old-7", "old-8", "old-9", "old-10")
    }

    @Test
    fun empty_input_returns_empty() {
        assertThat(BackupRetentionPolicy.selectToDelete(emptyList(), now)).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails (compilation error)**

```
./gradlew :app:testDebugUnitTest --tests "cy.txtracker.cloud.BackupRetentionPolicyTest"
```

Expected: BUILD FAILED — `BackupRetentionPolicy` and `BackupFile` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package cy.txtracker.cloud

import kotlinx.datetime.Instant

/**
 * Metadata for a single backup file in Drive AppData. Comes from [DriveClient.listAll].
 *
 * @property id Drive file id, used for download/delete by id.
 * @property name The filename in AppData (e.g., `txtracker-backup-20260515T120000Z.json`).
 * @property modifiedAt Drive's `modifiedTime` for the file.
 */
data class BackupFile(
    val id: String,
    val name: String,
    val modifiedAt: Instant,
)

/**
 * Decides which backup files to delete given a current set in Drive AppData.
 *
 * A file is **kept** iff one of:
 *   - Its rank in the newest-first ordering is ≤ [MAX_KEEP_COUNT] (20), OR
 *   - Its age (now - modifiedAt) is less than [MAX_KEEP_AGE_DAYS] (30 days).
 *
 * A file is **deleted** iff both conditions fail: rank > 20 AND age ≥ 30 days.
 *
 * The "OR" gives complementary guarantees:
 *   - Light users get long history (last 20 backups regardless of age — could span months).
 *   - Heavy users get fresh history (all uploads from the last 30 days regardless of count).
 *
 * Pure function — caller is responsible for invoking [DriveClient.delete] on each returned id.
 */
object BackupRetentionPolicy {

    const val MAX_KEEP_COUNT: Int = 20
    const val MAX_KEEP_AGE_DAYS: Long = 30

    fun selectToDelete(files: List<BackupFile>, now: Instant): List<String> {
        val sortedNewestFirst = files.sortedByDescending { it.modifiedAt }
        val cutoffInstant = now.minus(MAX_KEEP_AGE_DAYS, kotlinx.datetime.DateTimeUnit.DAY, kotlinx.datetime.TimeZone.UTC)
        return sortedNewestFirst
            .withIndex()
            .filter { (rankZeroBased, file) ->
                val rank = rankZeroBased + 1
                // Delete iff rank > MAX_KEEP_COUNT AND age >= MAX_KEEP_AGE_DAYS.
                // Equivalently: NOT (rank <= MAX_KEEP_COUNT OR modifiedAt > cutoffInstant).
                rank > MAX_KEEP_COUNT && file.modifiedAt <= cutoffInstant
            }
            .map { it.value.id }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :app:testDebugUnitTest --tests "cy.txtracker.cloud.BackupRetentionPolicyTest"
```

Expected: PASS for all 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/cloud/BackupRetentionPolicy.kt app/src/test/java/cy/txtracker/cloud/BackupRetentionPolicyTest.kt
git commit -m "Cloud sync: retention policy (keep last 20 OR <30d)"
```

---

## Task 2: CloudSyncGuard — pre-upload shrink guard

**Files:**
- Create: `app/src/main/java/cy/txtracker/cloud/CloudSyncGuard.kt`
- Test: `app/src/test/java/cy/txtracker/cloud/CloudSyncGuardTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package cy.txtracker.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudSyncGuardTest {

    @Test
    fun proceeds_when_no_baseline() {
        // First-ever upload: baseline = UNKNOWN. We proceed because we have nothing to compare to.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 0, baselineRowCount = CloudSyncGuard.UNKNOWN_BASELINE)
        assertThat(decision).isEqualTo(CloudSyncGuard.Decision.Proceed)
    }

    @Test
    fun proceeds_when_local_and_baseline_both_zero() {
        // Long-time empty installs (e.g., user signed in but never captured anything).
        val decision = CloudSyncGuard.evaluate(currentRowCount = 0, baselineRowCount = 0)
        assertThat(decision).isEqualTo(CloudSyncGuard.Decision.Proceed)
    }

    @Test
    fun proceeds_when_local_grew() {
        val decision = CloudSyncGuard.evaluate(currentRowCount = 100, baselineRowCount = 50)
        assertThat(decision).isEqualTo(CloudSyncGuard.Decision.Proceed)
    }

    @Test
    fun proceeds_when_local_shrank_within_threshold() {
        // User legitimately deleted some rows. Within 50% shrink threshold — allow.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 60, baselineRowCount = 100)
        assertThat(decision).isEqualTo(CloudSyncGuard.Decision.Proceed)
    }

    @Test
    fun skips_when_local_emptied_but_baseline_had_rows() {
        // The smoking gun for ISSUE.md #1 — local DB got wiped, baseline has data.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 0, baselineRowCount = 100)
        assertThat(decision).isInstanceOf(CloudSyncGuard.Decision.Skip::class.java)
        val reason = (decision as CloudSyncGuard.Decision.Skip).reason
        assertThat(reason).contains("empty")
    }

    @Test
    fun skips_when_local_shrank_beyond_threshold() {
        // 80% shrink — beyond the 50% threshold. Skip and let user inspect.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 20, baselineRowCount = 100)
        assertThat(decision).isInstanceOf(CloudSyncGuard.Decision.Skip::class.java)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :app:testDebugUnitTest --tests "cy.txtracker.cloud.CloudSyncGuardTest"
```

Expected: BUILD FAILED — `CloudSyncGuard` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package cy.txtracker.cloud

/**
 * Pre-upload safety check. Compares the local DB's current transaction row count against the
 * last successfully-uploaded count (cached in [cy.txtracker.service.CloudSyncPrefs]).
 *
 * Refuses to upload when the local DB has suspiciously shrunk:
 *   - Hard skip: local is empty (`0`) and baseline had any rows.
 *   - Hard skip: local shrank by more than [SHRINK_THRESHOLD] (currently 50%) from baseline.
 *
 * Pure function — caller (the worker) handles the side-effects (logging, prefs writes,
 * surfacing a banner).
 *
 * The reported scenario (ISSUE.md #1) is the "local emptied, baseline non-zero" case:
 * destructive Room migration wiped the local DB, baseline still reflects the pre-wipe state,
 * guard fires, cloud is preserved.
 */
object CloudSyncGuard {

    /** Sentinel for "no upload has succeeded yet, no baseline to compare against." */
    const val UNKNOWN_BASELINE: Long = -1L

    /** Allow uploads when shrinkage is at most this fraction of baseline. */
    private const val SHRINK_THRESHOLD: Double = 0.5

    sealed interface Decision {
        data object Proceed : Decision
        data class Skip(val reason: String) : Decision
    }

    fun evaluate(currentRowCount: Long, baselineRowCount: Long): Decision {
        if (baselineRowCount == UNKNOWN_BASELINE) return Decision.Proceed
        if (baselineRowCount == 0L) return Decision.Proceed
        if (currentRowCount == 0L) {
            return Decision.Skip(
                "Local data is empty but cloud has $baselineRowCount transactions — " +
                    "sync paused to prevent overwriting your cloud backup.",
            )
        }
        val ratio = currentRowCount.toDouble() / baselineRowCount.toDouble()
        if (ratio < SHRINK_THRESHOLD) {
            return Decision.Skip(
                "Local transactions dropped from $baselineRowCount to $currentRowCount — " +
                    "sync paused. Resume from Settings if this is intentional.",
            )
        }
        return Decision.Proceed
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :app:testDebugUnitTest --tests "cy.txtracker.cloud.CloudSyncGuardTest"
```

Expected: PASS for all 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/cloud/CloudSyncGuard.kt app/src/test/java/cy/txtracker/cloud/CloudSyncGuardTest.kt
git commit -m "Cloud sync: pre-upload row-count shrink guard"
```

---

## Task 3: CloudSyncPrefs — add baseline + blocked-reason fields

**Files:**
- Modify: `app/src/main/java/cy/txtracker/service/CloudSyncPrefs.kt`

- [ ] **Step 1: Add the new prefs to `CloudSyncPrefs`**

Add inside the class body, near the existing prefs (around line 50):

```kotlin
    private val _lastUploadedRowCount = MutableStateFlow(
        prefs.getLong(KEY_LAST_UPLOADED_ROW_COUNT, CloudSyncGuard.UNKNOWN_BASELINE),
    )
    val lastUploadedRowCount: StateFlow<Long> = _lastUploadedRowCount.asStateFlow()

    private val _syncBlockedReason = MutableStateFlow(prefs.getString(KEY_SYNC_BLOCKED_REASON, null))
    val syncBlockedReason: StateFlow<String?> = _syncBlockedReason.asStateFlow()

    fun setLastUploadedRowCount(value: Long) {
        prefs.edit().putLong(KEY_LAST_UPLOADED_ROW_COUNT, value).apply()
        _lastUploadedRowCount.value = value
    }

    fun setSyncBlockedReason(value: String?) {
        prefs.edit().putString(KEY_SYNC_BLOCKED_REASON, value).apply()
        _syncBlockedReason.value = value
    }
```

Add a missing import at the top:

```kotlin
import cy.txtracker.cloud.CloudSyncGuard
```

Add the two new keys inside the `private companion object`:

```kotlin
        const val KEY_LAST_UPLOADED_ROW_COUNT = "last_uploaded_row_count"
        const val KEY_SYNC_BLOCKED_REASON = "sync_blocked_reason"
```

Update `clearSession()` (around line 90) so sign-out clears both new fields:

```kotlin
    fun clearSession() {
        prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_PAUSED, false)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_LAST_SYNC_AT)
            .remove(KEY_LAST_SYNC_ERROR)
            .remove(KEY_LAST_UPLOADED_ROW_COUNT)
            .remove(KEY_SYNC_BLOCKED_REASON)
            .apply()
        _enabled.value = false
        _paused.value = false
        _accountEmail.value = null
        _lastSyncAt.value = null
        _lastSyncError.value = null
        _lastUploadedRowCount.value = CloudSyncGuard.UNKNOWN_BASELINE
        _syncBlockedReason.value = null
    }
```

- [ ] **Step 2: Verify compile**

```
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/cy/txtracker/service/CloudSyncPrefs.kt
git commit -m "CloudSyncPrefs: add lastUploadedRowCount + syncBlockedReason"
```

---

## Task 4: DriveClient — list/upload/download/delete by file ID

**Files:**
- Modify: `app/src/main/java/cy/txtracker/cloud/DriveClient.kt`
- Modify: `app/src/test/java/cy/txtracker/cloud/DriveClientTest.kt`

This task changes `DriveClient`'s file model from a single canonical file to a list of dated files. Existing methods (`download()`/`delete()`/`exists()`) keep their signatures but operate on the newest / all files in AppData. New methods (`uploadDated`/`listAll`/`download(id)`/`delete(id)`) are added.

- [ ] **Step 1: Write the failing tests** in `DriveClientTest.kt` (append to existing class):

```kotlin
    @Test
    fun listAll_returns_files_with_metadata() = runTest {
        // Drive responds with two backup files.
        server.enqueue(
            MockResponse().setBody(
                """{"files":[
                    {"id":"abc","name":"txtracker-backup-20260515T100000Z.json","modifiedTime":"2026-05-15T10:00:00.000Z"},
                    {"id":"def","name":"txtracker-backup-20260514T100000Z.json","modifiedTime":"2026-05-14T10:00:00.000Z"}
                ]}""",
            ),
        )
        val result = client.listAll().getOrThrow()
        assertThat(result).hasSize(2)
        assertThat(result.map { it.id }).containsExactly("abc", "def")
        assertThat(result[0].name).isEqualTo("txtracker-backup-20260515T100000Z.json")
    }

    @Test
    fun listAll_empty_when_no_files() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        val result = client.listAll().getOrThrow()
        assertThat(result).isEmpty()
    }

    @Test
    fun uploadDated_creates_file_with_timestamped_name() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"new-id"}"""))
        val result = client.uploadDated("""{"hello":"world"}""", kotlinx.datetime.Instant.parse("2026-05-15T10:30:45Z"))
        assertThat(result.isSuccess).isTrue()
        val req = server.takeRequest()
        assertThat(req.path).contains("uploadType=multipart")
        // The multipart body should contain the timestamped name.
        val body = req.body.readUtf8()
        assertThat(body).contains("txtracker-backup-20260515T103045Z.json")
    }

    @Test
    fun download_returns_newest_files_content() = runTest {
        // listAll returns 2 files, the newer one (abc) wins.
        server.enqueue(
            MockResponse().setBody(
                """{"files":[
                    {"id":"abc","name":"txtracker-backup-20260515T100000Z.json","modifiedTime":"2026-05-15T10:00:00.000Z"},
                    {"id":"def","name":"txtracker-backup-20260514T100000Z.json","modifiedTime":"2026-05-14T10:00:00.000Z"}
                ]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"newest":true}"""))
        val result = client.download().getOrThrow()
        assertThat(result).isEqualTo("""{"newest":true}""")
        server.takeRequest() // list
        val getReq = server.takeRequest()
        assertThat(getReq.path).contains("files/abc")
    }

    @Test
    fun download_by_id_fetches_specific_file() = runTest {
        server.enqueue(MockResponse().setBody("""{"specific":true}"""))
        val result = client.download("specific-id").getOrThrow()
        assertThat(result).isEqualTo("""{"specific":true}""")
        val req = server.takeRequest()
        assertThat(req.path).contains("files/specific-id")
    }

    @Test
    fun delete_by_id_deletes_specific_file() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = client.delete("doomed-id")
        assertThat(result.isSuccess).isTrue()
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("DELETE")
        assertThat(req.path).contains("files/doomed-id")
    }

    @Test
    fun delete_no_args_deletes_all_backup_files() = runTest {
        // sign-out-and-delete must wipe every dated file plus any legacy file.
        server.enqueue(
            MockResponse().setBody(
                """{"files":[
                    {"id":"a","name":"txtracker-backup-20260515T100000Z.json","modifiedTime":"2026-05-15T10:00:00.000Z"},
                    {"id":"b","name":"txtracker-backup.json","modifiedTime":"2026-05-14T10:00:00.000Z"}
                ]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        val result = client.delete()
        assertThat(result.isSuccess).isTrue()
        // 1 list + 2 deletes.
        server.takeRequest()
        val firstDelete = server.takeRequest()
        val secondDelete = server.takeRequest()
        assertThat(firstDelete.method).isEqualTo("DELETE")
        assertThat(secondDelete.method).isEqualTo("DELETE")
    }
```

The legacy `upload_creates_new_when_no_existing_file` and `upload_updates_when_file_exists` tests now exercise behavior we're removing. **Delete those two tests** — they test a code path that no longer exists after this task.

- [ ] **Step 2: Run tests to verify they fail (compilation error)**

```
./gradlew :app:testDebugUnitTest --tests "cy.txtracker.cloud.DriveClientTest"
```

Expected: BUILD FAILED — `listAll`, `uploadDated`, `download(id)`, `delete(id)` unresolved.

- [ ] **Step 3: Rewrite `DriveClient.kt`** to support the new model. Full new contents:

```kotlin
package cy.txtracker.cloud

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Supplies the OAuth token. Implemented by [GoogleSignInStateProvider] in production, faked
 * in tests.
 */
interface SignInTokenSource {
    suspend fun currentAccessToken(): String
    fun currentAccountEmail(): String?
}

/**
 * Drive REST client for the app-private AppData folder. Operates over multiple dated
 * backup files (`txtracker-backup-<ISO>.json`), not a single canonical file. Filename
 * pattern is set up so lexicographic sort = chronological sort.
 *
 * Endpoints hit:
 *   - `GET drive/v3/files?spaces=appDataFolder&q=name contains 'txtracker-backup'` — list
 *   - `POST upload/drive/v3/files?uploadType=multipart` — upload new dated file
 *   - `GET drive/v3/files/<id>?alt=media` — download specific file
 *   - `DELETE drive/v3/files/<id>` — delete specific file
 *
 * Returns [Result] with typed exceptions from [CloudSyncException].
 */
@Singleton
class DriveClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val signInState: SignInTokenSource,
    private val baseUrl: String = "https://www.googleapis.com/",
) {

    /** Lists every backup file in AppData (legacy `txtracker-backup.json` and dated form). */
    suspend fun listAll(): Result<List<BackupFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            listAllImpl(token)
        }
    }

    /** Creates a new dated backup file. Timestamp formatted as ISO 8601 basic, e.g.
     *  `txtracker-backup-20260515T103045Z.json` — lexicographic sort = chronological. */
    suspend fun uploadDated(content: String, at: Instant = Clock.System.now()): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = signInState.currentAccessToken()
                val name = "$FILE_PREFIX${formatBasicIso(at)}.json"
                createFile(token, name, content)
            }
        }

    /** Newest backup's content. Returns null if AppData has no backups. Used by Onboarding
     *  / Settings "Restore from cloud" (latest). */
    suspend fun download(): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            val newest = listAllImpl(token)
                .maxByOrNull { it.modifiedAt }
                ?: return@runCatching null
            getFileContent(token, newest.id)
        }
    }

    /** Downloads a specific file by id. Used by the Settings restore-picker. */
    suspend fun download(fileId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            getFileContent(token, fileId)
        }
    }

    /** True iff at least one backup file is in AppData. */
    suspend fun exists(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            listAllImpl(token).isNotEmpty()
        }
    }

    /** Deletes every backup file in AppData. Used by sign-out-and-delete. */
    suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            listAllImpl(token).forEach { deleteFile(token, it.id) }
        }
    }

    /** Deletes a specific file by id. Used by the worker to prune older backups. */
    suspend fun delete(fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            deleteFile(token, fileId)
        }
    }

    private fun listAllImpl(token: String): List<BackupFile> {
        val q = "name contains '$FILE_PREFIX' and trashed = false"
        val url = "${baseUrl}drive/v3/files?spaces=appDataFolder" +
            "&q=" + percentEncode(q) +
            "&fields=files(id,name,modifiedTime)" +
            "&pageSize=1000"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        executeOrThrow(req).use { resp ->
            val body = resp.body?.string().orEmpty()
            val parsed = json.decodeFromString(FileListResponse.serializer(), body)
            return parsed.files.map { f ->
                BackupFile(
                    id = f.id,
                    name = f.name,
                    modifiedAt = Instant.parse(f.modifiedTime),
                )
            }
        }
    }

    private fun createFile(token: String, name: String, content: String) {
        val metadata = """{"name":"$name","parents":["appDataFolder"]}"""
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(
                metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()),
            )
            .addPart(content.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("${baseUrl}upload/drive/v3/files?uploadType=multipart")
            .post(body)
            .header("Authorization", "Bearer $token")
            .build()
        executeOrThrow(req).use { /* discard body */ }
    }

    private fun deleteFile(token: String, fileId: String) {
        val req = Request.Builder()
            .url("${baseUrl}drive/v3/files/$fileId")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        executeOrThrow(req).use { /* discard body */ }
    }

    private fun getFileContent(token: String, fileId: String): String {
        val req = Request.Builder()
            .url("${baseUrl}drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .build()
        executeOrThrow(req).use { resp ->
            return resp.body?.string().orEmpty()
        }
    }

    private fun executeOrThrow(request: Request): Response {
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw TransientNetworkException(e.message ?: "Network failure", e)
        }
        when (response.code) {
            in 200..299 -> return response
            401, 403 -> {
                response.close()
                throw AuthExpiredException()
            }
            429, in 500..599 -> {
                val msg = response.message
                response.close()
                throw TransientNetworkException("HTTP ${response.code} $msg")
            }
            else -> {
                val msg = response.body?.string().orEmpty()
                response.close()
                throw DriveApiException(response.code, msg)
            }
        }
    }

    private fun percentEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** ISO 8601 basic format with seconds: `yyyyMMddTHHmmssZ`. Lexicographic == chronological. */
    private fun formatBasicIso(at: Instant): String {
        val s = at.toString() // e.g. 2026-05-15T10:30:45.123Z
        val noFraction = s.substringBefore('.').let { if (it.endsWith("Z")) it else "${it}Z" }
        // 2026-05-15T10:30:45Z → 20260515T103045Z
        return noFraction.replace("-", "").replace(":", "")
    }

    @Serializable
    private data class FileListResponse(val files: List<FileEntry> = emptyList())

    @Serializable
    private data class FileEntry(val id: String, val name: String, val modifiedTime: String)

    private companion object {
        const val FILE_PREFIX = "txtracker-backup"
        val MULTIPART_RELATED = "multipart/related".toMediaType()
    }
}
```

- [ ] **Step 4: Run all DriveClient tests**

```
./gradlew :app:testDebugUnitTest --tests "cy.txtracker.cloud.DriveClientTest"
```

Expected: PASS for all tests (the 7 new ones plus the unchanged exists/download/error-handling tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/cy/txtracker/cloud/DriveClient.kt app/src/test/java/cy/txtracker/cloud/DriveClientTest.kt
git commit -m "DriveClient: dated filenames + list/by-id operations"
```

---

## Task 5: CloudSyncWorker — guard, dated upload, prune

**Files:**
- Modify: `app/src/main/java/cy/txtracker/cloud/CloudSyncWorker.kt`
- Modify: `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` (add small helper)
- Create: `app/src/test/java/cy/txtracker/cloud/CloudSyncWorkerTest.kt`

- [ ] **Step 1: Add `countAllTransactions` helper on the repository**

In `TransactionRepository.kt`, find any nearby aggregation helper (e.g., `getAllTransactionsOnce`) and add:

```kotlin
    /** Cheap row count without materializing all rows. Used by the cloud-sync guard. */
    suspend fun countAllTransactions(): Long = transactionDao.countAll()
```

And in `TransactionDao.kt`, add:

```kotlin
    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun countAll(): Long
```

- [ ] **Step 2: Write the failing worker test** at `CloudSyncWorkerTest.kt`:

```kotlin
package cy.txtracker.cloud

import com.google.common.truth.Truth.assertThat
import cy.txtracker.export.BackupExporter
import cy.txtracker.service.CloudSyncPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test
import androidx.work.ListenableWorker.Result as WorkResult

class CloudSyncWorkerTest {

    private val now = Instant.parse("2026-05-15T12:00:00Z")

    @Test
    fun skips_when_disabled() = runTest {
        val prefs = FakePrefs(enabled = false)
        val drive = FakeDrive()
        val exporter = FakeExporter(""" {"transactions":[]} """)
        val result = CloudSyncWorker.execute(
            prefs = prefs,
            backupExporter = exporter,
            driveClient = drive,
            currentRowCount = 0L,
            now = now,
        )
        assertThat(result).isEqualTo(WorkResult.success())
        assertThat(drive.uploads).isEmpty()
    }

    @Test
    fun guard_blocks_upload_when_local_emptied() = runTest {
        // Baseline says 100 rows; current is 0. The worker must NOT upload.
        val prefs = FakePrefs(enabled = true, baseline = 100L)
        val drive = FakeDrive()
        val exporter = FakeExporter(""" {"transactions":[]} """)
        val result = CloudSyncWorker.execute(
            prefs = prefs,
            backupExporter = exporter,
            driveClient = drive,
            currentRowCount = 0L,
            now = now,
        )
        assertThat(result).isEqualTo(WorkResult.success())
        assertThat(drive.uploads).isEmpty()
        assertThat(prefs.syncBlockedReasonValue).contains("empty")
        // Baseline was NOT clobbered.
        assertThat(prefs.lastUploadedRowCountValue).isEqualTo(100L)
    }

    @Test
    fun happy_path_uploads_dated_and_updates_baseline() = runTest {
        val prefs = FakePrefs(enabled = true, baseline = CloudSyncGuard.UNKNOWN_BASELINE)
        val drive = FakeDrive(
            listResponses = listOf(emptyList(), listOf(BackupFile("new-id", "txtracker-backup-20260515T120000Z.json", now))),
        )
        val exporter = FakeExporter(""" {"transactions":[{},{},{}]} """)
        val result = CloudSyncWorker.execute(
            prefs = prefs,
            backupExporter = exporter,
            driveClient = drive,
            currentRowCount = 3L,
            now = now,
        )
        assertThat(result).isEqualTo(WorkResult.success())
        assertThat(drive.uploads).hasSize(1)
        assertThat(prefs.lastUploadedRowCountValue).isEqualTo(3L)
        assertThat(prefs.syncBlockedReasonValue).isNull()
    }

    @Test
    fun prunes_older_files_after_successful_upload() = runTest {
        // 22 files all 60 days old + newly uploaded one. Retention deletes 2 (rank 21-22 of the
        // pre-prune 22; after upload the new file is rank 1, so old files shift to rank 2-23,
        // and 21..23 should die — that's old-20, old-21, old-22).
        val oldFiles = (1..22).map {
            BackupFile(id = "old-$it", name = "txtracker-backup-old-$it.json", modifiedAt = now.minus(60, kotlinx.datetime.DateTimeUnit.DAY, kotlinx.datetime.TimeZone.UTC))
        }
        val afterUpload = oldFiles + BackupFile("new-id", "txtracker-backup-20260515T120000Z.json", now)
        val drive = FakeDrive(listResponses = listOf(afterUpload))
        val prefs = FakePrefs(enabled = true, baseline = 100L)
        val exporter = FakeExporter(""" {"transactions":[...]} """)
        val result = CloudSyncWorker.execute(
            prefs = prefs,
            backupExporter = exporter,
            driveClient = drive,
            currentRowCount = 100L,
            now = now,
        )
        assertThat(result).isEqualTo(WorkResult.success())
        assertThat(drive.deletions).containsExactly("old-20", "old-21", "old-22")
    }

    // --- Test doubles below ---

    private class FakeDrive(
        private val listResponses: List<List<BackupFile>> = listOf(emptyList()),
    ) : DriveClientLike {
        val uploads = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        private var listCalls = 0
        override suspend fun uploadDated(content: String, at: Instant): Result<Unit> {
            uploads += content
            return Result.success(Unit)
        }
        override suspend fun listAll(): Result<List<BackupFile>> {
            val r = listResponses.getOrElse(listCalls) { listResponses.last() }
            listCalls++
            return Result.success(r)
        }
        override suspend fun delete(fileId: String): Result<Unit> {
            deletions += fileId
            return Result.success(Unit)
        }
    }

    private class FakeExporter(private val json: String) : BackupExporterLike {
        override suspend fun exportToJsonString(cutoff: cy.txtracker.export.YearMonth?): String = json
    }

    private class FakePrefs(
        enabled: Boolean,
        paused: Boolean = false,
        baseline: Long = CloudSyncGuard.UNKNOWN_BASELINE,
    ) : CloudSyncPrefsLike {
        override val enabled = MutableStateFlow(enabled).asStateFlow()
        override val paused = MutableStateFlow(paused).asStateFlow()
        override val transactionCutoff = MutableStateFlow<cy.txtracker.export.YearMonth?>(null).asStateFlow()
        var lastUploadedRowCountValue = baseline
        var syncBlockedReasonValue: String? = null
        var lastSyncSuccess: Boolean? = null
        var lastSyncError: String? = null
        override val lastUploadedRowCount: StateFlow<Long> get() = MutableStateFlow(lastUploadedRowCountValue).asStateFlow()
        override fun setLastUploadedRowCount(value: Long) { lastUploadedRowCountValue = value }
        override fun setSyncBlockedReason(value: String?) { syncBlockedReasonValue = value }
        override fun setLastSync(success: Boolean, error: String?) {
            lastSyncSuccess = success; lastSyncError = error
        }
    }
}
```

The test references three small interfaces (`DriveClientLike`, `BackupExporterLike`, `CloudSyncPrefsLike`). Define them in the worker file for testability — keeps the worker pure-function-friendly without mocking frameworks.

- [ ] **Step 3: Run the test to verify it fails**

```
./gradlew :app:testDebugUnitTest --tests "cy.txtracker.cloud.CloudSyncWorkerTest"
```

Expected: BUILD FAILED — the seams (`DriveClientLike`, etc.) and updated `execute()` signature don't exist yet.

- [ ] **Step 4: Rewrite `CloudSyncWorker.kt`**:

```kotlin
package cy.txtracker.cloud

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cy.txtracker.data.TransactionRepository
import cy.txtracker.export.BackupExporter
import cy.txtracker.export.YearMonth
import cy.txtracker.service.CloudSyncPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Performs the upload. Triggered by [CloudSyncScheduler] via WorkManager unique work with a
 * 15-min initial delay and KEEP policy.
 *
 * Pipeline:
 *   1. Pre-upload guard. Compare the local transaction count to [CloudSyncPrefs.lastUploadedRowCount].
 *      If [CloudSyncGuard] returns [CloudSyncGuard.Decision.Skip], stash the reason in prefs and
 *      return success (skip is success — the worker has done its job, the user must intervene).
 *   2. Export the backup to JSON.
 *   3. Upload to a new dated filename. The previous canonical file (if any) is left in place.
 *   4. List all backup files. Apply [BackupRetentionPolicy] to decide which to delete. Delete
 *      them (failures here are logged, NOT propagated — the new upload already succeeded).
 *   5. Update prefs: baseline = currentRowCount, blocked reason = null, lastSync = success.
 */
@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val prefs: CloudSyncPrefs,
    private val backupExporter: BackupExporter,
    private val driveClient: DriveClient,
    private val repository: TransactionRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        execute(
            prefs = CloudSyncPrefsAdapter(prefs),
            backupExporter = BackupExporterAdapter(backupExporter),
            driveClient = DriveClientAdapter(driveClient),
            currentRowCount = repository.countAllTransactions(),
            now = Clock.System.now(),
        )

    companion object {
        private const val TAG = "CloudSyncWorker"

        /** Pure-ish execute used by tests. */
        suspend fun execute(
            prefs: CloudSyncPrefsLike,
            backupExporter: BackupExporterLike,
            driveClient: DriveClientLike,
            currentRowCount: Long,
            now: Instant,
        ): Result {
            if (!prefs.enabled.value || prefs.paused.value) {
                Log.i(TAG, "Bailing: enabled=${prefs.enabled.value} paused=${prefs.paused.value}")
                return Result.success()
            }

            val decision = CloudSyncGuard.evaluate(
                currentRowCount = currentRowCount,
                baselineRowCount = prefs.lastUploadedRowCount.value,
            )
            if (decision is CloudSyncGuard.Decision.Skip) {
                Log.w(TAG, "Guard blocked upload: ${decision.reason}")
                prefs.setSyncBlockedReason(decision.reason)
                prefs.setLastSync(success = false, error = decision.reason)
                return Result.success()
            }

            val json = try {
                backupExporter.exportToJsonString(prefs.transactionCutoff.value)
            } catch (t: Throwable) {
                Log.w(TAG, "Export failed: ${t.message}", t)
                prefs.setLastSync(success = false, error = t.message ?: "Export failed")
                return Result.failure()
            }

            Log.i(TAG, "Uploading ${json.length} bytes (cutoff=${prefs.transactionCutoff.value})")
            val uploadResult = driveClient.uploadDated(json, now)
            if (uploadResult.isFailure) {
                val e = uploadResult.exceptionOrNull()
                val message = e?.message ?: "Upload failed"
                Log.w(TAG, "Upload failed: $message", e)
                prefs.setLastSync(success = false, error = message)
                return when (e) {
                    is TransientNetworkException -> Result.retry()
                    else -> Result.failure()
                }
            }

            // Prune older files. Failures are non-fatal: the new upload is already safe.
            try {
                val all = driveClient.listAll().getOrThrow()
                val toDelete = BackupRetentionPolicy.selectToDelete(all, now)
                for (id in toDelete) {
                    driveClient.delete(id).onFailure { e ->
                        Log.w(TAG, "Failed to prune $id: ${e.message}", e)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Prune step failed (upload still successful): ${t.message}", t)
            }

            prefs.setLastUploadedRowCount(currentRowCount)
            prefs.setSyncBlockedReason(null)
            prefs.setLastSync(success = true, error = null)
            return Result.success()
        }
    }

    /** Test seam: minimal subset of [CloudSyncPrefs] the worker uses. */
    interface CloudSyncPrefsLike {
        val enabled: StateFlow<Boolean>
        val paused: StateFlow<Boolean>
        val transactionCutoff: StateFlow<YearMonth?>
        val lastUploadedRowCount: StateFlow<Long>
        fun setLastUploadedRowCount(value: Long)
        fun setSyncBlockedReason(value: String?)
        fun setLastSync(success: Boolean, error: String?)
    }

    /** Test seam: minimal subset of [BackupExporter]. */
    interface BackupExporterLike {
        suspend fun exportToJsonString(cutoff: YearMonth?): String
    }

    /** Test seam: minimal subset of [DriveClient]. */
    interface DriveClientLike {
        suspend fun uploadDated(content: String, at: Instant): Result<Unit>
        suspend fun listAll(): Result<List<BackupFile>>
        suspend fun delete(fileId: String): Result<Unit>
    }

    private class CloudSyncPrefsAdapter(private val real: CloudSyncPrefs) : CloudSyncPrefsLike {
        override val enabled get() = real.enabled
        override val paused get() = real.paused
        override val transactionCutoff get() = real.transactionCutoff
        override val lastUploadedRowCount get() = real.lastUploadedRowCount
        override fun setLastUploadedRowCount(value: Long) = real.setLastUploadedRowCount(value)
        override fun setSyncBlockedReason(value: String?) = real.setSyncBlockedReason(value)
        override fun setLastSync(success: Boolean, error: String?) = real.setLastSync(success, error)
    }

    private class BackupExporterAdapter(private val real: BackupExporter) : BackupExporterLike {
        override suspend fun exportToJsonString(cutoff: YearMonth?): String =
            real.exportToJsonString(cutoff)
    }

    private class DriveClientAdapter(private val real: DriveClient) : DriveClientLike {
        override suspend fun uploadDated(content: String, at: Instant) = real.uploadDated(content, at)
        override suspend fun listAll() = real.listAll()
        override suspend fun delete(fileId: String) = real.delete(fileId)
    }
}
```

- [ ] **Step 5: Run all tests**

```
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/cy/txtracker/cloud/CloudSyncWorker.kt app/src/main/java/cy/txtracker/data/TransactionRepository.kt app/src/main/java/cy/txtracker/data/TransactionDao.kt app/src/test/java/cy/txtracker/cloud/CloudSyncWorkerTest.kt
git commit -m "CloudSyncWorker: guard + dated upload + retention prune"
```

---

## Task 6: Settings — sync-blocked banner

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/settings/cloud/CloudSyncSection.kt`

- [ ] **Step 1: Expose `syncBlockedReason` from `SettingsViewModel`**

In `SettingsViewModel.kt`, add near the other cloud-sync state exposures:

```kotlin
    val syncBlockedReason: StateFlow<String?> = cloudSyncPrefs.syncBlockedReason

    /** Clears the block and triggers an immediate upload. The next worker run will reset the
     *  baseline from the current local state, so subsequent uploads aren't blocked. */
    fun resumeBlockedSync() {
        cloudSyncPrefs.setSyncBlockedReason(null)
        // Wipe the baseline so the guard skips on the next run (UNKNOWN_BASELINE → Proceed),
        // then sets a new baseline from current local. This is the user's "yes I intend this
        // to be the new state" override.
        cloudSyncPrefs.setLastUploadedRowCount(CloudSyncGuard.UNKNOWN_BASELINE)
        cloudSyncScheduler.enqueueImmediateUpload()
    }
```

Add the import:

```kotlin
import cy.txtracker.cloud.CloudSyncGuard
```

- [ ] **Step 2: Render the banner in `CloudSyncSection.kt`**

Locate the existing cloud-sync section composable. Add — at the top of the section content, above the sign-in/sync controls — a conditional banner:

```kotlin
val blockedReason by viewModel.syncBlockedReason.collectAsStateWithLifecycle()
if (blockedReason != null) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Cloud sync paused",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                blockedReason ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::resumeBlockedSync) {
                    Text("Resume sync (use current local data)")
                }
            }
        }
    }
}
```

(Import any missing Compose Material 3 symbols as required.)

- [ ] **Step 3: Compile + smoke**

```
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt app/src/main/java/cy/txtracker/ui/settings/cloud/CloudSyncSection.kt
git commit -m "Settings: sync-blocked banner + resume action"
```

---

## Task 7: Settings — restore-from-cloud picker

**Files:**
- Modify: `app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/cy/txtracker/ui/settings/cloud/CloudSyncSection.kt`

The existing restore-from-cloud action restores the newest backup. This task adds a "Choose backup" affordance that opens a bottom sheet listing all backups (newest first), each with date and size. Tapping one restores that specific backup. The default action ("Restore latest") still exists for the common case.

- [ ] **Step 1: Expose the picker state from the ViewModel**

Add to `SettingsViewModel.kt`:

```kotlin
    private val _restorePickerState = MutableStateFlow<RestorePickerState>(RestorePickerState.Hidden)
    val restorePickerState: StateFlow<RestorePickerState> = _restorePickerState.asStateFlow()

    fun openRestorePicker() {
        _restorePickerState.value = RestorePickerState.Loading
        viewModelScope.launch {
            val result = driveClient.listAll()
            _restorePickerState.value = result.fold(
                onSuccess = { files ->
                    val sorted = files.sortedByDescending { it.modifiedAt }
                    RestorePickerState.Loaded(sorted)
                },
                onFailure = { RestorePickerState.Error(it.message ?: "Failed to list backups") },
            )
        }
    }

    fun dismissRestorePicker() {
        _restorePickerState.value = RestorePickerState.Hidden
    }

    fun restoreFromCloudById(fileId: String) {
        _cloudSyncStatus.value = CloudSyncStatus.Restoring
        _restorePickerState.value = RestorePickerState.Hidden
        viewModelScope.launch {
            try {
                backupExporter.saveLocalRollbackSnapshot("pre-cloud-restore")
                val json = driveClient.download(fileId).getOrThrow()
                val result = backupImporter.importFromJsonString(json)
                _cloudSyncStatus.value = CloudSyncStatus.RestoreSuccess(result)
            } catch (t: Throwable) {
                _cloudSyncStatus.value = CloudSyncStatus.Error(t.message ?: "Restore failed")
            }
        }
    }

    sealed interface RestorePickerState {
        data object Hidden : RestorePickerState
        data object Loading : RestorePickerState
        data class Loaded(val files: List<cy.txtracker.cloud.BackupFile>) : RestorePickerState
        data class Error(val message: String) : RestorePickerState
    }
```

- [ ] **Step 2: Add the picker UI to `CloudSyncSection.kt`**

Below the existing "Restore from cloud" row, add a second row "Choose backup…" whose click handler calls `viewModel.openRestorePicker()`. Then add a `ModalBottomSheet` that observes `viewModel.restorePickerState`:

```kotlin
val pickerState by viewModel.restorePickerState.collectAsStateWithLifecycle()
if (pickerState !is SettingsViewModel.RestorePickerState.Hidden) {
    ModalBottomSheet(onDismissRequest = viewModel::dismissRestorePicker) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Choose backup to restore", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            when (val s = pickerState) {
                is SettingsViewModel.RestorePickerState.Loading -> {
                    CircularProgressIndicator()
                }
                is SettingsViewModel.RestorePickerState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
                is SettingsViewModel.RestorePickerState.Loaded -> {
                    if (s.files.isEmpty()) {
                        Text("No cloud backups found.")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                            items(s.files) { file ->
                                ListItem(
                                    headlineContent = { Text(formatBackupTimestamp(file.modifiedAt)) },
                                    supportingContent = { Text(file.name) },
                                    modifier = Modifier.clickable {
                                        viewModel.restoreFromCloudById(file.id)
                                    },
                                )
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
```

Add a small helper at the bottom of the file:

```kotlin
private fun formatBackupTimestamp(at: kotlinx.datetime.Instant): String {
    val local = at.toLocalDateTime(cy.txtracker.domain.MalaysiaTimeZone)
    return "%04d-%02d-%02d %02d:%02d".format(local.year, local.monthNumber, local.dayOfMonth, local.hour, local.minute)
}
```

- [ ] **Step 3: Compile**

```
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/cy/txtracker/ui/settings/SettingsViewModel.kt app/src/main/java/cy/txtracker/ui/settings/cloud/CloudSyncSection.kt
git commit -m "Settings: cloud-restore backup picker"
```

---

## Task 8: Update FUTURE.md to record this work

**Files:**
- Modify: `FUTURE.md` (local-only, untracked — kept for context)

- [ ] **Step 1: Append a "✅ DONE" entry under the cloud-sync item**

In FUTURE.md item 5, find the **Deferred follow-ups** list and update the rotation entry:

```diff
 **Deferred follow-ups:**
-- Multi-device active sync (pull-on-foreground, periodic downloads).
-- Snapshot rotation in Drive (keep last N versions vs single canonical file).
-- gzip compression for backups with many transactions.
+- Multi-device active sync (pull-on-foreground, periodic downloads).
+- Snapshot rotation in Drive — ✅ DONE (2026-05-15). Dated filenames,
+  retention = last 20 OR <30 days, pre-upload row-count shrink guard.
+  Settings adds a backup picker for selective restore.
+- gzip compression for backups with many transactions.
```

No commit needed; FUTURE.md is local-only.

---

## Notes on what's not covered

- **First-sync edge case.** A user who signs in for the first time and then triggers a destructive migration *before* any successful upload has no baseline (`UNKNOWN_BASELINE`); guard proceeds, cloud could still be overwritten on that first upload. Mitigation: on sign-in completion, if `driveClient.exists()` returns true, download the newest backup, count transactions in the JSON, and seed `lastUploadedRowCount` from that. Deferred — this is a narrow window and the rotation backup gives recoverability even in this case.
- **Local rollback snapshot on auto-upload.** The auto-upload path does not call `BackupExporter.saveLocalRollbackSnapshot()` even now. With rotation in place this is less urgent, but a defense-in-depth follow-up is to snapshot on every Nth run.
- **`fallbackToDestructiveMigration()` itself.** Left in place per the discussion in the parent conversation — a crash-on-mismatch (option A) was rejected. The dev footgun remains; B+C address only the data-loss consequence.

---

## Self-Review

**1. Spec coverage:**
- B (rotation) → Tasks 1 (policy), 4 (Drive API), 5 (worker wiring). ✓
- C (shrink guard) → Tasks 2 (guard), 3 (prefs), 5 (worker wiring). ✓
- UX (banner + picker) → Tasks 6, 7. ✓
- Backward compat with legacy `txtracker-backup.json` → handled in Task 4 (the prefix-based listAll matches the legacy name too). ✓
- Retention policy "last 20 OR <30 days" → Task 1 constants. ✓

**2. Placeholder scan:** No TBDs. All code shown in full at each step.

**3. Type consistency:** `BackupFile(id, name, modifiedAt)` defined in Task 1 and used identically in Tasks 4, 5, 7. `CloudSyncGuard.Decision` defined in Task 2 and used in Task 5. `CloudSyncGuard.UNKNOWN_BASELINE` defined in Task 2 and used in Tasks 3, 5, 6. `selectToDelete` signature matches across Tasks 1 and 5. `uploadDated(content, at)` signature matches across Tasks 4 and 5. ✓
