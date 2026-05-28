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
        ForeignKey(
            entity = FundingSource::class,
            parentColumns = ["id"],
            childColumns = ["fundingSourceId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("occurredAt"),
        Index("categoryId"),
        Index("merchantNormalized"),
        Index(value = ["notificationDedupeKey"], unique = true),
        Index("fundingSourceId"),
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
    /**
     * True when this row was captured in a non-MYR currency outside any active
     * TripWindow for that currency. The Foreign tab hides such rows; they
     * appear in the Home "Currency review" filter until the user opens a trip
     * (which retroactively flips this to false for rows in range).
     */
    val needsCurrencyConfirmation: Boolean = false,
    /**
     * True once the user has renamed the merchant on this row via the Edit sheet.
     * Acts as a "do not clobber" flag for the Settings → Re-parse merchants action,
     * which otherwise re-derives merchantRaw from rawText for every captured row.
     * Default false: fresh captures and migration-upgraded rows are eligible for
     * reparse until the user has intentionally fixed them.
     */
    val merchantUserEdited: Boolean = false,
    /**
     * FK to [FundingSource.id]. Null for: existing rows pre-v10 upgrade until the user runs the
     * Settings "Classify existing transactions" backfill action, and for any notification that
     * the classifier could not link (very rare — the catch-all rule always produces a per-bank
     * "unknown account" source).
     */
    val fundingSourceId: Long? = null,
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
    val keywordPattern: String? = null,
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

/**
 * User-added packages that win cross-source dedupe duplicates against any other source.
 * Built-in user-facing packages (Grab, TnG) live in code, not in this table.
 */
@Entity(tableName = "user_facing_sources")
data class UserFacingSource(
    @PrimaryKey val packageName: String,
    val addedAt: Instant,
)

/**
 * Package names the user has implicitly approved by confirming a Pending transaction from
 * that source. The listener unions this set with [SourcePackages.PERMISSIVE_PACKAGES] to
 * decide what counts as a finance app. Self-grows over time: every Pending row the user
 * verifies adds its package here (insert-or-ignore).
 *
 * Distinct from [UserFacingSource], which represents a *tier* (Tier 1 vs Tier 2 for
 * cross-source dedup priority). A package can be in both, one, or neither.
 */
@Entity(tableName = "approved_sources")
data class ApprovedSource(
    @PrimaryKey val packageName: String,
    val firstApprovedAt: Instant,
)

@Entity(
    tableName = "captured_notifications",
    indices = [
        Index("packageName"),
        Index("disposition"),
        Index("capturedAt"),
        Index(value = ["dedupeKey"], unique = true),
    ],
)
data class CapturedNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val postedAt: Instant,
    val amountMinor: Long,
    val currency: String,
    val rawText: String,
    val rewrittenText: String?,
    val disposition: CaptureDisposition,
    val promotedToTxId: Long?,
    val capturedAt: Instant,
    /**
     * SHA-1 hash of `(packageName, amountMinor, currency, rawText, postedAt)`. Re-fires of
     * the same Android notification — common when the system re-posts after a ranking
     * change or content update — produce the same hash, so `OnConflictStrategy.IGNORE`
     * drops the duplicate row at insert time.
     */
    val dedupeKey: String,
)

enum class CaptureDisposition { PENDING, PROMOTED, NOISE }

@Entity(tableName = "rejected_sources")
data class RejectedSource(
    @PrimaryKey val packageName: String,
    val rejectedAt: Instant,
)

@Entity(
    tableName = "tracked_currencies",
)
data class TrackedCurrency(
    @PrimaryKey val code: String,
    val displaySymbol: String,
    /**
     * True when this row is the user's chosen interpretation of an ambiguous
     * symbol (`$`, `¥`). At most one row may have isDefaultForSymbol = true per
     * symbol value — enforced by the repository, not the schema.
     */
    val isDefaultForSymbol: Boolean,
    val addedAt: Instant,
)

/**
 * Per-package raw-text rewrite rule. Applied to the incoming notification text BEFORE
 * the heuristic / permissive extractors run, so app-specific noise (Wise's
 * "Tap to see this transaction" CTA, banks' boilerplate footers, etc.) can be stripped
 * without hardcoding regex into the extractor.
 *
 * - [pattern] is a Java regex compiled with the IGNORE_CASE flag. Invalid patterns are
 *   skipped at apply-time rather than blocking ingest.
 * - [replacement] is the literal text spliced in for each match. The empty string (the
 *   common case — "strip this junk") effectively deletes matches.
 *
 * The composite primary key lets one package own many rules; ordering across rules is
 * not guaranteed (currently learnedAt-ASC; deliberately not exposed in UI yet).
 */
@Entity(
    tableName = "package_text_rewrites",
    primaryKeys = ["packageName", "pattern"],
    indices = [Index("packageName")],
)
data class PackageTextRewrite(
    val packageName: String,
    val pattern: String,
    val replacement: String,
    val learnedAt: Instant,
)

@Entity(
    tableName = "trip_windows",
    indices = [
        Index("currency"),
        Index("startAt"),
        Index("endAt"),
    ],
)
data class TripWindow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val currency: String,
    val startAt: Instant,
    /** null = open-ended ("until the user ends it"). */
    val endAt: Instant?,
    val createdAt: Instant,
)
