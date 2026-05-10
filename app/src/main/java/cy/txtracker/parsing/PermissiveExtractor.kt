package cy.txtracker.parsing

import cy.txtracker.data.Direction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant

/**
 * Last-resort capture for notifications from known finance-app packages where the strict
 * parser AND the heuristic have both failed.
 *
 * Triggers when:
 *   - The source app is in [PERMISSIVE_PACKAGES], AND
 *   - The notification text contains *any* `RM` or `MYR` amount.
 *
 * Doesn't try to extract a merchant. The captured row uses a source-app label as the
 * merchant ("GWallet (review)", "CIMB (review)", …) and stores the full notification text
 * in `rawText`. The user reviews the row from the home Pending filter and either confirms
 * with edits, or marks "Not a transaction" to delete it.
 *
 * Conservative on what counts as a "finance app": package names known to belong to wallets
 * or banks that operate in Malaysia. Adding a new app is a one-line addition.
 *
 * Keeping the amount requirement matters — without it, a "Welcome to Wallet" notification
 * would create a phantom RM 0.00 row.
 */
@Singleton
class PermissiveExtractor @Inject constructor() {

    fun extract(text: String, sourceApp: String, postedAt: Instant): ParsedTransaction? {
        if (sourceApp !in PERMISSIVE_PACKAGES) return null
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
        sourceApp == GoogleWalletParser.GOOGLE_WALLET_PACKAGE -> "GWallet"
        sourceApp == GOOGLE_PAY_PACKAGE -> "GPay"
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
        sourceApp == TouchNGoParser.TNG_PACKAGE -> "TnG"
        sourceApp == GrabParser.GRAB_PACKAGE -> "Grab"
        else -> sourceApp.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    companion object {
        // Same shape as in HeuristicExtractor; duplicated rather than shared because the
        // permissive layer's contract is "amount alone is enough" and we don't want a
        // future change to the heuristic to silently widen this layer too.
        private val AMOUNT = Regex(
            """\b(?:RM|MYR)\s*(?<amount>[\d,]+\.\d{2})\b""",
        )

        const val GOOGLE_PAY_PACKAGE = "com.google.android.apps.nbu.paisa.user"

        /**
         * Packages the listener should watch for notifications even when no strict parser
         * claims them. Adding a candidate here is the cheapest way to ensure a finance app
         * never silently drops a payment notification — the row will land as Pending with
         * a source label, and the user reviews from there.
         */
        val PERMISSIVE_PACKAGES: Set<String> = setOf(
            // Wallets
            GoogleWalletParser.GOOGLE_WALLET_PACKAGE,
            GOOGLE_PAY_PACKAGE,
            TouchNGoParser.TNG_PACKAGE,
            GrabParser.GRAB_PACKAGE,
            // CIMB variants (already claimed by CIMBParser, but listed for correctness if
            // the strict parser ever stops claiming them)
            "com.cimb.octo",
            "com.cimbbank.octo",
            "com.cimbmalaysia",
            "com.cimb.cimbclicks.my",
            "com.cimb.bizchannel",
            // Maybank — main banking app and the separately-installed MAE companion app.
            "com.maybank2u.life",
            "my.com.maybank2u.life",
            "com.maybank2u.mae",
            "my.com.maybank2u.mae",
            // Public Bank
            "com.publicbank.pbe",
            "com.publicbank.publicbankebanking",
            // Hong Leong
            "com.hl.connect",
            "com.hongleong.hlb",
            "com.hl.hlb.connectfirst",
            // HSBC
            "com.hsbc.hsbcclassic",
            "uk.co.hsbc.hsbcukpersonalbanking",
            // RHB
            "com.rhb.mobile",
            "com.rhbgroup.rhbmobilebanking",
            // AmBank
            "com.ambank.amonline",
            "my.com.ambank.amonline",
            // BSN
            "com.bsn.bsnsignaturemobile",
            "com.bsn.cms",
            // GX Bank — the Grab-affiliated digital bank in Malaysia. Both candidate IDs
            // listed; whichever doesn't match is harmless.
            "my.com.gxsbank",
            "com.gxsbank.my",
            // Wise — international payments. Original "transferwise" ID and current "wise"
            // ID, again both harmless if absent.
            "com.transferwise.android",
            "com.wise.android",
            // Boost / ShopeePay
            "my.com.myboost",
            "com.shopee.my",
        )
    }
}
