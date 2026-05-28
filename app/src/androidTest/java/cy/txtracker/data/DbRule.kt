package cy.txtracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

/**
 * JUnit rule that builds a fresh in-memory [TxDatabase] for each test, with the seed
 * callback wired up so default categories exist exactly as they will in production.
 */
class DbRule : ExternalResource() {
    lateinit var db: TxDatabase
        private set

    override fun before() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, TxDatabase::class.java)
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        TxDatabase.seedCategories(db)
                        val now = System.currentTimeMillis()
                        db.execSQL(
                            "INSERT INTO funding_sources(kind, displayName, last4, sourceAppHint, isUserNamed, createdAt, updatedAt) " +
                                "VALUES('CASH', 'Cash', NULL, NULL, 0, ?, ?)",
                            arrayOf<Any?>(now, now),
                        )
                    }
                },
            )
            .allowMainThreadQueries()
            .build()
    }

    override fun after() {
        db.close()
    }

    val transactionDao: TransactionDao get() = db.transactionDao()
    val categoryDao: CategoryDao get() = db.categoryDao()
    val merchantMappingDao: MerchantMappingDao get() = db.merchantMappingDao()
    val descriptionMappingDao: DescriptionMappingDao get() = db.descriptionMappingDao()
    val merchantNoteDao: MerchantNoteDao get() = db.merchantNoteDao()
    val userFacingSourceDao: UserFacingSourceDao get() = db.userFacingSourceDao()
    val approvedSourceDao: ApprovedSourceDao get() = db.approvedSourceDao()
    val capturedNotificationDao: CapturedNotificationDao get() = db.capturedNotificationDao()
    val rejectedSourceDao: RejectedSourceDao get() = db.rejectedSourceDao()
    val trackedCurrencyDao: TrackedCurrencyDao get() = db.trackedCurrencyDao()
    val tripWindowDao: TripWindowDao get() = db.tripWindowDao()
    val fundingSourceDao: FundingSourceDao get() = db.fundingSourceDao()
}
