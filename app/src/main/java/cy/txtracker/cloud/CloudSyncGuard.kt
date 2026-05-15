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
