package cy.txtracker.data

/** Money flow direction. v1 only persists OUT; reserved for future income/refund support. */
enum class Direction {
    OUT,
    IN,
}
