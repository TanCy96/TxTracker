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
class MigrationV12ToV13Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate12To13_addsReimbursedMinor_keepsItNull() {
        val dbName = "migration-test-12-13"
        helper.createDatabase(dbName, 12).apply {
            execSQL(
                "INSERT INTO transactions(amountMinor, currency, merchantRaw, merchantNormalized, " +
                    "categoryId, description, occurredAt, timeBucket, sourceApp, rawText, direction, " +
                    "createdAt, notificationDedupeKey, needsVerification, needsCurrencyConfirmation, " +
                    "merchantUserEdited) " +
                    "VALUES(1000,'MYR','X','X',NULL,NULL,0,'MORNING','manual',NULL,'OUT',0,'k1',0,0,0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 13, true, MIGRATION_12_13)

        db.query("SELECT reimbursedMinor FROM transactions WHERE notificationDedupeKey='k1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
        }
        db.close()
    }
}

// Test copy of the production MIGRATION_12_13 (which is `private` in DatabaseModule.kt, in the
// `cy.txtracker.di` package and therefore unreachable from here). Kept byte-identical to the
// production version so the test exercises the exact migration SQL that ships.
private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `reimbursedMinor` INTEGER DEFAULT NULL")
    }
}
