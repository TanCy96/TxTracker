package cy.txtracker.service

import cy.txtracker.parsing.HeuristicExtractor
import cy.txtracker.parsing.NotificationAmountParser
import cy.txtracker.parsing.ParsedTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant

sealed interface CaptureDecision {
    data class Parsed(val parsed: ParsedTransaction) : CaptureDecision
    data class Pooled(
        val packageName: String,
        val postedAt: Instant,
        val amountMinor: Long,
        val currency: String,
        val rawText: String,
        val rewrittenText: String?,
        val capturedAt: Instant,
    ) : CaptureDecision
    data object Dropped : CaptureDecision
}

@Singleton
class CapturePipeline @Inject constructor(
    private val heuristicExtractor: HeuristicExtractor,
) {
    fun decide(
        packageName: String,
        rawText: String,
        rewrittenText: String,
        postedAt: Instant,
        symbolDefaults: Map<String, String>,
        capturedAt: Instant,
        isRejected: Boolean = false,
    ): CaptureDecision {
        val heuristic = heuristicExtractor.extract(
            text = rewrittenText,
            sourceApp = packageName,
            postedAt = postedAt,
            symbolDefaults = symbolDefaults,
        )?.copy(rawText = rawText)
        if (heuristic != null) {
            // Non-rejected: a confident parse becomes a (verifiable) home transaction.
            if (!isRejected) return CaptureDecision.Parsed(heuristic)
            // Rejected: keep the spec's hidden safety net — pool the entry (hidden by the
            // PENDING-minus-rejected filter) instead of surfacing it on home, and never reach
            // the listener's auto-track call.
            return CaptureDecision.Pooled(
                packageName = packageName,
                postedAt = postedAt,
                amountMinor = heuristic.amountMinor,
                currency = heuristic.currency,
                rawText = rawText,
                rewrittenText = rewrittenText.takeIf { it != rawText },
                capturedAt = capturedAt,
            )
        }

        val amount = NotificationAmountParser.findFirst(rewrittenText, symbolDefaults)
            ?: return CaptureDecision.Dropped

        return CaptureDecision.Pooled(
            packageName = packageName,
            postedAt = postedAt,
            amountMinor = amount.amountMinor,
            currency = amount.currency,
            rawText = rawText,
            rewrittenText = rewrittenText.takeIf { it != rawText },
            capturedAt = capturedAt,
        )
    }
}
