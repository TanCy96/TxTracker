package cy.txtracker.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudSyncSchedulerTest {

    @Test
    fun watchedTables_includeEveryBackupSerializedTable() {
        val watched = watchedTables()

        assertThat(watched).containsAtLeast(
            "transactions",
            "categories",
            "merchant_mappings",
            "merchant_description_mappings",
            "category_description_mappings",
            "merchant_notes",
            "user_facing_sources",
            "approved_sources",
            "funding_sources",
            "tracked_currencies",
            "trip_windows",
            "sl_debit_account",
            "sl_debit_deposit",
            "reimbursement_entries",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun watchedTables(): Set<String> {
        val field = CloudSyncScheduler::class.java.getDeclaredField("WATCHED_TABLES")
        field.isAccessible = true
        return (field.get(null) as Array<String>).toSet()
    }
}
