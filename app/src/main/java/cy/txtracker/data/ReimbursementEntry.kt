package cy.txtracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * One person's reimbursement of part of a [Transaction]. A transaction may have several.
 * The portion others returned is netted out of *your* spend; the cached aggregate lives on
 * [Transaction.reimbursedMinor] (kept in step by the repository) so existing net-spend SQL
 * is untouched. These rows add the per-person / per-destination detail consumed by the edit
 * sheet and the CSV funding columns.
 *
 * [destinationKind] is the funding bucket the money landed in — it selects which CSV funding
 * column receives the negative term. [personLabel] is in-app only (never exported to CSV).
 * Deleting the parent transaction cascades these away.
 */
@Entity(
    tableName = "reimbursement_entries",
    foreignKeys = [
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transactionId")],
)
data class ReimbursementEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    /** Portion this person returned, in minor units. Always > 0. */
    val amountMinor: Long,
    /** Funding bucket the money landed in; selects the CSV funding column for the negative. */
    val destinationKind: FundingSourceKind,
    /** Optional free-text label for who reimbursed. In-app only; never emitted to CSV. */
    val personLabel: String? = null,
    /** Stable ordering for the edit sheet and CSV term order. */
    val createdAt: Instant,
)
