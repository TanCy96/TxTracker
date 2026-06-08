package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReimbursementEntryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entry: ReimbursementEntry): Long
    @Update suspend fun update(entry: ReimbursementEntry)
    @Delete suspend fun delete(entry: ReimbursementEntry)

    @Query("SELECT * FROM reimbursement_entries WHERE transactionId = :txId ORDER BY createdAt, id")
    fun observeForTransaction(txId: Long): Flow<List<ReimbursementEntry>>

    @Query("SELECT * FROM reimbursement_entries WHERE transactionId = :txId ORDER BY createdAt, id")
    suspend fun getForTransaction(txId: Long): List<ReimbursementEntry>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM reimbursement_entries WHERE transactionId = :txId")
    suspend fun totalForTransaction(txId: Long): Long

    /** All entries across all transactions, for CSV export + backup. */
    @Query("SELECT * FROM reimbursement_entries ORDER BY createdAt, id")
    suspend fun getAll(): List<ReimbursementEntry>
}
