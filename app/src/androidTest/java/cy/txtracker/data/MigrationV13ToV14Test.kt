package cy.txtracker.data

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v13 -> v14 adds the `reimbursement_entries` child table and backfills one DEBIT_BANK entry
 * per transaction that already carries a `reimbursedMinor > 0` (the column added at v13).
 * Re-sequenced from main's v12->v13 because on this branch v12 is SL Debit and v13 is the
 * reimbursedMinor column.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV13ToV14Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbName = "migration-v13-v14-test"

    @Test
    fun backfills_one_debit_bank_entry_per_reimbursed_transaction() {
        helper.createDatabase(dbName, 13).use { db ->
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

        val db = helper.runMigrationsAndValidate(dbName, 14, true, MIGRATION_13_14)

        db.query("SELECT transactionId, amountMinor, destinationKind, personLabel FROM reimbursement_entries")
            .use { c ->
                assertThat(c.count).isEqualTo(1)
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(1)).isEqualTo(5000)
                assertThat(c.getString(2)).isEqualTo("DEBIT_BANK")
                assertThat(c.isNull(3)).isTrue()
            }
        db.close()
    }
}

// Test copy of the production MIGRATION_13_14 (which is `private` in DatabaseModule.kt, in the
// `cy.txtracker.di` package and therefore unreachable from here). Kept byte-identical to the
// production version so the test exercises the exact migration SQL that ships.
private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `reimbursement_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `transactionId` INTEGER NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `destinationKind` TEXT NOT NULL,
                `personLabel` TEXT,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reimbursement_entries_transactionId` " +
                "ON `reimbursement_entries`(`transactionId`)",
        )
        db.execSQL(
            """
            INSERT INTO `reimbursement_entries`
                (`transactionId`, `amountMinor`, `destinationKind`, `personLabel`, `createdAt`)
            SELECT `id`, `reimbursedMinor`, 'DEBIT_BANK', NULL, `occurredAt`
            FROM `transactions`
            WHERE `reimbursedMinor` IS NOT NULL AND `reimbursedMinor` > 0
            """.trimIndent(),
        )
    }
}
