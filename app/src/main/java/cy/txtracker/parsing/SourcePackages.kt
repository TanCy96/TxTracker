package cy.txtracker.parsing

/**
 * Single source of truth for the package names of finance apps TxTracker recognizes.
 *
 * After retirement of the four strict per-source parsers (Google Wallet, TnG, Grab, CIMB),
 * these constants live here so the heuristic, permissive, source-tier, and UI layers can
 * all reference the same set without re-introducing per-source classes. Adding a new
 * finance app: add its package id to [PERMISSIVE_PACKAGES] (and to the named constants
 * above if other code references it by name).
 */
object SourcePackages {

    // Named constants — anything referenced by package outside of the allowlist itself.
    const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"
    const val GOOGLE_PAY = "com.google.android.apps.nbu.paisa.user"
    const val GRAB = "com.grabtaxi.passenger"
    const val TOUCH_N_GO = "my.com.tngdigital.ewallet"

    /**
     * Packages the listener should consider as potentially-finance apps. Notifications
     * from any package not in this set are dropped at the top of the listener (unless
     * CAPTURE_ALL_PACKAGES is on, which is a discovery aid). Adding a candidate here is
     * the cheapest way to ensure a finance app never silently drops a payment.
     */
    val PERMISSIVE_PACKAGES: Set<String> = setOf(
        // Wallets and booking apps
        GOOGLE_WALLET,
        GOOGLE_PAY,
        TOUCH_N_GO,
        GRAB,

        // CIMB variants
        "com.cimb.octo",
        "com.cimbbank.octo",
        "com.cimbmalaysia",
        "com.cimb.cimbclicks.my",
        "com.cimb.bizchannel",

        // Maybank — main banking app and the separately-installed MAE companion app
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

        // GX Bank — the Grab-affiliated digital bank in Malaysia
        "my.com.gxsbank",
        "com.gxsbank.my",

        // Wise — international payments
        "com.transferwise.android",
        "com.wise.android",

        // Boost / ShopeePay
        "my.com.myboost",
        "com.shopee.my",
    )
}
