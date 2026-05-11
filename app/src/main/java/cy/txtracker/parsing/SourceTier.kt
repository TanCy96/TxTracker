package cy.txtracker.parsing

import cy.txtracker.data.MANUAL_SOURCE_APP
import cy.txtracker.data.UserFacingSourceDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-tier priority used by cross-source dedupe. A `USER_FACING` row wins over a
 * `CARD_LAYER` row when both describe the same payment in the same 5-min bucket,
 * because the user-facing app names the actual context (e.g., "GRAB") while the
 * card-layer view is a generic pass-through identifier (e.g., "GRAB RIDES-EC").
 *
 * Tier 1 (USER_FACING) seed list lives here in code; user additions live in
 * `user_facing_sources`. Everything else is implicitly Tier 2 (CARD_LAYER).
 *
 * Manual entries (`sourceApp == MANUAL_SOURCE_APP`) are treated as USER_FACING so
 * they're never silently overwritten by an auto-detected match.
 */
enum class SourceTier { USER_FACING, CARD_LAYER }

@Singleton
class SourceTierResolver @Inject constructor(
    private val userFacingSourceDao: UserFacingSourceDao,
) {
    suspend fun tierFor(packageName: String): SourceTier =
        if (packageName == MANUAL_SOURCE_APP ||
            packageName in BUILTIN_USER_FACING_PACKAGES ||
            userFacingSourceDao.exists(packageName)
        ) SourceTier.USER_FACING
        else SourceTier.CARD_LAYER

    companion object {
        val BUILTIN_USER_FACING_PACKAGES: Set<String> = setOf(
            GrabParser.GRAB_PACKAGE,
            TouchNGoParser.TNG_PACKAGE,
        )
    }
}
