package cy.txtracker.cloud

/**
 * Pre-upload safety check on cloud sync. Compares the local transaction row count against
 * the last successfully-uploaded count to refuse uploads when the local DB has shrunk
 * suspiciously (e.g., a destructive Room migration wiped it).
 *
 * Pure function — caller handles side effects (logging, prefs writes, surfacing a banner).
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
        // Empty local must never auto-overwrite cloud unless cloud is also known-empty.
        // Covers both the destructive-migration / uninstall-reinstall case (baseline reset
        // to UNKNOWN_BASELINE) and the runtime-wipe case (baseline still has rows). The
        // only allowed empty-upload is when the last successful upload was also empty
        // (baseline == 0), where another empty is a no-op.
        if (currentRowCount == 0L && baselineRowCount != 0L) {
            return Decision.Skip(
                "Local data is empty — sync paused to avoid overwriting any cloud backup. " +
                    "Restore from cloud first, or sign out with 'also delete cloud backup' " +
                    "if this empty state is intentional.",
            )
        }
        if (baselineRowCount == UNKNOWN_BASELINE) return Decision.Proceed
        if (baselineRowCount == 0L) return Decision.Proceed
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
