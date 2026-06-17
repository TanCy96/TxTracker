package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoPromoteSourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: AutoPromoteSource)

    @Query("DELETE FROM auto_promote_sources WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT EXISTS(SELECT 1 FROM auto_promote_sources WHERE packageName = :packageName)")
    suspend fun isAutoPromote(packageName: String): Boolean

    @Query("SELECT packageName FROM auto_promote_sources")
    fun observeAllPackageNames(): Flow<List<String>>
}
