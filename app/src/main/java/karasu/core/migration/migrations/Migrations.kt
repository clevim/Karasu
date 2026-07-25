package karasu.core.migration.migrations

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import karasu.core.migration.Migration

val migrations: ImmutableList<Migration> = persistentListOf(
    // Always run
    SetupAppUpdateMigration(),
    SetupBackupCreateMigration(),
    SetupExtensionUpdateMigration(),
    SetupKoreaderSyncMigration(),
    SetupLibraryUpdateMigration(),

    // Karasu fork
    CutoutMigration(),
    ExtensionInstallerEnumMigration(),
    RepoJsonMigration(),
    ThePurgeMigration(),
)
