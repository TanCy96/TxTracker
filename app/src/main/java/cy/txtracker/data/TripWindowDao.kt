package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

@Dao
interface TripWindowDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(window: TripWindow): Long

    @Query("SELECT * FROM trip_windows WHERE id = :id")
    suspend fun get(id: Long): TripWindow?

    @Query("SELECT * FROM trip_windows ORDER BY startAt DESC")
    fun observeAll(): Flow<List<TripWindow>>

    @Query(
        """
        SELECT * FROM trip_windows
        WHERE currency = :currency
        ORDER BY startAt DESC
        """
    )
    fun observeForCurrency(currency: String): Flow<List<TripWindow>>

    /**
     * Returns the first trip whose half-open range covers [at]. NULL endAt is
     * treated as "open-ended" — covers anything >= startAt. Used by the ingest
     * path's `needsCurrencyConfirmation` decision and by `setCurrency` in the
     * edit-sheet flow.
     */
    @Query(
        """
        SELECT * FROM trip_windows
        WHERE currency = :currency
          AND startAt <= :at
          AND (endAt IS NULL OR endAt > :at)
        ORDER BY startAt DESC
        LIMIT 1
        """
    )
    suspend fun findActiveAt(currency: String, at: Instant): TripWindow?

    @Query("UPDATE trip_windows SET endAt = :endAt WHERE id = :id")
    suspend fun setEnd(id: Long, endAt: Instant?)

    @Query("DELETE FROM trip_windows WHERE id = :id")
    suspend fun delete(id: Long)
}
