package cy.txtracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationV11ToV12Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrates_v11_to_v12_adds_reimbursedMinor_column() {
        helper.createDatabase(DB_NAME, 11).apply { close() }

        val db = helper.runMigrationsAndValidate(DB_NAME, 12, true)

        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(`transactions`)").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) columns.add(c.getString(nameIdx))
        }
        assertThat(columns).contains("reimbursedMinor")
    }

    companion object { private const val DB_NAME = "migration-v11-v12-test.db" }
}
