package cy.txtracker.data

/** One row of the built-in travel template applied to every new trip. */
data class TripSeedCategory(val name: String, val color: Int)

/**
 * The travel category template seeded for each trip (on trip creation and, for
 * pre-existing trips, in migration 15->16). Distinct from Home's everyday
 * [DefaultCategories]; carries NO keyword patterns — trip categories are manual-only.
 */
object DefaultTripCategories {
    val template: List<TripSeedCategory> = listOf(
        TripSeedCategory("Accommodation", 0xFF5C6BC0.toInt()),
        TripSeedCategory("Food & Drink", 0xFFEF5350.toInt()),
        TripSeedCategory("Transport", 0xFF42A5F5.toInt()),
        TripSeedCategory("Attractions", 0xFFFFCA28.toInt()),
        TripSeedCategory("Shopping", 0xFFAB47BC.toInt()),
        TripSeedCategory("Groceries", 0xFF66BB6A.toInt()),
        TripSeedCategory("Fees & Cash", 0xFF8D6E63.toInt()),
        TripSeedCategory("Other", 0xFF78909C.toInt()),
    )
}
