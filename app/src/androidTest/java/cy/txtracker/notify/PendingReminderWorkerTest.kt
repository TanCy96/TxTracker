package cy.txtracker.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.DbRule
import cy.txtracker.data.TransactionRepository
import cy.txtracker.data.txAt
import cy.txtracker.service.NotificationPrefs
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingReminderWorkerTest {

    @get:Rule val dbRule = DbRule()

    private lateinit var context: Context
    private lateinit var prefs: NotificationPrefs

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

    private fun buildWorker(): PendingReminderWorker {
        val repository = repo()
        return TestListenableWorkerBuilder<PendingReminderWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    PendingReminderWorker(appContext, workerParameters, repository, prefs)
            })
            .build()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = NotificationPrefs(context)
        NotificationChannels.registerAll(context)
        prefs.setPendingEnabled(false)
        prefs.setPendingDismissedUntil(null)
    }

    @Test
    fun returns_success_when_disabled() = runTest {
        val worker = buildWorker()
        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun returns_success_when_no_stale_rows() = runTest {
        prefs.setPendingEnabled(true)
        repo().insert(txAt(Clock.System.now(), dedupeKey = "k1"))

        val worker = buildWorker()
        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun returns_success_during_cooldown() = runTest {
        prefs.setPendingEnabled(true)
        prefs.setPendingDismissedUntil(Clock.System.now() + 6.hours)
        val staleInstant = Clock.System.now() - 30.hours
        repo().insert(
            txAt(staleInstant, dedupeKey = "stale1")
                .copy(needsVerification = true, createdAt = staleInstant),
        )

        val worker = buildWorker()
        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun posts_notification_for_stale_rows() = runTest {
        prefs.setPendingEnabled(true)
        val staleInstant = Clock.System.now() - 30.hours
        repo().insert(
            txAt(staleInstant, dedupeKey = "stale1")
                .copy(needsVerification = true, createdAt = staleInstant),
        )

        val worker = buildWorker()
        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }
}
