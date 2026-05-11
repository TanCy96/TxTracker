package cy.txtracker.parsing

import cy.txtracker.data.Direction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant

/**
 * Last-resort capture for notifications from known finance-app packages where the
 * heuristic has failed.
 *
 * Triggers when:
 *   - The source app is in [SourcePackages.PERMISSIVE_PACKAGES], AND
 *   - The notification text contains *any* `RM` or `MYR` amount.
 *
 * Doesn't try to extract a merchant. The captured row uses a source-app label as the
 * merchant ("GWallet (review)", "CIMB (review)", …) and stores the full notification text
 * in `rawText`. The user reviews the row from the home Pending filter and either confirms
 * with edits, or marks "Not a transaction" to delete it.
 *
 * Conservative on what counts as a "finance app": package names known to belong to wallets
 * or banks that operate in Malaysia. Adding a new app is a one-line addition to
 * [SourcePackages.PERMISSIVE_PACKAGES].
 *
 * Keeping the amount requirement matters — without it, a "Welcome to Wallet" notification
 * would create a phantom RM 0.00 row.
 */
@Singleton
class PermissiveExtractor @Inject constructor() {

    fun extract(text: String, sourceApp: String, postedAt: Instant): ParsedTransaction? {
        if (sourceApp !in SourcePackages.PERMISSIVE_PACKAGES) return null
        if (text.isBlank()) return null
        val match = AMOUNT.find(text) ?: return null

        return ParsedTransaction(
            amountMinor = parseRinggitAmountMinor(match.groups["amount"]!!.value),
            currency = "MYR",
            merchantRaw = "${sourceLabel(sourceApp)} (review)",
            occurredAt = postedAt,
            sourceApp = sourceApp,
            rawText = text,
            direction = Direction.OUT,
        )
    }

    /** Friendlier short name for the row's merchant placeholder, derived from the package. */
    private fun sourceLabel(sourceApp: String): String = when {
        sourceApp == SourcePackages.GOOGLE_WALLET -> "GWallet"
        sourceApp == SourcePackages.GOOGLE_PAY -> "GPay"
        sourceApp.contains("cimb") -> "CIMB"
        sourceApp.contains("maybank") -> "Maybank"
        sourceApp.contains("publicbank") -> "Public Bank"
        sourceApp.contains("rhb") -> "RHB"
        sourceApp.contains("hsbc") -> "HSBC"
        sourceApp.contains("hlb") || sourceApp.contains("hongleong") -> "Hong Leong"
        sourceApp.contains("ambank") -> "AmBank"
        sourceApp.contains("bsn") -> "BSN"
        sourceApp.contains("gxs") || sourceApp.contains("gxbank") -> "GX Bank"
        sourceApp.contains("wise") || sourceApp.contains("transferwise") -> "Wise"
        sourceApp == SourcePackages.TOUCH_N_GO -> "TnG"
        sourceApp == SourcePackages.GRAB -> "Grab"
        else -> sourceApp.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    companion object {
        // Same shape as in HeuristicExtractor; duplicated rather than shared because the
        // permissive layer's contract is "amount alone is enough" and we don't want a
        // future change to the heuristic to silently widen this layer too.
        private val AMOUNT = Regex(
            """\b(?:RM|MYR)\s*(?<amount>[\d,]+\.\d{2})\b""",
        )
    }
}
