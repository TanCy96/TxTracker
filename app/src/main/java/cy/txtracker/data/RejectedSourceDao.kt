package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RejectedSourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: RejectedSource)

    @Query("SELECT * FROM rejected_sources ORDER BY rejectedAt ASC")
    fun observeAll(): Flow<List<RejectedSource>>

    @Query("SELECT packageName FROM rejected_sources")
    fun observeAllPackageNames(): Flow<List<String>>

    @Query("SELECT packageName FROM rejected_sources")
    suspend fun getAllPackageNamesOnce(): List<String>

    @Query("DELETE FROM rejected_sources WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
