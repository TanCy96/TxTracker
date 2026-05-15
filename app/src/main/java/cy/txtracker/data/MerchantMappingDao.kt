package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantMappingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: MerchantMapping)

    @Query("SELECT * FROM merchant_mappings WHERE merchantNormalized = :merchant")
    suspend fun get(merchant: String): MerchantMapping?

    @Query("DELETE FROM merchant_mappings WHERE merchantNormalized = :merchant")
    suspend fun delete(merchant: String)

    @Query("SELECT * FROM merchant_mappings ORDER BY learnedAt DESC")
    fun observeAll(): Flow<List<MerchantMapping>>

    @Query("SELECT * FROM merchant_mappings ORDER BY learnedAt DESC")
    suspend fun getAllOrderedByRecency(): List<MerchantMapping>

    @Query("SELECT COUNT(*) FROM merchant_mappings WHERE categoryId = :categoryId")
    suspend fun countForCategory(categoryId: Long): Int
}
