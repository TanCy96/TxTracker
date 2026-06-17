package cy.txtracker.data

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v14 -> v15 adds two additive source-config tables: `custom_source_labels` (user rename
 * overrides for tracked apps) and `auto_promote_sources` (packages opted into auto-adding
 * amount-only notifications to home). No backfill.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV14ToV15Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbName = "migration-test-14-15"

    @Test
    fun migrate14To15_createsBothTables() {
        helper.createDatabase(dbName, 14).close()
        val db = helper.runMigrationsAndValidate(dbName, 15, true, MIGRATION_14_15)
        db.execSQL("INSERT INTO custom_source_labels(packageName, label, updatedAt) VALUES('p', 'Nice', 1)")
        db.execSQL("INSERT INTO auto_promote_sources(packageName, enabledAt) VALUES('p', 1)")
        db.close()
    }
}

// Test copy of the production MIGRATION_14_15 (which is `private` in DatabaseModule.kt, in the
// `cy.txtracker.di` package and therefore unreachable from here). Kept byte-identical to the
// production version so the test exercises the exact migration SQL that ships.
private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `custom_source_labels` (
                `packageName` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `auto_promote_sources` (
                `packageName` TEXT NOT NULL,
                `enabledAt` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`)
            )
            """.trimIndent(),
        )
    }
}
