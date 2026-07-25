package karasu.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.pluralStringResource
import dev.icerock.moko.resources.compose.stringResource
import karasu.domain.manga.failures.interactor.BreakageKind
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
            BrokenSourceCard(source = source, onMigrate = { onMigrate(source) })
        }
    }
}

@Composable
private fun BrokenSourceCard(source: BrokenSource, onMigrate: () -> Unit) {
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
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            ) {
                TextButton(onClick = onMigrate) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(MR.strings.broken_sources_migrate_all),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

private fun BreakageKind.label() = when (this) {
    BreakageKind.SOURCE_MISSING -> MR.strings.broken_source_missing
    BreakageKind.SOURCE_OBSOLETE -> MR.strings.broken_source_obsolete
    BreakageKind.FAILING -> MR.strings.broken_source_failing
}

private const val PREVIEW_TITLES = 3
