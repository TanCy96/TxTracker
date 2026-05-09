package cy.txtracker.domain

/**
 * Coarse time-of-day buckets used by the description-learning system.
 * The actual `bucketOf(Instant)` mapping is added in a later task; this file
 * only declares the enum so the data layer can store it as a column.
 */
enum class TimeBucket {
    MORNING,
    MIDDAY,
    AFTERNOON,
    EVENING,
    LATE_NIGHT,
}
