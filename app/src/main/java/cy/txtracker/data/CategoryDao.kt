package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: Category): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<Category>): List<Long>

    @Update
    suspend fun update(category: Category)

    /**
     * Bulk update — used by the categories reorder action to renumber every row's sortOrder
     * in a single Room transaction.
     */
    @Update
    suspend fun updateAll(categories: List<Category>)

    @Delete
    suspend fun delete(category: Category)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): Category?

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    suspend fun getAll(): List<Category>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT * FROM categories WHERE tripId IS NULL ORDER BY sortOrder ASC, name ASC")
    fun observeGlobal(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE tripId IS NULL ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllGlobal(): List<Category>

    @Query("SELECT * FROM categories WHERE tripId = :tripId ORDER BY sortOrder ASC, name ASC")
    fun observeForTrip(tripId: Long): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE tripId = :tripId ORDER BY sortOrder ASC, name ASC")
    suspend fun getForTrip(tripId: Long): List<Category>

    @Query("DELETE FROM categories WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: Long)
}
