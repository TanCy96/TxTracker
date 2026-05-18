# Issues found/Bug list

Local-only bug list, not committed. Use this to track issues found or bugs to be fixed.

---

## 1. Strengthen backup and recovery — ✅ DONE (2026-05-15)

Landed across nine commits on 2026-05-15. The two halves of the original
complaint (single overwriting file; data loss going unnoticed) are both
addressed:

- **Multiple backup files in Drive.** `47918dc DriveClient: dated filenames
  + list/by-id operations` replaces the single canonical
  `txtracker-backup.json` with `txtracker-backup-YYYY-MM-DD-HHmmss.json`.
  `9cc4676 Cloud sync: retention policy (keep last 20 OR <30d)` adds
  `BackupRetentionPolicy` so old snapshots prune predictably instead of
  unbounded growth.
- **Versioned-data protection on upload.** `4d6c6594 Cloud sync: pre-upload
  row-count shrink guard` adds `CloudSyncGuard`: the worker compares the
  about-to-upload row count against the last successfully uploaded count;
  if it's shrunk by more than 50% (or any shrink below a small floor),
  the upload is blocked and a sync-blocked reason is persisted to prefs.
- **Resume affordance.** `4081722 Settings: sync-blocked banner + resume
  action` surfaces a banner with an explicit "resume" button that clears
  the block and wipes the baseline so the next worker run uploads cleanly.
- **Selective restore from any snapshot.** `e78cfe8 Settings: cloud-restore
  backup picker` lists every dated snapshot in Drive and lets the user
  pick which one to restore from — no longer locked to the most-recent
  file.

Verify after upgrade: trigger a build that drops most local rows → next
sync should NOT upload; banner should appear; tapping "Resume" should
re-enqueue and complete. Restore picker should list the dated files in
Drive in descending order.

## 2. Incorrect and inconsistent push notification — ✅ DONE (2026-05-15)

Fixed in `b1141ec NotificationScheduler: re-anchor summary on cadence/hour
change`. The summary worker wasn't re-enqueueing when the hour/cadence pref
changed mid-window, so the new 9pm time wouldn't take effect until the next
natural reschedule. The scheduler now cancels and re-enqueues against the new
firing time on any pref change. Verify on device by changing the hour and
confirming the next firing matches.

## 3. Amount parsing — ✅ DONE (2026-05-15)

Fixed in `ba772b2 Parsing: accept 4+ digit amounts without thousands
separator`. The AMOUNT regex's leading group was `\d{1,3}(?:,\d{3})*`, so
`MYR 1163.27` (no comma) parsed as `116` → RM 116.00. Widened to
`(?:\d{1,3}(?:,\d{3})+|\d+)` — comma-grouped or bare integer. Tests added
for CIMB's exact shape and the suffix-form analogue.

## 4. Smarter Improvement — 🟡 PARTIAL (2026-05-16)

Three of the four sub-asks shipped via the smarter-categorization work
(FUTURE.md #9). Auto-categorize now uses longest-prefix merchant matching
plus per-category user-editable regex patterns; auto-describe gains the
same prefix path plus a (category, any-bucket) fallback; merchant notes
were already per-merchant and editable. **Still open:** filtering
non-transaction notifications when CAPTURE_ALL is on (the "approach A"
brainstorm path, explicitly deferred from the categorization spec).
