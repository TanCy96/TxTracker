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
class MigrationV9ToV10Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrates_v9_to_v10_seeds_cash_and_preserves_transactions() {
        helper.createDatabase(DB_NAME, 9).apply {
            execSQL(
                """
                INSERT INTO transactions(
                    amountMinor, currency, merchantRaw, merchantNormalized, categoryId,
                    description, occurredAt, timeBucket, sourceApp, rawText, direction,
                    createdAt, notificationDedupeKey, needsVerification,
                    needsCurrencyConfirmation, merchantUserEdited
                )
                VALUES(1300, 'MYR', 'TEST', 'TEST', NULL, NULL, 1700000000000, 'MIDDAY',
                       'anything', NULL, 'OUT', 1700000000000, 'k1', 0, 0, 0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 10, true)

        db.query("SELECT amountMinor, fundingSourceId FROM transactions WHERE merchantRaw = 'TEST'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(1300L)
            assertThat(c.isNull(1)).isTrue()
        }

        db.query("SELECT COUNT(*) FROM funding_sources WHERE kind = 'CASH'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }

        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_funding_sources_sourceAppHint_last4'",
        ).use { c -> assertThat(c.moveToFirst()).isTrue() }
    }

    companion object { private const val DB_NAME = "migration-test.db" }
}
