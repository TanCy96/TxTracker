package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomSourceLabelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CustomSourceLabel)

    @Query("DELETE FROM custom_source_labels WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT * FROM custom_source_labels")
    fun observeAll(): Flow<List<CustomSourceLabel>>
}
