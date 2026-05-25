package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PackageTextRewriteDao {

    /** Upsert by composite PK (packageName, pattern). Replaces replacement + learnedAt. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rewrite: PackageTextRewrite)

    @Query("DELETE FROM package_text_rewrites WHERE packageName = :packageName AND pattern = :pattern")
    suspend fun delete(packageName: String, pattern: String)

    @Query("SELECT * FROM package_text_rewrites ORDER BY packageName ASC, learnedAt ASC")
    fun observeAll(): Flow<List<PackageTextRewrite>>

    /** Reactive feed used by NotificationRewriteEngine to keep its in-memory map current. */
    @Query("SELECT * FROM package_text_rewrites ORDER BY packageName ASC, learnedAt ASC")
    suspend fun getAll(): List<PackageTextRewrite>

    @Query("SELECT * FROM package_text_rewrites WHERE packageName = :packageName ORDER BY learnedAt ASC")
    suspend fun getForPackage(packageName: String): List<PackageTextRewrite>
}
