package cy.txtracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * Singleton config row for the SL Debit prepaid pool. Always id = 1 (the migration seeds it).
 * The pool's running balance is derived (SUM of deposits minus SUM of transaction shares),
 * never stored here.
 */
@Entity(tableName = "sl_debit_account")
data class SlDebitAccount(
    @PrimaryKey val id: Long = 1,
    /** Renameable display name. Default "SL Debit". */
    val displayName: String,
    /** 0..100. Prefills the per-transaction share field. Default 40. */
    val defaultSharePercent: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A manual top-up into the SL Debit pool. */
@Entity(tableName = "sl_debit_deposit", indices = [Index("occurredAt")])
data class SlDebitDeposit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Positive top-up amount in minor units. */
    val amountMinor: Long,
    val occurredAt: Instant,
    val note: String? = null,
    val createdAt: Instant,
)
