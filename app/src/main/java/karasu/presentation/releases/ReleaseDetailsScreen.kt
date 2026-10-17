package karasu.presentation.releases

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.icerock.moko.resources.compose.pluralStringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.source.model.SManga
import karasu.domain.releases.ORIGIN_MANGADEX
import karasu.domain.releases.Release
import karasu.domain.releases.ReleaseDetail
import karasu.domain.releases.TYPE_MANHUA
import karasu.domain.releases.TYPE_MANHWA
import karasu.i18n.MR
import karasu.presentation.AppBarType
import karasu.presentation.KarasuScaffold
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A series from the "Releases" window: what the catalogue says about it, and a way to look for it
 * in the reader's own sources. Deliberately not a manga page — there are no chapters to list and
 * nothing to track until a source has it.
 *
 * [detail] is null while it loads or when it could not be: the list's own data is shown either way.
 */
@Composable
fun ReleaseDetailsScreen(
    release: Release,
    detail: ReleaseDetail?,
    loading: Boolean,
    failed: Boolean,
    portugueseNames: List<String>,
    searching: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val shown = detail?.release ?: release
    KarasuScaffold(
        onNavigationIconClicked = onBack,
        title = shown.title,
        appBarType = AppBarType.SMALL,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box {
                    detail?.banner?.let { banner ->
                        AsyncImage(
                            model = banner,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (detail?.banner != null) 80.dp else 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AsyncImage(
                            model = shown.cover,
                            contentDescription = stringResource(MR.strings.cover_of_image),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                        )
                        Column(modifier = Modifier.padding(top = if (detail?.banner != null) 64.dp else 0.dp)) {
                            Text(
                                text = stringResource(MR.strings.experimental),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Text(text = shown.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = listOfNotNull(typeLabel(shown), statusLabel(shown.status), shown.start?.let(::dateLabel)).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            detail?.authors?.takeIf { it.isNotEmpty() }?.let {
                                Text(stringResource(MR.strings.releases_story, it.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                            }
                            detail?.artists?.takeIf { it.isNotEmpty() && it != detail.authors }?.let {
                                Text(stringResource(MR.strings.releases_art, it.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onSearch,
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    if (searching) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    }
                    Text(stringResource(MR.strings.releases_search_sources), modifier = Modifier.padding(start = 8.dp))
                }
            }

            if (shown.origin == ORIGIN_MANGADEX) {
                item { Note(stringResource(MR.strings.releases_from_mangadex)) }
            }
            if (loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else if (failed) {
                item { Note(stringResource(MR.strings.releases_details_failed)) }
            }

            item {
                val numbers = listOfNotNull(
                    detail?.chapters?.let { pluralStringResource(MR.plurals.chapters_plural, it, it.toString()) },
                    detail?.volumes?.let { pluralStringResource(MR.plurals.releases_volumes, it, it) },
                    shown.score?.let { stringResource(MR.strings.releases_score, it) },
                    // MangaDex's stands for a rank, not a count: not worth showing.
                    shown.popularity?.takeIf { shown.origin != ORIGIN_MANGADEX }?.let { stringResource(MR.strings.releases_popularity, it) },
                )
                if (numbers.isNotEmpty()) Note(numbers.joinToString(" · "))
            }

            detail?.description?.let { description ->
                item { Text(description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
            }

            val names = (portugueseNames + shown.names).distinct()
            item {
                Section(stringResource(MR.strings.releases_names)) {
                    names.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            val tags = shown.genres.map { it to 100 } + detail?.tags.orEmpty()
            if (tags.isNotEmpty()) {
                item {
                    Section(stringResource(MR.strings.releases_tags)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.distinctBy { it.first.lowercase() }.forEach { (tag, rank) ->
                                AssistChip(onClick = {}, label = { Text(if (rank in 1..99) "$tag $rank%" else tag) })
                            }
                        }
                    }
                }
            }

            detail?.relations?.takeIf { it.isNotEmpty() }?.let { relations ->
                item {
                    Section(stringResource(MR.strings.releases_related)) {
                        relations.forEach { (type, name) ->
                            Text("${relationLabel(type)}: $name", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            val links = detail?.links ?: listOf((if (shown.origin == ORIGIN_MANGADEX) "MangaDex" else "AniList") to shown.url)
            item {
                Section(stringResource(MR.strings.releases_links)) {
                    links.forEach { (site, url) ->
                        Text(
                            text = site,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenLink(url) }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun typeLabel(release: Release): String = stringResource(
    when (release.type) {
        TYPE_MANHWA -> MR.strings.manhwa
        TYPE_MANHUA -> MR.strings.manhua
        // "manga" is the library filter's plural in pt-BR ("Mangás"); this names one series.
        else -> MR.strings.releases_kind_manga
    },
)

@Composable
private fun statusLabel(status: Int?): String? = when (status) {
    SManga.ONGOING -> stringResource(MR.strings.ongoing)
    SManga.COMPLETED -> stringResource(MR.strings.completed)
    SManga.ON_HIATUS -> stringResource(MR.strings.on_hiatus)
    SManga.CANCELLED -> stringResource(MR.strings.cancelled)
    else -> null
}

/** yyyymmdd → month and year in the reader's locale: a missing day was filled in as the 1st, so no day is shown. */
private fun dateLabel(date: Int): String {
    val locale = Locale.getDefault()
    val month = Calendar.getInstance().apply { clear(); set(date / 10_000, date / 100 % 100 - 1, 1) }
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMyyyy"), locale).format(month.time)
}

/** AniList's SEQUEL, SPIN_OFF… in the reader's language; a type without a string is shown as words. */
@Composable
private fun relationLabel(type: String): String = when (type) {
    "SEQUEL" -> stringResource(MR.strings.releases_relation_sequel)
    "PREQUEL" -> stringResource(MR.strings.releases_relation_prequel)
    "SIDE_STORY" -> stringResource(MR.strings.releases_relation_side_story)
    "SPIN_OFF" -> stringResource(MR.strings.releases_relation_spin_off)
    "ADAPTATION" -> stringResource(MR.strings.releases_relation_adaptation)
    "SOURCE" -> stringResource(MR.strings.releases_relation_source)
    "ALTERNATIVE" -> stringResource(MR.strings.releases_relation_alternative)
    "PARENT" -> stringResource(MR.strings.releases_relation_parent)
    else -> type.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
}
