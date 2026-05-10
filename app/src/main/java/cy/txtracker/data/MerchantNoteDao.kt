package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: MerchantNote)

    @Query("SELECT * FROM merchant_notes WHERE merchantNormalized = :merchant")
    suspend fun get(merchant: String): MerchantNote?

    @Query("DELETE FROM merchant_notes WHERE merchantNormalized = :merchant")
    suspend fun delete(merchant: String)

    @Query("SELECT * FROM merchant_notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MerchantNote>>
}
