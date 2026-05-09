package cy.txtracker.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import cy.txtracker.data.CategoryDao
import cy.txtracker.data.DescriptionMappingDao
import cy.txtracker.data.MerchantMappingDao
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
            // Acceptable while the app is in early development with no users to migrate.
            // Once we ship to friends and care about preserving their captured rows, replace
            // this with proper Migration objects per schema bump.
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
}
