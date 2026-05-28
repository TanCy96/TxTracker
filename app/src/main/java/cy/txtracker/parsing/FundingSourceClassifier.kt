package cy.txtracker.parsing

import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceDao
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.MANUAL_SOURCE_APP
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant

data class FundingSourceDetection(
    val kind: FundingSourceKind,
    val displayName: String,
    val last4: String?,
    val sourceAppHint: String?,
)

@Singleton
class FundingSourceClassifier @Inject constructor(
    private val fundingSourceDao: FundingSourceDao,
) {
    /**
     * Resolve (lookup-then-create) a funding source for an incoming transaction.
     *
     * Hot-path behavior: indexed lookup by [(sourceAppHint, last4)]. Insert only fires the
     * first time a (bank, last-4) pair is observed. Existing rows are returned as-is —
     * user-set kind/displayName persist against future ingest.
     */
    suspend fun classify(rawText: String?, sourceApp: String, now: Instant): Long {
        // Manual entries always go to the seeded Cash source.
        if (sourceApp == MANUAL_SOURCE_APP) {
            return fundingSourceDao.getDefaultCash()?.id
                ?: error("Cash source missing — migration v9->v10 should have seeded it")
        }
        val detected = detect(rawText, sourceApp)
        fundingSourceDao.findByKey(detected.sourceAppHint, detected.last4)?.let { return it.id }
        return fundingSourceDao.insert(
            FundingSource(
                kind = detected.kind,
                displayName = detected.displayName,
                last4 = detected.last4,
                sourceAppHint = detected.sourceAppHint,
                isUserNamed = false,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    companion object {
        // Rule 1: "<MERCHANT> RM<amt> with <CARD> ••<last4>" — reuses the same shape as
        // CARD_SPEND_PATTERN in HeuristicExtractor. Captures the card name verbatim.
        private val RULE_1 = Regex(
            """with\s+(?<card>.+?)\s+[•*]+\s*(?<last4>\d{4})""",
            RegexOption.IGNORE_CASE,
        )

        // Rule 2: bullets/asterisks immediately preceding a last-4, no card name preamble.
        private val RULE_2 = Regex("""[•*]+\s*(?<last4>\d{4})\b""")

        // Rule 3: literal "card ending 1234" or "card 1234" in body text.
        private val RULE_3 = Regex(
            """\bcard\s+(?:ending\s+)?(?<last4>\d{4})\b""",
            RegexOption.IGNORE_CASE,
        )

        // Rule 4: literal "A/C ending 1234" or "account ending 1234". Distinguishes a debit
        // account from a credit card by the literal word used.
        private val RULE_4 = Regex(
            """\b(?:A/C|account)\s+(?:ending\s+)?(?<last4>\d{4})\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Pure detection — no DAO interaction. Splitting this out lets us unit-test the
         * rule table without a mocked DAO and lets the lookup-then-create code use the
         * result directly.
         */
        fun detect(rawText: String?, sourceApp: String): FundingSourceDetection {
            val text = rawText.orEmpty()
            val bankLabel = SourceLabels.label(sourceApp)
            // Rule 1.
            RULE_1.find(text)?.let { m ->
                val card = m.groups["card"]!!.value.trim()
                val last4 = m.groups["last4"]!!.value
                return FundingSourceDetection(
                    kind = FundingSourceKind.CREDIT_CARD,
                    displayName = "$card $last4",
                    last4 = last4,
                    sourceAppHint = sourceApp,
                )
            }
            // Rule 2.
            RULE_2.find(text)?.let { m ->
                val last4 = m.groups["last4"]!!.value
                return FundingSourceDetection(
                    kind = FundingSourceKind.CREDIT_CARD,
                    displayName = "$bankLabel card $last4",
                    last4 = last4,
                    sourceAppHint = sourceApp,
                )
            }
            // Rule 3.
            RULE_3.find(text)?.let { m ->
                val last4 = m.groups["last4"]!!.value
                return FundingSourceDetection(
                    kind = FundingSourceKind.CREDIT_CARD,
                    displayName = "$bankLabel card $last4",
                    last4 = last4,
                    sourceAppHint = sourceApp,
                )
            }
            // Rule 4.
            RULE_4.find(text)?.let { m ->
                val last4 = m.groups["last4"]!!.value
                return FundingSourceDetection(
                    kind = FundingSourceKind.DEBIT_BANK,
                    displayName = "$bankLabel account $last4",
                    last4 = last4,
                    sourceAppHint = sourceApp,
                )
            }
            // Rules 5-8 fall in here in Task 4. Placeholder fallback for this task so the
            // method always returns:
            return FundingSourceDetection(
                kind = FundingSourceKind.DEBIT_BANK,
                displayName = "$bankLabel (unknown account)",
                last4 = null,
                sourceAppHint = sourceApp,
            )
        }
    }
}
