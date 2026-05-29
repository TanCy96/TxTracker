package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SlDebitDao {

    // Account (singleton, id = 1) ---------------------------------------
    @Query("SELECT * FROM sl_debit_account WHERE id = 1")
    fun observeAccount(): Flow<SlDebitAccount?>

    @Query("SELECT * FROM sl_debit_account WHERE id = 1")
    suspend fun getAccount(): SlDebitAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: SlDebitAccount)

    @Update
    suspend fun updateAccount(account: SlDebitAccount)

    // Deposits ----------------------------------------------------------
    @Insert
    suspend fun insertDeposit(deposit: SlDebitDeposit): Long

    @Update
    suspend fun updateDeposit(deposit: SlDebitDeposit)

    @Query("DELETE FROM sl_debit_deposit WHERE id = :id")
    suspend fun deleteDeposit(id: Long)

    @Query("SELECT * FROM sl_debit_deposit ORDER BY occurredAt DESC")
    fun observeDeposits(): Flow<List<SlDebitDeposit>>

    @Query("SELECT * FROM sl_debit_deposit ORDER BY occurredAt ASC")
    suspend fun getDepositsOnce(): List<SlDebitDeposit>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM sl_debit_deposit")
    fun observeDepositSum(): Flow<Long>
}
