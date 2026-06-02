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
import cy.txtracker.export.BackupMerchantNote
import cy.txtracker.export.BackupTrackedCurrency
import cy.txtracker.export.BackupReimbursementEntry
import cy.txtracker.export.BackupTransaction
import cy.txtracker.export.BackupTripWindow
import cy.txtracker.export.BackupUserFacingSource
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.first
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
        trackedCurrencyDao = dbRule.trackedCurrencyDao,
        tripWindowDao = dbRule.tripWindowDao,
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
    fun backup_category_set_replaces_local_set_authoritatively() = runTest {
        // The backup is authoritative: it carries only "Food" (with a custom color) and a
        // new "Pets". After import the local set must be EXACTLY those two — every seed
        // default the backup omits is removed, and the surviving "Food" adopts the backup's
        // color (the backup wins, in place).
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
        assertThat(all.keys).containsExactly("Food", "Pets")  // seed defaults pruned
        assertThat(all["Food"]?.color).isEqualTo(customColor)  // backup wins, updated in place
        assertThat(all["Pets"]?.isCustom).isTrue()
        assertThat(result.categoriesCreated).isEqualTo(1)  // Pets inserted; Food updated
    }

    @Test
    fun applyBackup_removes_seed_defaults_the_backup_omits() = runTest {
        // Reproduces the restore-onto-fresh-install bug: the source device deleted "Food"
        // and renamed "Transport" -> "Commute", so its backup carries neither "Food" nor
        // "Transport". A fresh install re-seeds all 10 defaults; after restore, the resurrected
        // defaults the user had customized away must be gone.
        val result = repo().applyBackup(
            backup(
                categories = listOf(
                    BackupCategory("Groceries", color = 0xFF66BB6A.toInt(), sortOrder = 1, isCustom = false),
                    BackupCategory("Commute", color = 0xFF42A5F5.toInt(), sortOrder = 2, isCustom = false),
                    BackupCategory("Other", color = 0xFF78909C.toInt(), sortOrder = 9, isCustom = false),
                ),
            ),
        )

        val names = dbRule.categoryDao.getAll().map { it.name }.toSet()
        assertThat(names).containsExactly("Groceries", "Commute", "Other")
        assertThat(names).doesNotContain("Food")       // deleted on source device
        assertThat(names).doesNotContain("Transport")  // renamed to "Commute"
        assertThat(result.categoriesCreated).isEqualTo(1)  // only "Commute" is new
    }

    @Test
    fun applyBackup_with_empty_category_list_leaves_local_categories_untouched() = runTest {
        // A backup with NO categories is a partial/degenerate payload, not a "delete all"
        // instruction — the local (seeded) set must survive intact.
        val before = dbRule.categoryDao.getAll().map { it.name }.toSet()
        repo().applyBackup(backup(categories = emptyList()))
        val after = dbRule.categoryDao.getAll().map { it.name }.toSet()
        assertThat(after).isEqualTo(before)
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

    @Test
    fun applyBackup_inserts_merchant_notes() = runTest {
        val repo = repo()
        val backup = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            merchantNotes = listOf(
                BackupMerchantNote(
                    merchant = "WARUNG UNCLE",
                    note = "SS15 kopitiam",
                    updatedAt = Instant.parse("2026-05-10T10:00:00Z"),
                ),
            ),
        )

        repo.applyBackup(backup)

        val notes = repo.observeMerchantNotes().first()
        assertThat(notes).hasSize(1)
        val note = notes.single()
        assertThat(note.merchantNormalized).isEqualTo("WARUNG UNCLE")
        assertThat(note.note).isEqualTo("SS15 kopitiam")
    }

    @Test
    fun applyBackup_overwrites_merchant_note_when_backup_is_newer() = runTest {
        val repo = repo()
        // Local: older note
        repo.setMerchantNote(
            merchantNormalized = "WARUNG UNCLE",
            note = "old note",
            now = Instant.parse("2026-05-01T10:00:00Z"),
        )

        val backup = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            merchantNotes = listOf(
                BackupMerchantNote(
                    merchant = "WARUNG UNCLE",
                    note = "new note from another device",
                    updatedAt = Instant.parse("2026-05-10T10:00:00Z"),
                ),
            ),
        )

        repo.applyBackup(backup)

        val note = repo.observeMerchantNotes().first().single()
        assertThat(note.note).isEqualTo("new note from another device")
    }

    @Test
    fun applyBackup_does_not_overwrite_merchant_note_when_local_is_newer() = runTest {
        val repo = repo()
        // Local: newer note
        repo.setMerchantNote(
            merchantNormalized = "WARUNG UNCLE",
            note = "local newer note",
            now = Instant.parse("2026-05-15T10:00:00Z"),
        )

        val backup = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            merchantNotes = listOf(
                BackupMerchantNote(
                    merchant = "WARUNG UNCLE",
                    note = "older note from backup",
                    updatedAt = Instant.parse("2026-05-10T10:00:00Z"),
                ),
            ),
        )

        repo.applyBackup(backup)

        val note = repo.observeMerchantNotes().first().single()
        assertThat(note.note).isEqualTo("local newer note")
    }

    @Test
    fun applyBackup_inserts_transactions() = runTest {
        val repo = repo()
        // Ensure the category exists locally so categoryName resolves.
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }

        val backup = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            transactions = listOf(
                BackupTransaction(
                    amountMinor = 1500,
                    currency = "MYR",
                    merchantRaw = "MCDONALDS",
                    merchantNormalized = "MCDONALDS",
                    categoryName = "Food",
                    description = "lunch",
                    occurredAt = Instant.parse("2026-05-10T12:30:00Z"),
                    timeBucket = cy.txtracker.domain.TimeBucket.MIDDAY,
                    sourceApp = "com.google.android.apps.walletnfcrel",
                    rawText = "MCDONALDS RM15.00 with CIMB ••1868",
                    direction = cy.txtracker.data.Direction.OUT,
                    createdAt = Instant.parse("2026-05-10T12:30:00Z"),
                    notificationDedupeKey = "import-test-key-1",
                    needsVerification = false,
                ),
            ),
        )

        val result = repo.applyBackup(backup)

        assertThat(result.transactionsAdded).isEqualTo(1)
        val all = dbRule.transactionDao.getAllOnce()
        assertThat(all).hasSize(1)
        val tx = all.single()
        assertThat(tx.merchantNormalized).isEqualTo("MCDONALDS")
        assertThat(tx.categoryId).isEqualTo(food.id)
        assertThat(tx.notificationDedupeKey).isEqualTo("import-test-key-1")
    }

    @Test
    fun applyBackup_skips_transaction_when_dedupe_key_collides_with_local() = runTest {
        val repo = repo()
        // Pre-insert a local transaction with a specific dedupe key.
        val existingId = repo.insert(
            cy.txtracker.data.Transaction(
                amountMinor = 1000,
                currency = "MYR",
                merchantRaw = "LOCAL",
                merchantNormalized = "LOCAL",
                categoryId = null,
                description = null,
                occurredAt = Instant.parse("2026-05-10T10:00:00Z"),
                timeBucket = cy.txtracker.domain.TimeBucket.MIDDAY,
                sourceApp = "manual",
                rawText = null,
                direction = cy.txtracker.data.Direction.OUT,
                createdAt = Instant.parse("2026-05-10T10:00:00Z"),
                notificationDedupeKey = "collide-key",
                needsVerification = false,
            ),
        )!!

        val backup = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            transactions = listOf(
                BackupTransaction(
                    amountMinor = 9999, // different amount, same dedupe key
                    currency = "MYR",
                    merchantRaw = "FROM-BACKUP",
                    merchantNormalized = "FROM-BACKUP",
                    categoryName = null,
                    description = null,
                    occurredAt = Instant.parse("2026-05-10T10:00:00Z"),
                    timeBucket = cy.txtracker.domain.TimeBucket.MIDDAY,
                    sourceApp = "manual",
                    rawText = null,
                    direction = cy.txtracker.data.Direction.OUT,
                    createdAt = Instant.parse("2026-05-10T10:00:00Z"),
                    notificationDedupeKey = "collide-key",
                    needsVerification = false,
                ),
            ),
        )

        val result = repo.applyBackup(backup)

        assertThat(result.transactionsAdded).isEqualTo(0)
        val all = dbRule.transactionDao.getAllOnce()
        assertThat(all).hasSize(1)
        assertThat(all.single().id).isEqualTo(existingId)
        assertThat(all.single().merchantRaw).isEqualTo("LOCAL")
    }

    @Test
    fun applyBackup_v6_round_trips_currencies_trips_and_confirmation_flag() = runTest {
        val repo = repo()
        val backup = Backup(
            version = 6,
            exportedAt = now,
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            transactions = listOf(
                BackupTransaction(
                    amountMinor = 2000L,
                    currency = "GBP",
                    merchantRaw = "WISE",
                    merchantNormalized = "WISE",
                    categoryName = null,
                    description = null,
                    occurredAt = now,
                    timeBucket = TimeBucket.MIDDAY,
                    sourceApp = "com.transferwise.android",
                    rawText = null,
                    direction = Direction.OUT,
                    createdAt = now,
                    notificationDedupeKey = "k-gbp",
                    needsVerification = false,
                    needsCurrencyConfirmation = true,
                ),
            ),
            trackedCurrencies = listOf(
                BackupTrackedCurrency("GBP", "£", isDefaultForSymbol = false, addedAt = now),
            ),
            tripWindows = listOf(
                BackupTripWindow("GBP", startAt = now - 1.hours, endAt = null, createdAt = now),
            ),
        )

        repo.applyBackup(backup)

        assertThat(dbRule.trackedCurrencyDao.get("GBP")).isNotNull()
        assertThat(dbRule.tripWindowDao.observeAll().first()).hasSize(1)
        val txs = repo.getAllTransactionsOnce()
        assertThat(txs).hasSize(1)
        assertThat(txs.first().needsCurrencyConfirmation).isTrue()
    }

    @Test
    fun applyBackup_v5_defaults_missing_currency_fields_cleanly() = runTest {
        val repo = repo()
        val backup = Backup(
            version = 5,
            exportedAt = now,
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            transactions = listOf(
                BackupTransaction(
                    amountMinor = 1250L,
                    currency = "MYR",
                    merchantRaw = "MCDONALDS",
                    merchantNormalized = "MCDONALDS",
                    categoryName = null,
                    description = null,
                    occurredAt = now,
                    timeBucket = TimeBucket.MIDDAY,
                    sourceApp = "manual",
                    rawText = null,
                    direction = Direction.OUT,
                    createdAt = now,
                    notificationDedupeKey = "k-myr",
                    needsVerification = false,
                    // needsCurrencyConfirmation uses default = false
                ),
            ),
            // trackedCurrencies and tripWindows use default = emptyList()
        )

        repo.applyBackup(backup)

        assertThat(dbRule.trackedCurrencyDao.observeAll().first()).isEmpty()
        assertThat(dbRule.tripWindowDao.observeAll().first()).isEmpty()
        val txs = repo.getAllTransactionsOnce()
        assertThat(txs.first().needsCurrencyConfirmation).isFalse()
    }

    @Test
    fun applyBackup_inserts_transaction_with_null_category_when_categoryName_not_local() = runTest {
        val repo = repo()

        val backup = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            transactions = listOf(
                BackupTransaction(
                    amountMinor = 500,
                    currency = "MYR",
                    merchantRaw = "X",
                    merchantNormalized = "X",
                    categoryName = "NonExistentCategory",
                    description = null,
                    occurredAt = Instant.parse("2026-05-10T10:00:00Z"),
                    timeBucket = cy.txtracker.domain.TimeBucket.MIDDAY,
                    sourceApp = "manual",
                    rawText = null,
                    direction = cy.txtracker.data.Direction.OUT,
                    createdAt = Instant.parse("2026-05-10T10:00:00Z"),
                    notificationDedupeKey = "null-cat-test",
                    needsVerification = false,
                ),
            ),
        )

        val result = repo.applyBackup(backup)

        assertThat(result.transactionsAdded).isEqualTo(1)
        val tx = dbRule.transactionDao.getAllOnce().single()
        assertThat(tx.categoryId).isNull()
    }

    @Test
    fun applyBackup_v10_restores_two_reimbursement_entries_for_transaction() = runTest {
        val repo = repo()
        val dedupeKey = "v10-reimb-test"
        val reimbursedTotal = 3000L

        val backup = Backup(
            version = 10,
            exportedAt = now,
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            transactions = listOf(
                BackupTransaction(
                    amountMinor = 10000,
                    currency = "MYR",
                    merchantRaw = "RESTAURANT",
                    merchantNormalized = "RESTAURANT",
                    categoryName = null,
                    description = null,
                    occurredAt = now,
                    timeBucket = TimeBucket.MIDDAY,
                    sourceApp = "manual",
                    rawText = null,
                    direction = Direction.OUT,
                    createdAt = now,
                    notificationDedupeKey = dedupeKey,
                    needsVerification = false,
                    reimbursedMinor = reimbursedTotal,
                ),
            ),
            reimbursementEntries = listOf(
                BackupReimbursementEntry(
                    transactionDedupeKey = dedupeKey,
                    amountMinor = 1500,
                    destinationKind = "DEBIT_BANK",
                    personLabel = "Alice",
                    createdAt = now,
                ),
                BackupReimbursementEntry(
                    transactionDedupeKey = dedupeKey,
                    amountMinor = 1500,
                    destinationKind = "E_WALLET",
                    personLabel = "Bob",
                    createdAt = now,
                ),
            ),
        )

        val result = repo.applyBackup(backup)

        assertThat(result.transactionsAdded).isEqualTo(1)
        val tx = dbRule.transactionDao.getAllOnce().single()
        assertThat(tx.reimbursedMinor).isEqualTo(reimbursedTotal)
        val entries = dbRule.db.reimbursementEntryDao().getForTransaction(tx.id)
        assertThat(entries).hasSize(2)
        assertThat(entries.map { it.amountMinor }).containsExactly(1500L, 1500L)
    }

    @Test
    fun applyBackup_v9_synthesizes_single_debit_bank_entry_for_reimbursed_tx() = runTest {
        val repo = repo()
        val dedupeKey = "v9-synth-test"

        val backup = Backup(
            version = 9,
            exportedAt = now,
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            transactions = listOf(
                BackupTransaction(
                    amountMinor = 8000,
                    currency = "MYR",
                    merchantRaw = "SHOP",
                    merchantNormalized = "SHOP",
                    categoryName = null,
                    description = null,
                    occurredAt = now,
                    timeBucket = TimeBucket.MIDDAY,
                    sourceApp = "manual",
                    rawText = null,
                    direction = Direction.OUT,
                    createdAt = now,
                    notificationDedupeKey = dedupeKey,
                    needsVerification = false,
                    reimbursedMinor = 5000,
                ),
            ),
            // reimbursementEntries omitted — v9-style backup
        )

        repo.applyBackup(backup)

        val tx = dbRule.transactionDao.getAllOnce().single()
        val entries = dbRule.db.reimbursementEntryDao().getForTransaction(tx.id)
        assertThat(entries).hasSize(1)
        val entry = entries.single()
        assertThat(entry.amountMinor).isEqualTo(5000)
        assertThat(entry.destinationKind).isEqualTo(FundingSourceKind.DEBIT_BANK)
        assertThat(entry.personLabel).isNull()
    }
}
