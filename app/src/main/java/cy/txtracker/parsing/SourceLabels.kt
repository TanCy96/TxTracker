package cy.txtracker.parsing

object SourceLabels {
    fun label(sourceApp: String): String = when {
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
}
