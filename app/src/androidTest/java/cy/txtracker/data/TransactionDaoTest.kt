package cy.txtracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.TimeBucket
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionDaoTest {

    @get:Rule val dbRule = DbRule()

    private suspend fun foodCategoryId(): Long =
        dbRule.categoryDao.getAll().first { it.name == "Food" }.id

    @Test
    fun insert_returns_rowId() = runTest {
        val rowId = dbRule.transactionDao.insert(txAt(occurredAt = Instant.fromEpochMilliseconds(1_000)))
        assertThat(rowId).isGreaterThan(0L)
    }

    @Test
    fun insert_dedupe_collision_returns_negative_one() = runTest {
        val first = txAt(
            occurredAt = Instant.fromEpochMilliseconds(1_000),
            dedupeKey = "dup-key",
        )
        val secondSameKey = first.copy(amountMinor = 9999, id = 0)

        val firstId = dbRule.transactionDao.insert(first)
        val secondId = dbRule.transactionDao.insert(secondSameKey)

        assertThat(firstId).isGreaterThan(0L)
        assertThat(secondId).isEqualTo(-1L)
        // Original row is preserved, not overwritten.
        val row = dbRule.transactionDao.getById(firstId)!!
        assertThat(row.amountMinor).isEqualTo(1250)
    }

    @Test
    fun observeBetween_filters_by_occurredAt_range() = runTest {
        val janFirst = Instant.parse("2026-01-01T00:00:00Z")
        val janFifteenth = Instant.parse("2026-01-15T12:00:00Z")
        val febFirst = Instant.parse("2026-02-01T00:00:00Z")

        dbRule.transactionDao.insert(txAt(janFifteenth, dedupeKey = "jan"))
        dbRule.transactionDao.insert(txAt(febFirst, dedupeKey = "feb"))

        dbRule.transactionDao.observeBetween(janFirst, febFirst).test {
            val rows = awaitItem()
            assertThat(rows).hasSize(1)
            assertThat(rows.single().notificationDedupeKey).isEqualTo("jan")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeTotalBetween_sums_only_OUT_in_window() = runTest {
        val start = Instant.parse("2026-05-01T00:00:00Z")
        val end = Instant.parse("2026-06-01T00:00:00Z")

        dbRule.transactionDao.insert(
            txAt(Instant.parse("2026-05-09T12:30:00Z"), amountMinor = 1250, dedupeKey = "a"),
        )
        dbRule.transactionDao.insert(
            txAt(Instant.parse("2026-05-15T18:00:00Z"), amountMinor = 800, dedupeKey = "b"),
        )
        dbRule.transactionDao.insert(
            // Outside window — must be excluded.
            txAt(Instant.parse("2026-04-30T23:00:00Z"), amountMinor = 9999, dedupeKey = "c"),
        )

        dbRule.transactionDao.observeTotalBetween(start, end).test {
            assertThat(awaitItem()).isEqualTo(2050L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeTotalBetween_nets_out_reimbursed_portion() = runTest {
        val start = Instant.parse("2026-05-01T00:00:00Z")
        val end = Instant.parse("2026-06-01T00:00:00Z")

        val id = dbRule.transactionDao.insert(
            txAt(Instant.parse("2026-05-09T12:30:00Z"), amountMinor = 10000, dedupeKey = "r"),
        )
        dbRule.transactionDao.updateReimbursed(id, 4000)

        dbRule.transactionDao.observeTotalBetween(start, end).test {
            assertThat(awaitItem()).isEqualTo(6000L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeCategoryTotalsBetween_groups_by_category_with_null_for_unverified() = runTest {
        val start = Instant.parse("2026-05-01T00:00:00Z")
        val end = Instant.parse("2026-06-01T00:00:00Z")
        val food = foodCategoryId()

        dbRule.transactionDao.insert(
            txAt(
                Instant.parse("2026-05-02T12:30:00Z"),
                amountMinor = 1000,
                categoryId = food,
                dedupeKey = "f1",
            ),
        )
        dbRule.transactionDao.insert(
            txAt(
                Instant.parse("2026-05-03T13:00:00Z"),
                amountMinor = 2500,
                categoryId = food,
                dedupeKey = "f2",
            ),
        )
        dbRule.transactionDao.insert(
            txAt(
                Instant.parse("2026-05-04T14:00:00Z"),
                amountMinor = 700,
                categoryId = null,
                dedupeKey = "u1",
            ),
        )

        dbRule.transactionDao.observeCategoryTotalsBetween(start, end).test {
            val totals = awaitItem().associateBy { it.categoryId }
            assertThat(totals[food]?.totalMinor).isEqualTo(3500)
            assertThat(totals[null]?.totalMinor).isEqualTo(700)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleting_category_sets_referencing_transactions_categoryId_to_null() = runTest {
        // SET_NULL foreign-key behavior — losing a category should not destroy its transactions.
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val txId = dbRule.transactionDao.insert(
            txAt(
                Instant.parse("2026-05-09T12:30:00Z"),
                categoryId = food.id,
                dedupeKey = "tx",
            ),
        )

        dbRule.categoryDao.delete(food)

        val after = dbRule.transactionDao.getById(txId)!!
        assertThat(after.categoryId).isNull()
    }

    @Test
    fun slShare_netted_from_category_and_total_aggregates() = runTest {
        val start = Instant.parse("2026-05-01T00:00:00Z")
        val end = Instant.parse("2026-06-01T00:00:00Z")
        val food = foodCategoryId()

        // tx A: amountMinor=10_000, slShareMinor=4_000 → nets to 6_000
        dbRule.transactionDao.insert(
            txAt(
                Instant.parse("2026-05-10T10:00:00Z"),
                amountMinor = 10_000,
                categoryId = food,
                dedupeKey = "sl-a",
            ).copy(slShareMinor = 4_000),
        )
        // tx B: amountMinor=5_000, slShareMinor=null → nets to 5_000
        dbRule.transactionDao.insert(
            txAt(
                Instant.parse("2026-05-11T10:00:00Z"),
                amountMinor = 5_000,
                categoryId = food,
                dedupeKey = "sl-b",
            ),
        )

        // Category total for food: 6_000 + 5_000 = 11_000
        dbRule.transactionDao.observeCategoryTotalsBetween(start, end).test {
            val totals = awaitItem().associateBy { it.categoryId }
            assertThat(totals[food]?.totalMinor).isEqualTo(11_000)
            cancelAndIgnoreRemainingEvents()
        }

        // Home headline total: same 11_000
        dbRule.transactionDao.observeTotalBetween(start, end).test {
            assertThat(awaitItem()).isEqualTo(11_000L)
            cancelAndIgnoreRemainingEvents()
        }

        // Share sum: only tx A's 4_000
        dbRule.transactionDao.observeShareSum().test {
            assertThat(awaitItem()).isEqualTo(4_000L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun updateCategory_changes_only_category_field() = runTest {
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val txId = dbRule.transactionDao.insert(
            txAt(Instant.parse("2026-05-09T12:30:00Z"), dedupeKey = "tx"),
        )

        dbRule.transactionDao.updateCategory(txId, food.id)

        val after = dbRule.transactionDao.getById(txId)!!
        assertThat(after.categoryId).isEqualTo(food.id)
        assertThat(after.amountMinor).isEqualTo(1250)
        assertThat(after.timeBucket).isEqualTo(TimeBucket.MIDDAY)
    }
}
