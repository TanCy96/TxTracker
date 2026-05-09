package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cy.txtracker.domain.TimeBucket
import kotlinx.coroutines.flow.Flow

@Dao
interface DescriptionMappingDao {

    // Merchant + time-bucket level

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMerchant(mapping: MerchantDescriptionMapping)

    @Query(
        """
        SELECT * FROM merchant_description_mappings
        WHERE merchantNormalized = :merchant AND timeBucket = :bucket
        """
    )
    suspend fun getMerchantBucket(
        merchant: String,
        bucket: TimeBucket,
    ): MerchantDescriptionMapping?

    /** Returns the most recently learned description for this merchant in any time bucket. */
    @Query(
        """
        SELECT * FROM merchant_description_mappings
        WHERE merchantNormalized = :merchant
        ORDER BY learnedAt DESC
        LIMIT 1
        """
    )
    suspend fun getMerchantAny(merchant: String): MerchantDescriptionMapping?

    @Query(
        """
        DELETE FROM merchant_description_mappings
        WHERE merchantNormalized = :merchant AND timeBucket = :bucket
        """
    )
    suspend fun deleteMerchantBucket(merchant: String, bucket: TimeBucket)

    @Query("SELECT * FROM merchant_description_mappings ORDER BY learnedAt DESC")
    fun observeAllMerchant(): Flow<List<MerchantDescriptionMapping>>

    // Category + time-bucket level

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(mapping: CategoryDescriptionMapping)

    @Query(
        """
        SELECT * FROM category_description_mappings
        WHERE categoryId = :categoryId AND timeBucket = :bucket
        """
    )
    suspend fun getCategoryBucket(
        categoryId: Long,
        bucket: TimeBucket,
    ): CategoryDescriptionMapping?

    @Query(
        """
        DELETE FROM category_description_mappings
        WHERE categoryId = :categoryId AND timeBucket = :bucket
        """
    )
    suspend fun deleteCategoryBucket(categoryId: Long, bucket: TimeBucket)

    @Query("SELECT * FROM category_description_mappings ORDER BY learnedAt DESC")
    fun observeAllCategory(): Flow<List<CategoryDescriptionMapping>>
}
