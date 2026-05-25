package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

@Dao
interface CapturedNotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: CapturedNotification): Long

    @Query("SELECT * FROM captured_notifications WHERE id = :id")
    suspend fun get(id: Long): CapturedNotification?

    @Query("SELECT * FROM captured_notifications ORDER BY postedAt DESC")
    fun observeAll(): Flow<List<CapturedNotification>>

    @Query("SELECT * FROM captured_notifications WHERE packageName = :packageName ORDER BY postedAt DESC")
    fun observeForPackage(packageName: String): Flow<List<CapturedNotification>>

    @Query(
        """
        SELECT COUNT(*) FROM captured_notifications
        WHERE disposition = 'PENDING'
          AND packageName NOT IN (SELECT packageName FROM rejected_sources)
        """
    )
    fun observeVisiblePendingCount(): Flow<Int>

    @Query("UPDATE captured_notifications SET disposition = 'NOISE' WHERE id = :id")
    suspend fun markNoise(id: Long)

    @Query(
        """
        UPDATE captured_notifications
        SET disposition = 'PROMOTED', promotedToTxId = :txId
        WHERE id = :id
        """
    )
    suspend fun markPromoted(id: Long, txId: Long)

    @Query(
        """
        UPDATE captured_notifications
        SET disposition = 'NOISE'
        WHERE packageName = :packageName AND disposition = 'PENDING'
        """
    )
    suspend fun markPendingNoiseForPackage(packageName: String)

    @Query(
        """
        DELETE FROM captured_notifications
        WHERE disposition IN ('PENDING', 'NOISE')
          AND packageName IN (SELECT packageName FROM rejected_sources)
          AND capturedAt < :cutoff
        """
    )
    suspend fun deleteRejectedBefore(cutoff: Instant): Int

    @Query(
        """
        SELECT packageName, COUNT(*) AS entryCount, MAX(capturedAt) AS lastCapturedAt
        FROM captured_notifications
        WHERE capturedAt >= :since
        GROUP BY packageName
        """
    )
    fun observePackageStatsSince(since: Instant): Flow<List<PoolPackageStats>>
}

data class PoolPackageStats(
    val packageName: String,
    val entryCount: Int,
    val lastCapturedAt: Instant,
)
