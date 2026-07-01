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
 * v15 -> v16 adds per-trip categories. The `categories` table is recreated with a nullable
 * `tripId` FK to `trip_windows(id)` (ON DELETE CASCADE). Existing categories become global
 * (tripId = NULL); every existing trip is seeded with the travel template; and categoryId is
 * cleared on all non-MYR transactions so trips start clean.
 *
 * Instrumented — compile-gated here; run by user/CI on a device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV15ToV16Test {

    private val dbName = "migration-v15-v16-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate15To16_backfills_global_seeds_trips_and_clears_foreign_categories() {
        helper.createDatabase(dbName, 15).use { db ->
            // A trip and two categorized transactions: one MYR, one USD.
            db.execSQL(
                "INSERT INTO trip_windows(id, currency, startAt, endAt, createdAt) " +
                    "VALUES (1, 'USD', 1000, NULL, 1000)",
            )
            db.execSQL(
                "INSERT INTO categories(id, name, color, isCustom, sortOrder, keywordPattern) " +
                    "VALUES (10, 'Food', 1, 0, 0, NULL)",
            )
            db.execSQL(
                "INSERT INTO transactions(amountMinor, currency, merchantRaw, merchantNormalized, " +
                    "categoryId, occurredAt, timeBucket, sourceApp, direction, createdAt, " +
                    "notificationDedupeKey, needsVerification, needsCurrencyConfirmation, merchantUserEdited) " +
                    "VALUES (500,'MYR','A','A',10,2000,'AFTERNOON','p','OUT',3000,'k-myr',0,0,0)",
            )
            db.execSQL(
                "INSERT INTO transactions(amountMinor, currency, merchantRaw, merchantNormalized, " +
                    "categoryId, occurredAt, timeBucket, sourceApp, direction, createdAt, " +
                    "notificationDedupeKey, needsVerification, needsCurrencyConfirmation, merchantUserEdited) " +
                    "VALUES (900,'USD','B','B',10,2500,'AFTERNOON','p','OUT',3000,'k-usd',0,0,0)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        // Existing category kept, now global.
        db.query("SELECT tripId FROM categories WHERE id = 10").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
        }
        // Trip 1 seeded with the template.
        db.query("SELECT COUNT(*) FROM categories WHERE tripId = 1").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(DefaultTripCategories.template.size)
        }
        // MYR categoryId preserved; USD cleared.
        db.query("SELECT categoryId FROM transactions WHERE notificationDedupeKey = 'k-myr'").use { c ->
            c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(10)
        }
        db.query("SELECT categoryId FROM transactions WHERE notificationDedupeKey = 'k-usd'").use { c ->
            c.moveToFirst(); assertThat(c.isNull(0)).isTrue()
        }
    }
}

// Test copy of the production MIGRATION_15_16 (which is `private` in DatabaseModule.kt, in the
// `cy.txtracker.di` package and therefore unreachable from here). Kept byte-identical to the
// production version so the test exercises the exact migration SQL that ships.
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `categories_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `color` INTEGER NOT NULL,
                `isCustom` INTEGER NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `keywordPattern` TEXT,
                `tripId` INTEGER,
                FOREIGN KEY(`tripId`) REFERENCES `trip_windows`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `categories_new` (id, name, color, isCustom, sortOrder, keywordPattern, tripId)
            SELECT id, name, color, isCustom, sortOrder, keywordPattern, NULL FROM `categories`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `categories`")
        db.execSQL("ALTER TABLE `categories_new` RENAME TO `categories`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_tripId` ON `categories`(`tripId`)")

        // Seed template categories for every existing trip.
        val tripIds = mutableListOf<Long>()
        db.query("SELECT id FROM `trip_windows`").use { c ->
            while (c.moveToNext()) tripIds.add(c.getLong(0))
        }
        tripIds.forEach { TxDatabase.seedTripCategories(db, it) }

        // Existing foreign categorizations pointed at Home categories; clear them so trips
        // start clean against their travel template (see design doc, "Existing data").
        db.execSQL("UPDATE `transactions` SET `categoryId` = NULL WHERE `currency` != 'MYR'")
    }
}
