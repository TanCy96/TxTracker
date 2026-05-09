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
