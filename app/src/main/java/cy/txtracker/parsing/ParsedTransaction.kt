package cy.txtracker.parsing

import cy.txtracker.data.Direction
import kotlinx.datetime.Instant

/**
 * The output of the heuristic or permissive extractor. The listener then computes
 * `merchantNormalized`, `timeBucket`, and `notificationDedupeKey` from these values before
 * inserting into the DB.
 */
data class ParsedTransaction(
    /** Amount in minor units (cents). RM 530.00 → 53000. */
    val amountMinor: Long,
    val currency: String,
    /** Merchant string as parsed from the notification, prior to normalization. */
    val merchantRaw: String,
    val occurredAt: Instant,
    val sourceApp: String,
    /** Full notification text, retained for debugging and for re-parsing if rules change. */
    val rawText: String,
    val direction: Direction,
)
