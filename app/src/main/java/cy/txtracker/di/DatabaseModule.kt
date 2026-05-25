package cy.txtracker.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cy.txtracker.data.CapturedNotificationDao
import cy.txtracker.data.CategoryDao
import cy.txtracker.data.DescriptionMappingDao
import cy.txtracker.data.MerchantMappingDao
import cy.txtracker.data.MerchantNoteDao
import cy.txtracker.data.PackageTextRewriteDao
import cy.txtracker.data.RejectedSourceDao
import cy.txtracker.data.TransactionDao
import cy.txtracker.data.TxDatabase
import cy.txtracker.data.ApprovedSourceDao
import cy.txtracker.data.UserFacingSourceDao
import cy.txtracker.data.TrackedCurrencyDao
import cy.txtracker.data.TripWindowDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TxDatabase =
        Room.databaseBuilder(context, TxDatabase::class.java, TxDatabase.DB_NAME)
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        TxDatabase.seedCategories(db)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Self-healing safety net. Room's `onCreate` only fires on the very
                        // first DB creation; after a destructive migration the schema is
                        // recreated but onCreate does NOT re-fire, so the seed wouldn't run
                        // and the user would land in an app with zero categories. Re-running
                        // the seed here is cheap (one SELECT COUNT) and idempotent (bails when
                        // rows already exist). Also covers any future accidental wipe.
                        val cursor = db.query("SELECT COUNT(*) FROM categories")
                        val count = cursor.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                        if (count == 0) {
                            TxDatabase.seedCategories(db)
                        }
                    }
                },
            )
            // Real migration from v2 (which existed in the wild during testing) to v3
            // (adds the merchant_notes table). Preserves all captured transactions and
            // learned mappings rather than wiping them. fallbackToDestructiveMigration
            // stays as a safety net for any unforeseen mismatch.
            .addMigrations(
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
            )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTransactionDao(db: TxDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideCategoryDao(db: TxDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideMerchantMappingDao(db: TxDatabase): MerchantMappingDao = db.merchantMappingDao()

    @Provides
    fun provideDescriptionMappingDao(db: TxDatabase): DescriptionMappingDao =
        db.descriptionMappingDao()

    @Provides
    fun provideMerchantNoteDao(db: TxDatabase): MerchantNoteDao = db.merchantNoteDao()

    @Provides
    fun provideUserFacingSourceDao(db: TxDatabase): UserFacingSourceDao =
        db.userFacingSourceDao()

    @Provides
    fun provideApprovedSourceDao(db: TxDatabase): ApprovedSourceDao =
        db.approvedSourceDao()

    @Provides
    fun provideCapturedNotificationDao(db: TxDatabase): CapturedNotificationDao =
        db.capturedNotificationDao()

    @Provides
    fun provideRejectedSourceDao(db: TxDatabase): RejectedSourceDao =
        db.rejectedSourceDao()

    @Provides
    fun provideTrackedCurrencyDao(db: TxDatabase): TrackedCurrencyDao = db.trackedCurrencyDao()

    @Provides
    fun provideTripWindowDao(db: TxDatabase): TripWindowDao = db.tripWindowDao()

    @Provides
    fun providePackageTextRewriteDao(db: TxDatabase): PackageTextRewriteDao =
        db.packageTextRewriteDao()
}

/**
 * Adds the `merchant_notes` table introduced in v3. Schema mirrors what Room would
 * generate for [cy.txtracker.data.MerchantNote] so the resulting DB matches a fresh
 * install on v3.
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `merchant_notes` (
                `merchantNormalized` TEXT NOT NULL,
                `note` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`merchantNormalized`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * Adds the `user_facing_sources` table introduced in v4. Schema mirrors what Room would
 * generate for [cy.txtracker.data.UserFacingSource].
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `user_facing_sources` (
                `packageName` TEXT NOT NULL,
                `addedAt` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * Adds the `approved_sources` table introduced in v5 and backfills it with the package names
 * of every already-verified transaction. This way users who upgrade keep their existing
 * finance-app coverage when capture-all-packages is later turned off — without needing to
 * re-verify a row from each source. Schema mirrors what Room would generate for
 * [cy.txtracker.data.ApprovedSource] so the resulting DB matches a fresh install on v5.
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `approved_sources` (
                `packageName` TEXT NOT NULL,
                `firstApprovedAt` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`)
            )
            """.trimIndent(),
        )
        // Backfill: for every package the user has already verified at least one transaction
        // from, record the earliest createdAt as firstApprovedAt. Skip manual entries — they
        // aren't packages.
        db.execSQL(
            """
            INSERT OR IGNORE INTO `approved_sources` (`packageName`, `firstApprovedAt`)
            SELECT sourceApp, MIN(createdAt)
            FROM `transactions`
            WHERE `needsVerification` = 0
              AND sourceApp != 'manual'
            GROUP BY sourceApp
            """.trimIndent(),
        )
    }
}

/**
 * Adds `tracked_currencies` and `trip_windows` tables introduced in v6, and the
 * `needsCurrencyConfirmation` column on `transactions`. Schema mirrors what Room
 * would generate for [cy.txtracker.data.TrackedCurrency], [cy.txtracker.data.TripWindow],
 * and the augmented Transaction so the resulting DB matches a fresh install on v6.
 */
private val MIGRATION_5_6 = object : Migration(5, 6) {
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

/**
 * Adds `keywordPattern` column to categories table introduced in v7.
 * Schema mirrors what Room would generate for the augmented Category so the
 * resulting DB matches a fresh install on v7.
 */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `categories` ADD COLUMN `keywordPattern` TEXT DEFAULT NULL"
        )
    }
}

/**
 * v8 introduces:
 *   - `package_text_rewrites` table for per-package raw-text rewrite rules applied
 *     before the parser runs.
 *   - `transactions.merchantUserEdited` column so the Settings → "Re-parse merchants
 *     from raw text" sweep can skip rows the user already fixed by hand.
 *
 * Schema mirrors what Room would generate so the resulting DB matches a fresh
 * install on v8.
 */
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `package_text_rewrites` (
                `packageName` TEXT NOT NULL,
                `pattern` TEXT NOT NULL,
                `replacement` TEXT NOT NULL,
                `learnedAt` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`, `pattern`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_package_text_rewrites_packageName` " +
                "ON `package_text_rewrites`(`packageName`)"
        )
        db.execSQL(
            "ALTER TABLE `transactions` ADD COLUMN `merchantUserEdited` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * v9 introduces a device-local capture pool for amount-bearing notifications that the
 * heuristic parser could not promote to real transactions, plus a rejected package list.
 * Existing permissive "(review)" transactions with raw notification text are moved into
 * the pool so the user can review them without polluting the transaction list.
 */
private val MIGRATION_8_9 = object : Migration(8, 9) {
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
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_captured_notifications_packageName` " +
                "ON `captured_notifications`(`packageName`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_captured_notifications_disposition` " +
                "ON `captured_notifications`(`disposition`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_captured_notifications_capturedAt` " +
                "ON `captured_notifications`(`capturedAt`)"
        )
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
