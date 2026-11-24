package karasu.presentation.settings.screen.data

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.backup.BackupFileValidator.Results
import eu.kanade.tachiyomi.data.backup.create.BackupCreatorJob
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.util.relativeTimeSpanString
import eu.kanade.tachiyomi.util.system.toast
import karasu.domain.DialogHostState
import karasu.domain.koreader.models.RemoteBackupInfo
import karasu.i18n.MR
import karasu.presentation.component.LabeledCheckbox
import kotlin.coroutines.resume
import android.R as AR

suspend fun DialogHostState.awaitRestoreBackup(
    context: Context,
    uri: Uri,
    pair: Pair<Results?, Exception?>,
): Unit = dialog { cont ->
    val (results, e) = pair
    if (results != null) {
        var message = stringResource(MR.strings.restore_content_full)
        if (results.missingSources.isNotEmpty()) {
            message += "\n\n${stringResource(MR.strings.restore_missing_sources)}\n${
                results.missingSources.joinToString(
                    "\n",
                ) { "- $it" }
            }"
        }
        if (results.missingTrackers.isNotEmpty()) {
            message += "\n\n${stringResource(MR.strings.restore_missing_trackers)}\n${
                results.missingTrackers.joinToString(
                    "\n",
                ) { "- $it" }
            }"
        }

        AlertDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.toast(MR.strings.restoring_backup)
                        BackupRestoreJob.start(context, uri)
                        cont.cancel()
                    },
                ) {
                    Text(text = stringResource(MR.strings.restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = stringResource(AR.string.cancel))
                }
            },
            title = { Text(text = stringResource(MR.strings.restore_backup)) },
            text = { Text(text = message) },
        )
    } else {
        AlertDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = stringResource(AR.string.cancel))
                }
            },
            title = { Text(text = stringResource(MR.strings.invalid_backup_file)) },
            text = { e?.message?.let { Text(text = it) } }
        )
    }
}

suspend fun DialogHostState.awaitCreateBackup(
    context: Context,
    uri: Uri,
): Unit = dialog { cont ->
    var options by mutableStateOf(BackupOptions())

    AlertDialog(
        onDismissRequest = { cont.cancel() },
        confirmButton = {
            TextButton(onClick = {
                val actualUri =
                    UniFile.fromUri(context, uri)?.createFile(Backup.getBackupFilename())?.uri ?: return@TextButton
                context.toast(MR.strings.creating_backup)
                BackupCreatorJob.startNow(context, actualUri, options)
                cont.cancel()
            }) {
                Text(stringResource(MR.strings.create))
            }
        },
        dismissButton = {
            TextButton(onClick = { cont.cancel() }) {
                Text(stringResource(MR.strings.cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.create_backup)) },
        text = {
            Box {
                val state = rememberLazyListState()
                LazyColumn(state = state) {
                    BackupOptions.getEntries().forEach { option ->
                        item {
                            LabeledCheckbox(
                                label = stringResource(option.label),
                                checked = option.getter(options),
                                onCheckedChange = { options = option.setter(options, it) },
                                enabled = option.enabled(options),
                            )
                        }
                    }
                }
            }
        },
    )
}

suspend fun DialogHostState.awaitSelectRemoteBackup(
    context: Context,
    backups: List<RemoteBackupInfo>,
): RemoteBackupInfo? = dialog { cont ->
    AlertDialog(
        onDismissRequest = { if (cont.isActive) cont.resume(null) },
        confirmButton = {
            TextButton(onClick = { if (cont.isActive) cont.resume(null) }) {
                Text(text = stringResource(MR.strings.cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.yata_backup_restore)) },
        text = {
            val state = rememberLazyListState()
            LazyColumn(state = state) {
                items(backups, key = { it.id }) { backup ->
                    val displayName = backup.name.ifBlank { backup.id }
                    val sizeText = Formatter.formatFileSize(context, backup.size)
                    val createdAtMs = when {
                        backup.createdAt <= 0L -> 0L
                        backup.createdAt < 100_000_000_000L -> backup.createdAt * 1000L
                        else -> backup.createdAt
                    }
                    val dateText = if (createdAtMs > 0L) relativeTimeSpanString(createdAtMs) else null
                    val metaText = listOfNotNull(sizeText, dateText).joinToString(" · ")

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (cont.isActive) cont.resume(backup)
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = metaText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}
