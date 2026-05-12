package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedCurrencyDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(currency: TrackedCurrency): Long

    @Update
    suspend fun update(currency: TrackedCurrency)

    @Query("SELECT * FROM tracked_currencies WHERE code = :code")
    suspend fun get(code: String): TrackedCurrency?

    @Query("SELECT * FROM tracked_currencies ORDER BY code ASC")
    fun observeAll(): Flow<List<TrackedCurrency>>

    @Query("SELECT * FROM tracked_currencies WHERE isDefaultForSymbol = 1")
    suspend fun getDefaultsForSymbol(): List<TrackedCurrency>

    /**
     * Clears the default flag on every currency that shares `symbol`. Used before
     * setting a new default for the same symbol so the invariant "at most one
     * default per symbol" holds. Call from inside a DB transaction with the
     * subsequent update of the new default row.
     */
    @Query(
        """
        UPDATE tracked_currencies
        SET isDefaultForSymbol = 0
        WHERE displaySymbol = :symbol AND isDefaultForSymbol = 1
        """
    )
    suspend fun clearDefaultForSymbol(symbol: String)

    @Query("DELETE FROM tracked_currencies WHERE code = :code")
    suspend fun delete(code: String)
}
