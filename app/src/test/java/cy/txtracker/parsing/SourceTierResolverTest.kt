package cy.txtracker.parsing

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.MANUAL_SOURCE_APP
import cy.txtracker.data.UserFacingSourceDao
import cy.txtracker.data.UserFacingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class SourceTierResolverTest {

    @Test
    fun grab_is_user_facing() = runTest {
        val resolver = SourceTierResolver(FakeUserFacingDao())
        assertThat(resolver.tierFor(GrabParser.GRAB_PACKAGE))
            .isEqualTo(SourceTier.USER_FACING)
    }

    @Test
    fun tng_is_user_facing() = runTest {
        val resolver = SourceTierResolver(FakeUserFacingDao())
        assertThat(resolver.tierFor(TouchNGoParser.TNG_PACKAGE))
            .isEqualTo(SourceTier.USER_FACING)
    }

    @Test
    fun manual_source_app_is_user_facing() = runTest {
        val resolver = SourceTierResolver(FakeUserFacingDao())
        assertThat(resolver.tierFor(MANUAL_SOURCE_APP))
            .isEqualTo(SourceTier.USER_FACING)
    }

    @Test
    fun random_package_is_card_layer() = runTest {
        val resolver = SourceTierResolver(FakeUserFacingDao())
        assertThat(resolver.tierFor("com.google.android.apps.walletnfcrel"))
            .isEqualTo(SourceTier.CARD_LAYER)
    }

    @Test
    fun user_added_package_is_user_facing() = runTest {
        val resolver = SourceTierResolver(FakeUserFacingDao(setOf("com.example.app")))
        assertThat(resolver.tierFor("com.example.app"))
            .isEqualTo(SourceTier.USER_FACING)
    }

    private class FakeUserFacingDao(
        private val present: Set<String> = emptySet(),
    ) : UserFacingSourceDao {
        override suspend fun exists(pkg: String) = pkg in present
        override suspend fun insert(source: UserFacingSource) = 0L
        override suspend fun delete(pkg: String) {}
        override fun observeAll(): Flow<List<UserFacingSource>> =
            flowOf(present.map { UserFacingSource(it, Instant.fromEpochMilliseconds(0)) })
        override suspend fun getAllOnce(): List<UserFacingSource> =
            present.map { UserFacingSource(it, Instant.fromEpochMilliseconds(0)) }
    }
}
