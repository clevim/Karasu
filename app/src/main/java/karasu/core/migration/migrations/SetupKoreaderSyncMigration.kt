package karasu.core.migration.migrations

import android.app.Application
import eu.kanade.tachiyomi.data.koreader.KoreaderSyncJob
import karasu.core.migration.Migration
import karasu.core.migration.MigrationContext

/**
 * Re-registers the shelf sync on every start, and cancels it when no shelf is configured — which
 * is also what stops a stale periodic job surviving after the URL is cleared.
 */
class SetupKoreaderSyncMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        KoreaderSyncJob.setupTask(context)
        return true
    }
}
