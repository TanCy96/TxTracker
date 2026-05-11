package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserFacingSourceDao {

    @Query("SELECT EXISTS(SELECT 1 FROM user_facing_sources WHERE packageName = :pkg)")
    suspend fun exists(pkg: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: UserFacingSource): Long

    @Query("DELETE FROM user_facing_sources WHERE packageName = :pkg")
    suspend fun delete(pkg: String)

    @Query("SELECT * FROM user_facing_sources ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<UserFacingSource>>

    @Query("SELECT * FROM user_facing_sources ORDER BY addedAt ASC")
    suspend fun getAllOnce(): List<UserFacingSource>
}
