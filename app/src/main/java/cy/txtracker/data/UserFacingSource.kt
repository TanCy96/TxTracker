package cy.txtracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * User-added packages that win cross-source dedupe duplicates against any other source.
 * Built-in user-facing packages (Grab, TnG) live in code, not in this table.
 */
@Entity(tableName = "user_facing_sources")
data class UserFacingSource(
    @PrimaryKey val packageName: String,
    val addedAt: Instant,
)
