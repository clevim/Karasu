package karasu.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.pluralStringResource
import dev.icerock.moko.resources.compose.stringResource
import karasu.domain.manga.failures.interactor.BreakageKind
import karasu.domain.manga.failures.FailureCause
import karasu.domain.manga.failures.causeOf
import karasu.domain.manga.failures.interactor.BrokenSource
import karasu.i18n.MR

/**
 * The sources that stopped working, grouped by source rather than by manga.
 *
 * Grouping matters: a dead extension takes every entry with it, and the useful action is
 * "migrate all forty of these", not "open forty manga one at a time".
 */
@Composable
fun BrokenSourcesScreen(
    sources: List<BrokenSource>,
    onMigrate: (BrokenSource) -> Unit,
    onUpdateExtensions: () -> Unit,
    onRetryUpdate: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    text = stringResource(MR.strings.broken_sources),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                Text(
                    text = stringResource(
                        if (sources.isEmpty()) {
                            MR.strings.broken_sources_none
                        } else {
                            MR.strings.broken_sources_explainer
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        items(sources, key = { "${it.sourceId}-${it.kind}" }) { source ->
            BrokenSourceCard(
                source = source,
                onMigrate = { onMigrate(source) },
                onUpdateExtensions = onUpdateExtensions,
                onRetryUpdate = onRetryUpdate,
            )
        }
    }
}

@Composable
private fun BrokenSourceCard(
    source: BrokenSource,
    onMigrate: () -> Unit,
    onUpdateExtensions: () -> Unit,
    onRetryUpdate: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                text = source.sourceName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = stringResource(source.kind.label()),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = when (source.kind) {
                    BreakageKind.SOURCE_MISSING -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Text(
                text = pluralStringResource(
                    MR.plurals.broken_sources_entries,
                    quantity = source.entries.size,
                    source.entries.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )

            // A few names make the group recognisable without turning the card into a list.
            source.entries.take(PREVIEW_TITLES).forEach { entry ->
                Text(
                    text = "• ${entry.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 2.dp),
                )
            }
            if (source.entries.size > PREVIEW_TITLES) {
                Text(
                    text = stringResource(
                        MR.strings.broken_sources_and_more,
                        source.entries.size - PREVIEW_TITLES,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 2.dp),
                )
            }

            // Only shown when there is an error to show: an uninstalled extension never produced
            // one, and an empty line would read as the source failing silently.
            source.entries.firstNotNullOfOrNull { it.lastMessage?.takeIf(String::isNotBlank) }
                ?.let { message ->
                    // The plain-language cause first, because the raw text is a library's
                    // internal complaint and says nothing about what to do. The raw text still
                    // follows, quieter and clipped: it is what makes a bug report useful.
                    causeOf(message).label()?.let { cause ->
                        Text(
                            text = stringResource(cause),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = RAW_ERROR_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }

            // Migrating is always offered but is deliberately never the only thing on the card
            // when something cheaper might work: it rewrites which source every one of these
            // entries points at, and there is no undo. A source that is merely refusing requests
            // today usually needs another attempt, not a rewrite of the library.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp),
            ) {
                if (source.kind == BreakageKind.FAILING) {
                    when (source.cause) {
                        FailureCause.OUTDATED_EXTENSION -> CardAction(
                            icon = Icons.Default.Update,
                            label = MR.strings.broken_sources_update_extension,
                            onClick = onUpdateExtensions,
                        )
                        FailureCause.SOURCE_REFUSED -> CardAction(
                            icon = Icons.Default.Refresh,
                            label = MR.strings.broken_sources_retry,
                            onClick = onRetryUpdate,
                        )
                        FailureCause.UNKNOWN -> Unit
                    }
                }
                CardAction(
                    icon = Icons.Default.SwapHoriz,
                    label = MR.strings.broken_sources_migrate_all,
                    onClick = onMigrate,
                )
            }
        }
    }
}

@Composable
private fun CardAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: dev.icerock.moko.resources.StringResource,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = stringResource(label), modifier = Modifier.padding(start = 8.dp))
    }
}

private fun BreakageKind.label() = when (this) {
    BreakageKind.SOURCE_MISSING -> MR.strings.broken_source_missing
    BreakageKind.SOURCE_OBSOLETE -> MR.strings.broken_source_obsolete
    BreakageKind.FAILING -> MR.strings.broken_source_failing
}

/** Null when there is nothing useful to add beyond the raw message. */
private fun FailureCause.label() = when (this) {
    FailureCause.OUTDATED_EXTENSION -> MR.strings.broken_cause_outdated_extension
    FailureCause.SOURCE_REFUSED -> MR.strings.broken_cause_source_refused
    FailureCause.UNKNOWN -> null
}

private const val PREVIEW_TITLES = 3
private const val RAW_ERROR_LINES = 3
