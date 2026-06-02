package cy.txtracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReimbursementEntryDaoTest {

    private lateinit var db: TxDatabase
    private lateinit var dao: ReimbursementEntryDao
    private lateinit var txDao: TransactionDao

    private val t0 = Instant.parse("2026-06-02T00:00:00Z")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TxDatabase::class.java,
        ).build()
        dao = db.reimbursementEntryDao()
        txDao = db.transactionDao()
    }

    @After fun tearDown() { db.close() }

    private fun insertTx(dedupe: String, reimbursed: Long? = null): Long = runBlocking {
        txDao.insert(
            Transaction(
                amountMinor = 10000,
                currency = "MYR",
                merchantRaw = "M",
                merchantNormalized = "M",
                categoryId = null,
                description = null,
                occurredAt = t0,
                timeBucket = cy.txtracker.domain.TimeBucket.MIDDAY,
                sourceApp = "manual",
                rawText = null,
                direction = Direction.OUT,
                createdAt = t0,
                notificationDedupeKey = dedupe,
                reimbursedMinor = reimbursed,
            ),
        )
    }

    @Test fun entries_observed_in_created_order() = runBlocking {
        val txId = insertTx("k1")
        dao.insert(ReimbursementEntry(transactionId = txId, amountMinor = 1000, destinationKind = FundingSourceKind.DEBIT_BANK, createdAt = t0))
        dao.insert(ReimbursementEntry(transactionId = txId, amountMinor = 1200, destinationKind = FundingSourceKind.E_WALLET, createdAt = t0))
        val entries = dao.observeForTransaction(txId).first()
        assertThat(entries.map { it.amountMinor }).containsExactly(1000L, 1200L).inOrder()
        assertThat(dao.totalForTransaction(txId)).isEqualTo(2200L)
    }

    @Test fun deleting_parent_transaction_cascades_entries() = runBlocking {
        val txId = insertTx("k2")
        dao.insert(ReimbursementEntry(transactionId = txId, amountMinor = 500, destinationKind = FundingSourceKind.CASH, createdAt = t0))
        txDao.delete(txId)
        assertThat(dao.getForTransaction(txId)).isEmpty()
    }
}
