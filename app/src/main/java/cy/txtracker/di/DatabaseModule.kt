package cy.txtracker.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cy.txtracker.data.CategoryDao
import cy.txtracker.data.DescriptionMappingDao
import cy.txtracker.data.MerchantMappingDao
import cy.txtracker.data.MerchantNoteDao
import cy.txtracker.data.TransactionDao
import cy.txtracker.data.TxDatabase
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
            .addMigrations(MIGRATION_2_3)
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
