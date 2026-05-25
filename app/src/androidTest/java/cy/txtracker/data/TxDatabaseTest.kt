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

    @Test
    fun migrate_8_to_9_moves_review_transactions_with_raw_text_into_pool() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            seedV8Category(db)
            insertV8Transaction(
                db = db,
                merchantRaw = "GX Bank (review)",
                sourceApp = "my.com.gxsbank",
                rawText = "RM9.40 to ML TRADITIONAL DESSERT",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            MIGRATION_8_9_TEST_COPY,
        )

        migrated.query("SELECT COUNT(*) FROM transactions").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        migrated.query(
            "SELECT packageName, amountMinor, currency, rawText, disposition FROM captured_notifications"
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("my.com.gxsbank")
            assertThat(c.getLong(1)).isEqualTo(940L)
            assertThat(c.getString(2)).isEqualTo("MYR")
            assertThat(c.getString(3)).contains("ML TRADITIONAL")
            assertThat(c.getString(4)).isEqualTo("PENDING")
        }
    }

    @Test
    fun migrate_8_to_9_keeps_review_literal_when_raw_text_is_null() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            seedV8Category(db)
            insertV8Transaction(
                db = db,
                merchantRaw = "Shop (review)",
                sourceApp = "manual",
                rawText = null,
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            MIGRATION_8_9_TEST_COPY,
        )

        migrated.query("SELECT COUNT(*) FROM transactions").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        migrated.query("SELECT COUNT(*) FROM captured_notifications").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun migrate_8_to_9_creates_rejected_sources() {
        helper.createDatabase(TEST_DB, 8).close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            MIGRATION_8_9_TEST_COPY,
        )

        migrated.execSQL(
            "INSERT INTO rejected_sources (packageName, rejectedAt) VALUES ('com.chat', 1770000000000)"
        )
        migrated.query("SELECT packageName FROM rejected_sources").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("com.chat")
        }
    }

    companion object {
        private const val TEST_DB = "migration_test"

        private fun seedV8Category(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT INTO categories (id, name, color, isCustom, sortOrder, keywordPattern)
                VALUES (1, 'Food', -65227, 0, 0, NULL)
                """.trimIndent(),
            )
        }

        private fun insertV8Transaction(
            db: SupportSQLiteDatabase,
            merchantRaw: String,
            sourceApp: String,
            rawText: String?,
        ) {
            db.execSQL(
                """
                INSERT INTO transactions (
                    amountMinor, currency, merchantRaw, merchantNormalized, categoryId,
                    description, occurredAt, timeBucket, sourceApp, rawText, direction,
                    createdAt, notificationDedupeKey, needsVerification,
                    needsCurrencyConfirmation, merchantUserEdited
                )
                VALUES (
                    940, 'MYR', ?, 'GX BANK REVIEW', 1,
                    NULL, 1770000000000, 'MIDDAY', ?, ?, 'OUT',
                    1770000000000, ?, 1, 0, 0
                )
                """.trimIndent(),
                arrayOf<Any?>(merchantRaw, sourceApp, rawText, "key-$merchantRaw"),
            )
        }
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

private val MIGRATION_8_9_TEST_COPY = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `captured_notifications` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `packageName` TEXT NOT NULL,
                `postedAt` INTEGER NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `currency` TEXT NOT NULL,
                `rawText` TEXT NOT NULL,
                `rewrittenText` TEXT,
                `disposition` TEXT NOT NULL,
                `promotedToTxId` INTEGER,
                `capturedAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_notifications_packageName` ON `captured_notifications`(`packageName`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_notifications_disposition` ON `captured_notifications`(`disposition`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_notifications_capturedAt` ON `captured_notifications`(`capturedAt`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `rejected_sources` (
                `packageName` TEXT NOT NULL,
                `rejectedAt` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO captured_notifications (
                packageName, postedAt, amountMinor, currency, rawText, rewrittenText,
                disposition, promotedToTxId, capturedAt
            )
            SELECT sourceApp, occurredAt, amountMinor, currency, rawText, NULL,
                   'PENDING', NULL, occurredAt
            FROM transactions
            WHERE merchantRaw LIKE '% (review)' AND rawText IS NOT NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            DELETE FROM transactions
            WHERE merchantRaw LIKE '% (review)' AND rawText IS NOT NULL
            """.trimIndent(),
        )
    }
}
