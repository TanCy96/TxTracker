package cy.txtracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationV12ToV13Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbName = "migration-v12-v13-test"

    @Test
    fun backfills_one_debit_bank_entry_per_reimbursed_transaction() {
        helper.createDatabase(dbName, 12).use { db ->
            // Row with reimbursedMinor = 5000 — should get one backfilled entry.
            db.execSQL(
                """
                INSERT INTO transactions
                    (amountMinor, currency, merchantRaw, merchantNormalized, categoryId,
                     description, occurredAt, timeBucket, sourceApp, rawText, direction,
                     createdAt, notificationDedupeKey, needsVerification,
                     needsCurrencyConfirmation, merchantUserEdited, fundingSourceId,
                     reimbursedMinor)
                VALUES
                    (10000, 'MYR', 'A', 'A', NULL, NULL, 1700000000000, 'MIDDAY', 'manual',
                     NULL, 'OUT', 1700000000000, 'k-reimb', 0, 0, 0, NULL, 5000)
                """.trimIndent(),
            )
            // Row with reimbursedMinor = NULL — should NOT get a backfilled entry.
            db.execSQL(
                """
                INSERT INTO transactions
                    (amountMinor, currency, merchantRaw, merchantNormalized, categoryId,
                     description, occurredAt, timeBucket, sourceApp, rawText, direction,
                     createdAt, notificationDedupeKey, needsVerification,
                     needsCurrencyConfirmation, merchantUserEdited, fundingSourceId,
                     reimbursedMinor)
                VALUES
                    (3000, 'MYR', 'B', 'B', NULL, NULL, 1700000000000, 'MIDDAY', 'manual',
                     NULL, 'OUT', 1700000000000, 'k-plain', 0, 0, 0, NULL, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 13, true)

        db.query("SELECT transactionId, amountMinor, destinationKind, personLabel FROM reimbursement_entries")
            .use { c ->
                assertThat(c.count).isEqualTo(1)
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(1)).isEqualTo(5000)
                assertThat(c.getString(2)).isEqualTo("DEBIT_BANK")
                assertThat(c.isNull(3)).isTrue()
            }
    }
}
