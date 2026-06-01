package cy.txtracker.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    version = 12,
    exportSchema = true,
    entities = [
        Transaction::class,
        Category::class,
        MerchantMapping::class,
        MerchantDescriptionMapping::class,
        CategoryDescriptionMapping::class,
        MerchantNote::class,
        UserFacingSource::class,
        ApprovedSource::class,
        CapturedNotification::class,
        RejectedSource::class,
        TrackedCurrency::class,
        TripWindow::class,
        PackageTextRewrite::class,
        FundingSource::class,
    ],
)
@TypeConverters(Converters::class)
abstract class TxDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun merchantMappingDao(): MerchantMappingDao
    abstract fun descriptionMappingDao(): DescriptionMappingDao
    abstract fun merchantNoteDao(): MerchantNoteDao
    abstract fun userFacingSourceDao(): UserFacingSourceDao
    abstract fun approvedSourceDao(): ApprovedSourceDao
    abstract fun capturedNotificationDao(): CapturedNotificationDao
    abstract fun rejectedSourceDao(): RejectedSourceDao
    abstract fun trackedCurrencyDao(): TrackedCurrencyDao
    abstract fun tripWindowDao(): TripWindowDao
    abstract fun packageTextRewriteDao(): PackageTextRewriteDao
    abstract fun fundingSourceDao(): FundingSourceDao

    companion object {
        const val DB_NAME = "txtracker.db"

        /**
         * Inserts the default seed categories. Used by the Room creation callback in production
         * and called explicitly from tests that build databases without the callback.
         */
        fun seedCategories(db: SupportSQLiteDatabase) {
            DefaultCategories.seed.forEach { seed ->
                val pattern = cy.txtracker.domain.DefaultKeywordPatterns.byCategoryName[seed.name]
                db.execSQL(
                    """
                    INSERT INTO categories (name, color, isCustom, sortOrder, keywordPattern)
                    VALUES (?, ?, 0, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(seed.name, seed.color, seed.sortOrder, pattern),
                )
            }
        }
    }
}

private data class SeedCategory(val name: String, val color: Int, val sortOrder: Int)

private object DefaultCategories {
    // Distinct Material-style colors so chips are visually separable.
    val seed: List<SeedCategory> = listOf(
        SeedCategory("Food", 0xFFEF5350.toInt(), 0),
        SeedCategory("Groceries", 0xFF66BB6A.toInt(), 1),
        SeedCategory("Transport", 0xFF42A5F5.toInt(), 2),
        SeedCategory("Fuel", 0xFFFF7043.toInt(), 3),
        SeedCategory("Parking", 0xFF8D6E63.toInt(), 4),
        SeedCategory("Apparel", 0xFFAB47BC.toInt(), 5),
        SeedCategory("Entertainment", 0xFFFFCA28.toInt(), 6),
        SeedCategory("Utilities", 0xFF26A69A.toInt(), 7),
        SeedCategory("Health", 0xFFEC407A.toInt(), 8),
        SeedCategory("Other", 0xFF78909C.toInt(), 9),
    )
}
