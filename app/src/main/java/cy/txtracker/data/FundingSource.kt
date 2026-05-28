package cy.txtracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * The bucket every FundingSource belongs to. Used as the read-side grouping in the Home
 * filter and the CSV `Source` column. Kind is mutable on a given FundingSource — when the
 * user corrects an auto-classifier guess in Settings, this is the field that changes.
 */
enum class FundingSourceKind { CREDIT_CARD, E_WALLET, DEBIT_BANK, CASH }

/**
 * A specific account/card/wallet identity that funds transactions. Multiple transactions can
 * point at the same FundingSource via [Transaction.fundingSourceId]. The classifier looks up
 * existing rows by the `(sourceAppHint, last4)` unique key — see [FundingSourceDao.findByKey].
 *
 * Invariant: the classifier only ever inserts new rows. Updates to existing rows come from
 * user actions in Settings or the edit sheet. This is what makes a user-set [kind] sticky
 * against future notification ingest.
 */
@Entity(
    tableName = "funding_sources",
    indices = [
        Index(value = ["sourceAppHint", "last4"], unique = true),
    ],
)
data class FundingSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: FundingSourceKind,
    /** Display name shown in Settings, edit-sheet picker, and the Add Manual default. */
    val displayName: String,
    /** Card / account last-4 digits. Null for wallets and the seeded Cash source. */
    val last4: String?,
    /** Package name where this source was first observed. Null for the seeded Cash source. */
    val sourceAppHint: String?,
    /** False until the user has renamed; UI marks auto-named entries with an "auto" tag. */
    val isUserNamed: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
)
