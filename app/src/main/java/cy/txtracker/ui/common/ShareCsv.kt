package cy.txtracker.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Fires an ACTION_SEND chooser to share a CSV file [uri]. Shared by Settings + Foreign export. */
fun shareCsv(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export transactions"))
}
