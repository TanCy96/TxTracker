package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ApprovedSourceDao {

    @Query("SELECT EXISTS(SELECT 1 FROM approved_sources WHERE packageName = :pkg)")
    suspend fun exists(pkg: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: ApprovedSource): Long

    @Query("SELECT * FROM approved_sources ORDER BY firstApprovedAt ASC")
    fun observeAll(): Flow<List<ApprovedSource>>

    @Query("SELECT * FROM approved_sources ORDER BY firstApprovedAt ASC")
    suspend fun getAllOnce(): List<ApprovedSource>

    @Query("SELECT packageName FROM approved_sources")
    fun observeAllPackageNames(): Flow<List<String>>

    @Query("DELETE FROM approved_sources WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}
