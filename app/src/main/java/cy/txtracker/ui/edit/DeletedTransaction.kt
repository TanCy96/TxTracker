package cy.txtracker.ui.edit

import cy.txtracker.data.ReimbursementEntry
import cy.txtracker.data.Transaction

/**
 * In-memory snapshot of a transaction (and its reimbursement children) captured the moment it
 * is deleted via the edit sheet, so the Home screen can offer a 5-second Undo that restores it.
 */
data class DeletedTransaction(
    val transaction: Transaction,
    val reimbursements: List<ReimbursementEntry>,
)
