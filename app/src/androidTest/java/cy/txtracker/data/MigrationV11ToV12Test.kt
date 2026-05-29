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

@RunWith(AndroidJUnit4::class)
class MigrationV11ToV12Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate11To12_seedsAccount_andKeepsShareNull() {
        val dbName = "migration-test-11-12"
        helper.createDatabase(dbName, 11).apply {
            execSQL(
                "INSERT INTO transactions(amountMinor, currency, merchantRaw, merchantNormalized, " +
                    "categoryId, description, occurredAt, timeBucket, sourceApp, rawText, direction, " +
                    "createdAt, notificationDedupeKey, needsVerification, needsCurrencyConfirmation, " +
                    "merchantUserEdited) " +
                    "VALUES(1000,'MYR','X','X',NULL,NULL,0,'MORNING','manual',NULL,'OUT',0,'k1',0,0,0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)

        db.query("SELECT slShareMinor FROM transactions WHERE notificationDedupeKey='k1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
        }
        db.query("SELECT displayName, defaultSharePercent FROM sl_debit_account WHERE id=1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("SL Debit")
            assertThat(c.getInt(1)).isEqualTo(40)
        }
        db.query("SELECT COUNT(*) FROM sl_debit_deposit").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        db.close()
    }
}

// Test copy of the production MIGRATION_11_12 (which is `private` in DatabaseModule.kt, in the
// `cy.txtracker.di` package and therefore unreachable from here). Kept byte-identical to the
// production version so the test exercises the exact migration SQL that ships.
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `slShareMinor` INTEGER DEFAULT NULL")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sl_debit_account` (
                `id` INTEGER NOT NULL,
                `displayName` TEXT NOT NULL,
                `defaultSharePercent` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sl_debit_deposit` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `occurredAt` INTEGER NOT NULL,
                `note` TEXT,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sl_debit_deposit_occurredAt` " +
                "ON `sl_debit_deposit`(`occurredAt`)",
        )
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO sl_debit_account(id, displayName, defaultSharePercent, createdAt, updatedAt) " +
                "VALUES(1, 'SL Debit', 40, ?, ?)",
            arrayOf<Any?>(now, now),
        )
    }
}
