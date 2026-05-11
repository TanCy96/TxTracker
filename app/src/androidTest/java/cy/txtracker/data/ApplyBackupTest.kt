package cy.txtracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.TimeBucket
import cy.txtracker.export.Backup
import cy.txtracker.export.BackupCategory
import cy.txtracker.export.BackupCategoryDescriptionMapping
import cy.txtracker.export.BackupMerchantDescriptionMapping
import cy.txtracker.export.BackupMerchantMapping
import cy.txtracker.export.BackupApprovedSource
import cy.txtracker.export.BackupUserFacingSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplyBackupTest {

    @get:Rule val dbRule = DbRule()

    private fun repo() = TransactionRepository(
        database = dbRule.db,
        transactionDao = dbRule.transactionDao,
        categoryDao = dbRule.categoryDao,
        merchantMappingDao = dbRule.merchantMappingDao,
        descriptionMappingDao = dbRule.descriptionMappingDao,
        merchantNoteDao = dbRule.merchantNoteDao,
        userFacingSourceDao = dbRule.userFacingSourceDao,
        approvedSourceDao = dbRule.approvedSourceDao,
    )

    private val now = Instant.parse("2026-05-09T12:30:00Z")
    private val later = Instant.parse("2026-05-09T13:00:00Z")
    private val earlier = Instant.parse("2026-04-01T00:00:00Z")

    private fun backup(
        categories: List<BackupCategory> = emptyList(),
        merchant: List<BackupMerchantMapping> = emptyList(),
        merchantDesc: List<BackupMerchantDescriptionMapping> = emptyList(),
        categoryDesc: List<BackupCategoryDescriptionMapping> = emptyList(),
    ) = Backup(
        version = 1,
        exportedAt = now,
        categories = categories,
        merchantMappings = merchant,
        merchantDescriptionMappings = merchantDesc,
        categoryDescriptionMappings = categoryDesc,
    )

    @Test
    fun missing_categories_are_created_existing_ones_left_alone() = runTest {
        // The default seed includes "Food" (color = 0xFFEF5350). Backup says Food has a
        // different color — existing local Food must NOT be overwritten.
        val customColor = 0xFF000000.toInt()
        val result = repo().applyBackup(
            backup(
                categories = listOf(
                    BackupCategory("Food", color = customColor, sortOrder = 99, isCustom = false),
                    BackupCategory("Pets", color = 0xFFAB47BC.toInt(), sortOrder = 5, isCustom = true),
                ),
            ),
        )

        val all = dbRule.categoryDao.getAll().associateBy { it.name }
        assertThat(all["Food"]?.color).isNotEqualTo(customColor)  // local Food untouched
        assertThat(all["Pets"]).isNotNull()
        assertThat(all["Pets"]?.isCustom).isTrue()
        assertThat(result.categoriesCreated).isEqualTo(1)
    }

    @Test
    fun newer_merchant_mapping_in_backup_overwrites_older_local() = runTest {
        val r = repo()
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val transport = dbRule.categoryDao.getAll().first { it.name == "Transport" }

        // Local: STARBUCKS -> Transport, learned earlier.
        dbRule.merchantMappingDao.upsert(MerchantMapping("STARBUCKS", transport.id, earlier))

        // Backup: STARBUCKS -> Food, learned later.
        val result = r.applyBackup(
            backup(merchant = listOf(BackupMerchantMapping("STARBUCKS", "Food", later))),
        )

        val mapping = dbRule.merchantMappingDao.get("STARBUCKS")!!
        assertThat(mapping.categoryId).isEqualTo(food.id)
        assertThat(mapping.learnedAt).isEqualTo(later)
        assertThat(result.merchantMappingsUpdated).isEqualTo(1)
    }

    @Test
    fun older_merchant_mapping_in_backup_does_not_clobber_newer_local() = runTest {
        val r = repo()
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val transport = dbRule.categoryDao.getAll().first { it.name == "Transport" }

        // Local: STARBUCKS -> Transport, learned later (recent local activity).
        dbRule.merchantMappingDao.upsert(MerchantMapping("STARBUCKS", transport.id, later))

        // Backup is older.
        val result = r.applyBackup(
            backup(merchant = listOf(BackupMerchantMapping("STARBUCKS", "Food", earlier))),
        )

        val mapping = dbRule.merchantMappingDao.get("STARBUCKS")!!
        assertThat(mapping.categoryId).isEqualTo(transport.id)  // local kept
        assertThat(mapping.learnedAt).isEqualTo(later)
        assertThat(result.merchantMappingsUpdated).isEqualTo(0)
    }

    @Test
    fun mappings_referencing_missing_category_are_skipped_and_counted() = runTest {
        val r = repo()
        // Backup mentions "NonExistentCat" without including a BackupCategory for it.
        val result = r.applyBackup(
            backup(
                merchant = listOf(BackupMerchantMapping("FOO", "NonExistentCat", now)),
                categoryDesc = listOf(
                    BackupCategoryDescriptionMapping("AlsoMissing", TimeBucket.MIDDAY, "lunch", now),
                ),
            ),
        )

        assertThat(dbRule.merchantMappingDao.get("FOO")).isNull()
        assertThat(result.merchantMappingsAdded).isEqualTo(0)
        assertThat(result.skippedDueToMissingCategory).isEqualTo(2)
    }

    @Test
    fun newer_merchant_description_replaces_older() = runTest {
        val r = repo()
        dbRule.descriptionMappingDao.upsertMerchant(
            MerchantDescriptionMapping("STARBUCKS", TimeBucket.MIDDAY, "old coffee", earlier),
        )

        r.applyBackup(
            backup(
                merchantDesc = listOf(
                    BackupMerchantDescriptionMapping(
                        "STARBUCKS", TimeBucket.MIDDAY, "lunch coffee", later,
                    ),
                ),
            ),
        )

        val mapping = dbRule.descriptionMappingDao.getMerchantBucket(
            "STARBUCKS", TimeBucket.MIDDAY,
        )
        assertThat(mapping?.description).isEqualTo("lunch coffee")
    }

    @Test
    fun newer_category_description_translates_categoryName_to_local_id() = runTest {
        val r = repo()
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }

        r.applyBackup(
            backup(
                categoryDesc = listOf(
                    BackupCategoryDescriptionMapping("Food", TimeBucket.MIDDAY, "lunch", later),
                ),
            ),
        )

        val mapping = dbRule.descriptionMappingDao.getCategoryBucket(food.id, TimeBucket.MIDDAY)
        assertThat(mapping?.description).isEqualTo("lunch")
    }

    @Test
    fun mapping_to_a_backup_only_category_works_after_category_is_created() = runTest {
        // Backup creates "Pets" AND a merchant mapping to "Pets" — both happen in the
        // same applyBackup call, so the mapping resolves correctly to the new category.
        val r = repo()
        r.applyBackup(
            backup(
                categories = listOf(
                    BackupCategory("Pets", color = 0xFFAB47BC.toInt(), sortOrder = 99, isCustom = true),
                ),
                merchant = listOf(BackupMerchantMapping("PETSHOP", "Pets", now)),
            ),
        )

        val pets = dbRule.categoryDao.getAll().first { it.name == "Pets" }
        val mapping = dbRule.merchantMappingDao.get("PETSHOP")!!
        assertThat(mapping.categoryId).isEqualTo(pets.id)
    }

    @Test
    fun applyBackup_inserts_user_facing_sources() = runTest {
        val repo = repo()
        val backup = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            userFacingSources = listOf(
                BackupUserFacingSource("com.example.app",
                    Instant.parse("2026-05-10T10:00:00Z")),
            ),
        )

        repo.applyBackup(backup)

        val sources = dbRule.userFacingSourceDao.getAllOnce()
        assertThat(sources.map { it.packageName }).containsExactly("com.example.app")
    }

    @Test
    fun applyBackup_does_not_overwrite_existing_user_facing_source_addedAt() = runTest {
        val repo = repo()
        val localAddedAt = Instant.parse("2026-05-11T10:00:00Z")
        dbRule.userFacingSourceDao.insert(UserFacingSource("com.example.app", localAddedAt))

        val backup = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            userFacingSources = listOf(
                BackupUserFacingSource("com.example.app",
                    Instant.parse("2026-04-01T10:00:00Z")),
            ),
        )

        repo.applyBackup(backup)

        val source = dbRule.userFacingSourceDao.getAllOnce().single()
        assertThat(source.addedAt).isEqualTo(localAddedAt)
    }

    @Test
    fun applyBackup_inserts_approved_sources() = runTest {
        val repo = repo()
        val backup = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            approvedSources = listOf(
                BackupApprovedSource("com.cimb.cimbocto",
                    Instant.parse("2026-05-10T10:00:00Z")),
            ),
        )

        repo.applyBackup(backup)

        val sources = dbRule.approvedSourceDao.getAllOnce()
        assertThat(sources.map { it.packageName }).containsExactly("com.cimb.cimbocto")
    }

    @Test
    fun applyBackup_does_not_overwrite_existing_approved_source_firstApprovedAt() = runTest {
        val repo = repo()
        val localFirstApprovedAt = Instant.parse("2026-05-11T10:00:00Z")
        dbRule.approvedSourceDao.insert(ApprovedSource("com.cimb.cimbocto", localFirstApprovedAt))

        val backup = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            approvedSources = listOf(
                BackupApprovedSource("com.cimb.cimbocto",
                    Instant.parse("2026-04-01T10:00:00Z")),
            ),
        )

        repo.applyBackup(backup)

        val source = dbRule.approvedSourceDao.getAllOnce().single()
        assertThat(source.firstApprovedAt).isEqualTo(localFirstApprovedAt)
    }
}
