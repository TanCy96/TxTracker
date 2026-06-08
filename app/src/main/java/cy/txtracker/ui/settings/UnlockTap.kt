package cy.txtracker.ui.settings

/**
 * Result of one tap on the hidden version-line unlock gesture (Android developer-options style).
 *
 * @property newCount the tap count to carry forward in ephemeral UI state (0 once unlocked or when
 *   already unlocked — nothing persists except the resulting [FeatureFlags] flag).
 * @property unlocked true on the tap that reaches the threshold.
 * @property hintRemaining non-null on the final taps before unlock, for a "N taps away" toast.
 */
data class TapResult(
    val newCount: Int,
    val unlocked: Boolean,
    val hintRemaining: Int?,
)

/**
 * Pure tap accounting. Holds no state; the caller keeps [currentCount] in `remember`. When
 * [alreadyUnlocked] is true the gesture is a no-op. Reaching [threshold] taps returns
 * `unlocked = true` and resets the count.
 */
fun registerUnlockTap(
    currentCount: Int,
    alreadyUnlocked: Boolean,
    threshold: Int = 7,
): TapResult {
    if (alreadyUnlocked) return TapResult(newCount = 0, unlocked = false, hintRemaining = null)
    val next = currentCount + 1
    if (next >= threshold) return TapResult(newCount = 0, unlocked = true, hintRemaining = null)
    val remaining = threshold - next
    val hint = if (remaining in 1..2) remaining else null
    return TapResult(newCount = next, unlocked = false, hintRemaining = hint)
}
