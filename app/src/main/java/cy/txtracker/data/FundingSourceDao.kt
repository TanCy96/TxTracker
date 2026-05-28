package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FundingSourceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: FundingSource): Long

    @Update
    suspend fun update(source: FundingSource)

    @Query("SELECT * FROM funding_sources WHERE id = :id")
    suspend fun getById(id: Long): FundingSource?

    @Query(
        """
        SELECT * FROM funding_sources
        WHERE sourceAppHint IS :sourceAppHint
          AND last4 IS :last4
        LIMIT 1
        """,
    )
    suspend fun findByKey(sourceAppHint: String?, last4: String?): FundingSource?

    /**
     * Default Cash source resolution. Lowest-id CASH row wins.
     * @return null only if the seeded CASH row is absent (migration not yet run or seed failed).
     */
    @Query("SELECT * FROM funding_sources WHERE kind = 'CASH' ORDER BY id ASC LIMIT 1")
    suspend fun getDefaultCash(): FundingSource?

    @Query("SELECT * FROM funding_sources ORDER BY kind ASC, displayName ASC")
    fun observeAll(): Flow<List<FundingSource>>

    @Query("SELECT * FROM funding_sources ORDER BY kind ASC, displayName ASC")
    suspend fun getAll(): List<FundingSource>

    @Query("DELETE FROM funding_sources WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM transactions WHERE fundingSourceId = :id")
    suspend fun txCountFor(id: Long): Int
}
