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
class MigrationV10ToV11Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrates_v10_to_v11_backfills_legacy_dedupeKey() {
        helper.createDatabase(DB_NAME, 10).apply {
            execSQL(
                """
                INSERT INTO captured_notifications(
                    packageName, postedAt, amountMinor, currency, rawText,
                    rewrittenText, disposition, promotedToTxId, capturedAt
                ) VALUES(
                    'com.test', 1700000000000, 500, 'MYR', 'sample', NULL,
                    'PENDING', NULL, 1700000000000
                )
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 11, true)

        db.query("SELECT id, dedupeKey FROM captured_notifications WHERE packageName = 'com.test'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            val id = c.getLong(0)
            assertThat(c.getString(1)).isEqualTo("legacy-$id")
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' " +
                "AND name='index_captured_notifications_dedupeKey'",
        ).use { c -> assertThat(c.moveToFirst()).isTrue() }
    }

    companion object { private const val DB_NAME = "migration-v10-v11-test.db" }
}
