package cy.txtracker.cloud

/**
 * Pre-upload safety check. Compares the local DB's current transaction row count against the
 * last successfully-uploaded count (cached in [cy.txtracker.service.CloudSyncPrefs]).
 *
 * Refuses to upload when the local DB has suspiciously shrunk:
 *   - Hard skip: local is empty (`0`) and baseline had any rows.
 *   - Hard skip: local shrank by more than [SHRINK_THRESHOLD] (currently 50%) from baseline.
 *
 * Pure function — caller (the worker) handles side effects.
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
