package cy.txtracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant

const val MANUAL_SOURCE_APP = "manual"

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("occurredAt"),
        Index("categoryId"),
        Index("merchantNormalized"),
        Index(value = ["notificationDedupeKey"], unique = true),
    ],
)
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Amount in minor units (cents), to avoid floating-point arithmetic. RM 12.50 → 1250. */
    val amountMinor: Long,
    val currency: String,
    /** Merchant string as it appeared in the notification. */
    val merchantRaw: String,
    /** Uppercased + trimmed merchant string used for mapping lookups. */
    val merchantNormalized: String,
    /** null = uncategorized ("Unverified"). */
    val categoryId: Long?,
    /** Free-text description like "lunch", "petrol", "movie". null = blank. */
    val description: String?,
    val occurredAt: Instant,
    /** Denormalized cache of the time-of-day bucket for fast queries. */
    val timeBucket: TimeBucket,
    /** Source app package name, or [MANUAL_SOURCE_APP]. */
    val sourceApp: String,
    /** Full notification text, kept for debugging. null for manual entries. */
    val rawText: String?,
    val direction: Direction,
    val createdAt: Instant,
    /** Stable hash used to dedupe duplicate notifications (pending + posted, etc.). */
    val notificationDedupeKey: String,
    /**
     * True when this row was captured by the generic heuristic extractor rather than a
     * strict per-source parser. Surfaces in the home screen "Pending" filter and the edit
     * sheet so the user can confirm or delete it. Strict-parser captures and manual entries
     * default to false.
     */
    val needsVerification: Boolean = false,
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB color int used for category chips/swatches. */
    val color: Int,
    /** True if user-created; false for the default seed list. */
    val isCustom: Boolean,
    /** Smaller values render first in the home screen and CSV export. */
    val sortOrder: Int,
)

@Entity(
    tableName = "merchant_mappings",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId")],
)
data class MerchantMapping(
    @PrimaryKey val merchantNormalized: String,
    val categoryId: Long,
    val learnedAt: Instant,
)

@Entity(
    tableName = "merchant_description_mappings",
    primaryKeys = ["merchantNormalized", "timeBucket"],
)
data class MerchantDescriptionMapping(
    val merchantNormalized: String,
    val timeBucket: TimeBucket,
    val description: String,
    val learnedAt: Instant,
)

@Entity(
    tableName = "merchant_notes",
)
data class MerchantNote(
    @PrimaryKey val merchantNormalized: String,
    /** Free-text user note about this merchant. e.g., "TnG P2P, SS15 warung uncle". */
    val note: String,
    val updatedAt: Instant,
)

@Entity(
    tableName = "category_description_mappings",
    primaryKeys = ["categoryId", "timeBucket"],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CategoryDescriptionMapping(
    val categoryId: Long,
    val timeBucket: TimeBucket,
    val description: String,
    val learnedAt: Instant,
)

/** Result row for category-total grouping queries. */
data class CategoryTotal(
    @ColumnInfo(name = "categoryId") val categoryId: Long?,
    @ColumnInfo(name = "totalMinor") val totalMinor: Long,
)
