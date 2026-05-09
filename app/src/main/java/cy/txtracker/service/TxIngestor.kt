package cy.txtracker.service

import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.data.computeDedupeKey
import cy.txtracker.data.normalizeMerchant
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.domain.bucketOf
import cy.txtracker.parsing.ParsedTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock

/**
 * Bridges parsed notifications into the data layer. Owns the assembly step:
 * normalize the merchant, compute the time bucket and the dedupe key, then call
 * [TransactionRepository.insert] (which drops on dedupe collision).
 *
 * Extracted from the listener so the mapping is testable without booting a
 * `NotificationListenerService`. The listener wires real `StatusBarNotification`
 * objects to parsers; everything downstream of `parser.parse()` lives here.
 *
 * `categoryId` and `description` are left null on insert. Auto-categorization and
 * auto-description suggestions are added by later tasks; until then, captured
 * transactions appear in the "Unverified" bucket and the user labels them.
 */
@Singleton
class TxIngestor @Inject constructor(
    private val repository: TransactionRepository,
    private val categorizationEngine: CategorizationEngine,
    private val descriptionEngine: DescriptionEngine,
) {
    /**
     * Inserts the parsed transaction. Returns the new row ID, or null if dropped on dedupe.
     *
     * @param needsVerification true when the source was the [HeuristicExtractor] rather than
     *   a strict per-source parser. Surfaces the row in the home screen "Pending" filter so
     *   the user can confirm or delete before it counts as real spend.
     */
    suspend fun ingest(parsed: ParsedTransaction, needsVerification: Boolean = false): Long? {
        val merchantNormalized = normalizeMerchant(parsed.merchantRaw)
        val bucket = bucketOf(parsed.occurredAt)
        val categoryId = categorizationEngine.categorize(merchantNormalized)
        val description = descriptionEngine.suggest(merchantNormalized, categoryId, bucket)
        val dedupeKey = computeDedupeKey(
            amountMinor = parsed.amountMinor,
            merchantNormalized = merchantNormalized,
            occurredAt = parsed.occurredAt,
        )
        val row = Transaction(
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
            merchantRaw = parsed.merchantRaw,
            merchantNormalized = merchantNormalized,
            categoryId = categoryId,
            description = description,
            occurredAt = parsed.occurredAt,
            timeBucket = bucket,
            sourceApp = parsed.sourceApp,
            rawText = parsed.rawText,
            direction = parsed.direction,
            createdAt = Clock.System.now(),
            notificationDedupeKey = dedupeKey,
            needsVerification = needsVerification,
        )
        return repository.insert(row)
    }
}
