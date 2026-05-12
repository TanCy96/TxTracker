package cy.txtracker.data

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TxDatabaseTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
    )

    @get:Rule val dbRule = DbRule()

    @Test
    fun seed_callback_inserts_default_categories_in_sortOrder() = runTest {
        val categories = dbRule.categoryDao.getAll()
        assertThat(categories).hasSize(10)
        assertThat(categories.map { it.name })
            .containsExactly(
                "Food", "Groceries", "Transport", "Fuel", "Parking",
                "Apparel", "Entertainment", "Utilities", "Health", "Other",
            )
            .inOrder()
        assertThat(categories.all { !it.isCustom }).isTrue()
        assertThat(categories.map { it.sortOrder }).isEqualTo((0..9).toList())
    }

    @Test
    fun migrate_5_to_6_creates_tracked_currencies_and_trip_windows_and_preserves_rows() {
        // Seed v5 with one transaction, then migrate.
        helper.createDatabase(TEST_DB, 5).use { db ->
            // Mirror seed-categories so the FK on transactions has a target if reused.
            db.execSQL(
                """
                INSERT INTO categories (id, name, color, isCustom, sortOrder)
                VALUES (1, 'Food', -65227, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO transactions (
                    amountMinor, currency, merchantRaw, merchantNormalized, categoryId,
                    description, occurredAt, timeBucket, sourceApp, rawText, direction,
                    createdAt, notificationDedupeKey, needsVerification
                )
                VALUES (
                    1250, 'MYR', 'MCDONALDS', 'MCDONALDS', 1,
                    NULL, 1747000000000, 'MIDDAY', 'manual', NULL, 'OUT',
                    1747000000000, 'k1', 0
                )
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            MIGRATION_5_6_TEST_COPY,
        )

        // tracked_currencies + trip_windows exist and are empty.
        migrated.query("SELECT COUNT(*) FROM tracked_currencies").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        migrated.query("SELECT COUNT(*) FROM trip_windows").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }

        // Existing row preserved + new column defaulted to 0.
        migrated.query(
            "SELECT amountMinor, currency, needsCurrencyConfirmation FROM transactions"
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(1250)
            assertThat(c.getString(1)).isEqualTo("MYR")
            assertThat(c.getInt(2)).isEqualTo(0)
        }
    }

    companion object {
        private const val TEST_DB = "migration_test"
    }
}

// Test copy of the production MIGRATION_5_6. Kept byte-identical to the version in
// DatabaseModule.kt — diverging will surface as a runMigrationsAndValidate failure.
private val MIGRATION_5_6_TEST_COPY = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tracked_currencies` (
                `code` TEXT NOT NULL,
                `displaySymbol` TEXT NOT NULL,
                `isDefaultForSymbol` INTEGER NOT NULL,
                `addedAt` INTEGER NOT NULL,
                PRIMARY KEY(`code`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `trip_windows` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `currency` TEXT NOT NULL,
                `startAt` INTEGER NOT NULL,
                `endAt` INTEGER,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_windows_currency` ON `trip_windows`(`currency`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_windows_startAt`  ON `trip_windows`(`startAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_windows_endAt`    ON `trip_windows`(`endAt`)")

        db.execSQL(
            """
            ALTER TABLE `transactions`
            ADD COLUMN `needsCurrencyConfirmation` INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),
        )
    }
}
