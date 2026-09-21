package karasu.presentation.recommendation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.pluralStringResource
import dev.icerock.moko.resources.compose.stringResource
import karasu.i18n.MR
import karasu.presentation.core.enterAlwaysAppBarScrollBehavior
import karasu.presentation.KarasuScaffold
import karasu.presentation.AppBarType
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.roundToInt

/**
 * Every tag the profile knows, with its weight on a slider.
 *
 * @param learned what the library taught, null while still being read.
 * @param overrides what the reader fixed by hand. A tag in here shows its fixed weight and a way
 *   back to the learned one; a tag in here but not in [learned] was added by the reader and the
 *   way back is to remove it.
 */
@Composable
fun RecommendationTagsScreen(
    learned: Map<String, Float>?,
    overrides: Map<String, Float>,
    support: Map<String, Int> = emptyMap(),
    onChange: (Map<String, Float>) -> Unit,
    verdicts: Int = 0,
    onForgetVerdicts: () -> Unit = {},
    onBan: (String) -> Unit = {},
    banned: Int = 0,
    onRestoreBanned: () -> Unit = {},
    merges: Int = 0,
    onMerge: (tags: Set<String>, into: String) -> Unit = { _, _ -> },
    onUnmergeAll: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(bottom = 32.dp),
) {
    // Long-press picks tags to merge; while any are picked the app bar is the merge bar, the way
    // a selection takes over the toolbar everywhere else in the app.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var naming by remember { mutableStateOf(false) }
    val onBack = LocalBackPress.current ?: {}
    val listState = rememberLazyListState()

    KarasuScaffold(
        onNavigationIconClicked = { if (selected.isNotEmpty()) selected = emptySet() else onBack() },
        navigationIcon = if (selected.isNotEmpty()) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
        title = if (selected.isNotEmpty()) {
            pluralStringResource(MR.plurals.recommendation_tags_selected, selected.size, selected.size)
        } else {
            stringResource(MR.strings.recommendation_tags)
        },
        appBarType = AppBarType.SMALL,
        scrollBehavior = enterAlwaysAppBarScrollBehavior(
            canScroll = { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 },
        ),
        actions = {
            if (selected.isNotEmpty()) {
                TextButton(onClick = { naming = true }, enabled = selected.size >= 2) {
                    Text(stringResource(MR.strings.recommendation_tags_merge))
                }
            }
        },
    ) { innerPadding ->
        if (learned == null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@KarasuScaffold
        }
        TagList(
            learned = learned,
            support = support,
            overrides = overrides,
            onChange = onChange,
            verdicts = verdicts,
            onForgetVerdicts = onForgetVerdicts,
            onBan = onBan,
            banned = banned,
            onRestoreBanned = onRestoreBanned,
            merges = merges,
            onUnmergeAll = onUnmergeAll,
            selected = selected,
            onSelect = { tag -> selected = if (tag in selected) selected - tag else selected + tag },
            listState = listState,
            contentPadding = contentPadding,
            modifier = Modifier.padding(innerPadding),
        )
    }

    if (naming) {
        MergeNameDialog(
            initial = selected.firstOrNull().orEmpty(),
            onConfirm = { name ->
                onMerge(selected, name)
                selected = emptySet()
                naming = false
            },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun TagList(
    learned: Map<String, Float>,
    support: Map<String, Int>,
    overrides: Map<String, Float>,
    onChange: (Map<String, Float>) -> Unit,
    verdicts: Int,
    onForgetVerdicts: () -> Unit,
    onBan: (String) -> Unit,
    banned: Int,
    onRestoreBanned: () -> Unit,
    merges: Int,
    onUnmergeAll: () -> Unit,
    selected: Set<String>,
    onSelect: (String) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // Overrides are keyed however the reader typed them; match them to the learned spelling.
    fun overrideOf(tag: String) = overrides.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }
    val rows = (learned.keys + overrides.keys.filter { key -> learned.keys.none { it.equals(key, true) } })
        .map { tag -> Triple(tag, learned[tag], overrideOf(tag)?.value) }
        // A weight that rounds to nothing is a tag on one entry the reader barely opened. It
        // would only be noise on this list; it is still there to be found by name.
        .filter { (_, learnedWeight, override) -> override != null || (learnedWeight ?: 0f) >= MIN_SHOWN_WEIGHT }
        .sortedByDescending { (_, learnedWeight, override) -> override ?: learnedWeight ?: 0f }

    // One field: typing narrows the list to matching tags; the + adds what was typed as a new one.
    var newTag by remember { mutableStateOf("") }
    val shown = newTag.trim().let { q -> if (q.isEmpty()) rows else rows.filter { (tag, _, _) -> tag.contains(q, ignoreCase = true) } }
    fun add() {
        val tag = newTag.trim()
        if (tag.isNotEmpty()) onChange(overrides + (tag to 1f))
        newTag = ""
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(
                        if (selected.isNotEmpty()) MR.strings.recommendation_tags_merge_explainer
                        else MR.strings.recommendation_tags_explainer,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text(stringResource(MR.strings.recommendation_tag_search_or_add)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = ::add, enabled = newTag.isNotBlank()) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(MR.strings.recommendation_tag_add))
                    }
                }
                // A bad run of "not interested" must be undoable, or the profile is poisoned for good.
                if (verdicts > 0) {
                    TextButton(onClick = onForgetVerdicts) {
                        Text(pluralStringResource(MR.plurals.recommendation_forget_verdicts, verdicts, verdicts))
                    }
                }
                if (banned > 0) {
                    TextButton(onClick = onRestoreBanned) {
                        Text(pluralStringResource(MR.plurals.recommendation_restore_banned, banned, banned))
                    }
                }
                if (merges > 0) {
                    TextButton(onClick = onUnmergeAll) {
                        Text(pluralStringResource(MR.plurals.recommendation_unmerge, merges, merges))
                    }
                }
            }
        }
        items(shown, key = { (tag, _, _) -> tag.lowercase() }) { (tag, learnedWeight, override) ->
            TagRow(
                tag = tag,
                learned = learnedWeight,
                support = support[tag],
                override = override,
                selected = tag in selected,
                selecting = selected.isNotEmpty(),
                onSelect = { onSelect(tag) },
                onWeight = { weight -> onChange(overrides.without(tag) + (tag to weight)) },
                onReset = { onChange(overrides.without(tag)) },
                // Striking a learned tag says "this is not a genre"; it leaves the list for good.
                // A tag the reader typed is simply un-typed.
                onRemove = if (learnedWeight != null) ({ onChange(overrides.without(tag)); onBan(tag) }) else null,
            )
        }
    }
}

private fun Map<String, Float>.without(tag: String) = filterKeys { !it.equals(tag, ignoreCase = true) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagRow(
    tag: String,
    learned: Float?,
    support: Int?,
    override: Float?,
    onWeight: (Float) -> Unit,
    onReset: () -> Unit,
    onRemove: (() -> Unit)? = null,
    selected: Boolean = false,
    selecting: Boolean = false,
    onSelect: () -> Unit = {},
) {
    val effective = override ?: learned ?: 0f
    // Dragging is local; the number is only written when the finger lifts.
    var value by remember(effective) { mutableFloatStateOf(effective) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            // A long press starts picking; once picking, a tap picks too.
            .combinedClickable(onClick = { if (selecting) onSelect() }, onLongClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (override != null) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    // "1%" on forty series and "1%" on one series are different things: the count
                    // is what says whether the weight is a pattern or a single entry.
                    text = when {
                        learned != null && support != null && support > 0 ->
                            stringResource(MR.strings.recommendation_tag_learned, learned.percent()) + " · " +
                                pluralStringResource(MR.plurals.recommendation_support, support, support)
                        learned != null -> stringResource(MR.strings.recommendation_tag_learned, learned.percent())
                        else -> stringResource(MR.strings.recommendation_tag_added)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value.percent(),
                style = MaterialTheme.typography.labelLarge,
                color = if (value < 0f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (override != null) {
                IconButton(onClick = onReset) {
                    Icon(
                        imageVector = if (learned != null) Icons.Outlined.Refresh else Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.strings.recommendation_tag_reset),
                    )
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = stringResource(MR.strings.recommendation_tag_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onWeight(value) },
            valueRange = -1f..1f,
        )
    }
}

private fun Float.percent() = "${(this * 100).roundToInt()}%"

@Composable
private fun MergeNameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.recommendation_tags_merge)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(MR.strings.recommendation_tags_merge_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(MR.strings.recommendation_tags_merge)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.cancel)) } },
    )
}

/** Below this a learned weight rounds to 0% on screen. */
private const val MIN_SHOWN_WEIGHT = 0.005f
