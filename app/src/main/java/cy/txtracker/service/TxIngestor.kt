package cy.txtracker.service

import androidx.room.withTransaction
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.data.TxDatabase
import cy.txtracker.data.computeDedupeKey
import cy.txtracker.data.normalizeMerchant
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.domain.bucketOf
import cy.txtracker.parsing.FundingSourceClassifier
import cy.txtracker.parsing.ParsedTransaction
import cy.txtracker.parsing.SourceTier
import cy.txtracker.parsing.SourceTierResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock

/**
 * Bridges parsed notifications into the data layer. Owns the assembly step:
 * normalize the merchant, compute the time bucket and the dedupe key, then apply
 * the cross-source dedup rules before insert.
 *
 * Two-step dedupe:
 *   1. Hash collision (existing) — exact same amount + merchant + 5-min bucket from
 *      any source drops via the unique index on `notificationDedupeKey`.
 *   2. Cross-source (new) — same amount + currency + 5-min bucket from a DIFFERENT
 *      source app. If incoming is `USER_FACING` and existing is `CARD_LAYER`, the
 *      existing row is promoted in place (source fields rewritten, user-edited
 *      `categoryId`/`description` preserved, dedupe key recomputed). Otherwise the
 *      incoming row is dropped.
 */
@Singleton
class TxIngestor @Inject constructor(
    private val database: TxDatabase,
    private val repository: TransactionRepository,
    private val categorizationEngine: CategorizationEngine,
    private val descriptionEngine: DescriptionEngine,
    private val sourceTierResolver: SourceTierResolver,
    private val fundingSourceClassifier: FundingSourceClassifier,
) {
    /**
     * Inserts the parsed transaction. Returns the new row ID for fresh inserts, the
     * existing row ID for cross-source promotions, or null when dropped.
     */
    suspend fun ingest(parsed: ParsedTransaction, needsVerification: Boolean = false): Long? {
        val merchantNormalized = normalizeMerchant(parsed.merchantRaw)
        val bucket = bucketOf(parsed.occurredAt)
        val categoryId =
            if (parsed.currency == "MYR") categorizationEngine.categorize(merchantNormalized) else null
        val description = descriptionEngine.suggest(merchantNormalized, categoryId, bucket)
        val dedupeKey = computeDedupeKey(
            amountMinor = parsed.amountMinor,
            merchantNormalized = merchantNormalized,
            occurredAt = parsed.occurredAt,
            currency = parsed.currency,
        )
        val fundingSourceId = fundingSourceClassifier.classify(
            rawText = parsed.rawText,
            sourceApp = parsed.sourceApp,
            now = parsed.occurredAt,
        )

        return database.withTransaction {
            // Step 2: cross-source check (step 1 — hash collision — falls through to the
            // OnConflict.IGNORE on insert below).
            val existing = repository.findCrossMerchantDupe(
                amountMinor = parsed.amountMinor,
                currency = parsed.currency,
                occurredAt = parsed.occurredAt,
                excludeSourceApp = parsed.sourceApp,
            )
            if (existing != null) {
                val incomingTier = sourceTierResolver.tierFor(parsed.sourceApp)
                val existingTier = sourceTierResolver.tierFor(existing.sourceApp)
                if (incomingTier == SourceTier.USER_FACING &&
                    existingTier == SourceTier.CARD_LAYER
                ) {
                    // Intentionally not passing fundingSourceId here. Spec invariant: when two
                    // notifications for the same payment collapse, the surviving row keeps the
                    // FundingSource of whichever notification was classified first (typically
                    // the GWallet/CARD_LAYER push, which arrives seconds before the bank SMS
                    // and has a richer card-name string via classifier rule 1). The just-
                    // classified fundingSourceId from the incoming notification is dropped.
                    repository.promoteSourceFields(
                        id = existing.id,
                        merchantRaw = parsed.merchantRaw,
                        merchantNormalized = merchantNormalized,
                        sourceApp = parsed.sourceApp,
                        rawText = parsed.rawText,
                        notificationDedupeKey = dedupeKey,
                        needsVerification = existing.needsVerification && needsVerification,
                    )
                    return@withTransaction existing.id
                }
                // Else: incoming is Tier 2 against Tier 1, or both same tier → drop.
                return@withTransaction null
            }

            val needsCurrencyConfirmation = if (parsed.currency == "MYR") {
                false
            } else {
                repository.ensureTrackedCurrency(parsed.currency)
                repository.findActiveTrip(parsed.currency, parsed.occurredAt) == null
            }

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
                needsCurrencyConfirmation = needsCurrencyConfirmation,
                fundingSourceId = fundingSourceId,
            )
            repository.insert(row)
        }
    }
}
