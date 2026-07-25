package karasu.presentation.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.pluralStringResource
import dev.icerock.moko.resources.compose.stringResource
import karasu.domain.category.models.CategoryTransition
import karasu.domain.category.models.RuleCondition
import karasu.domain.category.models.RuleField
import karasu.domain.category.models.RuleOperator
import karasu.i18n.MR
import kotlinx.coroutines.launch

/** How much a switched-off rule fades, while staying legible and editable. */
private const val DISABLED_ALPHA = 0.4f

/**
 * Editor for the rules that move entries out of a category.
 *
 * A rule reads as one sentence — IF these conditions AND those THEN move to that category —
 * because that is exactly what it does. Conditions render themselves according to the field
 * they are on: a count gets a comparison and a number, a status gets a list of statuses, a
 * yes/no gets yes or no. Nothing asks for an operator that has only one sensible answer.
 */
@Composable
fun CategoryRuleScreen(
    categoryName: String,
    transitions: List<CategoryTransition>,
    targets: List<RuleTarget>,
    onChange: (List<CategoryTransition>) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    matchCount: Int? = null,
) {
    fun replace(index: Int, transition: CategoryTransition) =
        onChange(transitions.toMutableList().also { it[index] = transition })

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(MR.strings.category_rule_deleted)
    val undoLabel = stringResource(MR.strings.undo)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            // The screen has no app bar, so without this the category name draws under the status bar.
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(
                        text = categoryName,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    )
                    Text(
                        text = stringResource(
                            if (transitions.isEmpty()) MR.strings.category_rules_empty
                            else MR.strings.category_rules_explainer,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // Counted against the real library, so a rule can be judged before it is
                    // trusted to rearrange it. Null while the count is still being worked out.
                    matchCount?.let { count ->
                        Text(
                            text = pluralStringResource(
                                MR.plurals.category_rule_preview,
                                quantity = count,
                                count,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            itemsIndexed(transitions) { index, transition ->
                RuleCard(
                    position = index,
                    count = transitions.size,
                    transition = transition,
                    targets = targets,
                    onChange = { replace(index, it) },
                    onDelete = {
                        // Deleting throws away a rule that took real effort to write, and edits
                        // here save immediately, so the only way back is an explicit undo.
                        val previous = transitions
                        onChange(transitions.filterIndexed { i, _ -> i != index })
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = deletedMessage,
                                actionLabel = undoLabel,
                                duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) onChange(previous)
                        }
                    },
                    onMove = { to ->
                        onChange(
                            transitions.toMutableList().also {
                                it.add(to, it.removeAt(index))
                            },
                        )
                    },
                )
            }

            item {
                OutlinedButton(
                    onClick = {
                        onChange(
                            transitions + CategoryTransition(
                                target = targets.firstOrNull()?.id ?: 0L,
                            ),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(MR.strings.category_rule_add),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            // Same reason the list needs statusBarsPadding: no scaffold here, so the undo action
            // would otherwise sit under the navigation bar and be unreachable.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

/** A category a rule can send entries to. */
data class RuleTarget(val id: Long, val name: String)

@Composable
private fun RuleCard(
    position: Int,
    count: Int,
    transition: CategoryTransition,
    targets: List<RuleTarget>,
    onChange: (CategoryTransition) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            RuleHeader(
                position = position,
                count = count,
                enabled = transition.enabled,
                onToggle = { onChange(transition.copy(enabled = it)) },
                onDelete = onDelete,
                onMove = onMove,
            )

            // A disabled rule dims but stays fully editable, so it can be tuned before turning on.
            Column(modifier = Modifier.alpha(if (transition.enabled) 1f else DISABLED_ALPHA)) {
                SectionLabel(stringResource(MR.strings.category_rule_if))

                if (transition.conditions.isEmpty()) {
                    Text(
                        text = stringResource(MR.strings.category_rule_needs_condition),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                transition.conditions.forEachIndexed { index, condition ->
                    if (index > 0) SectionLabel(stringResource(MR.strings.category_rule_and))
                    ConditionRow(
                        condition = condition,
                        onChange = { changed ->
                            onChange(
                                transition.copy(
                                    conditions = transition.conditions.toMutableList()
                                        .also { it[index] = changed },
                                ),
                            )
                        },
                        onDelete = {
                            onChange(
                                transition.copy(
                                    conditions = transition.conditions
                                        .filterIndexed { i, _ -> i != index },
                                ),
                            )
                        },
                    )
                }

                TextButton(
                    onClick = {
                        onChange(
                            transition.copy(
                                conditions = transition.conditions + RuleCondition(RuleField.UNREAD),
                            ),
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(MR.strings.category_rule_add_condition),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

                SectionLabel(stringResource(MR.strings.category_rule_then))
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Picker(
                        label = targets.firstOrNull { it.id == transition.target }?.name.orEmpty(),
                        options = targets.map { it.name },
                        onSelect = { index -> onChange(transition.copy(target = targets[index].id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleHeader(
    position: Int,
    count: Int,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val toggleDescription = stringResource(MR.strings.category_rule_enabled)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
    ) {
        Text(
            text = stringResource(MR.strings.category_rule_number, position + 1),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier
                .padding(end = 4.dp)
                .semantics { contentDescription = toggleDescription },
        )
        IconButton(onClick = { onMove(position - 1) }, enabled = position > 0) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(MR.strings.category_rule_move_up),
            )
        }
        IconButton(onClick = { onMove(position + 1) }, enabled = position < count - 1) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(MR.strings.category_rule_move_down),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(MR.strings.category_rule_delete),
            )
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
internal fun ConditionRow(
    condition: RuleCondition,
    onChange: (RuleCondition) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // One FlowRow so the field, operator and value wrap onto the next line instead of
            // running off the right edge on a narrow screen.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Picker(
                    label = stringResource(condition.field.label()),
                    options = RuleField.entries.map { stringResource(it.label()) },
                    onSelect = { index ->
                        val field = RuleField.entries[index]
                        // Each shape has a different sensible default, and carrying the old
                        // value across would leave nonsense like "status is 20".
                        onChange(
                            RuleCondition(
                                field = field,
                                operator = if (field.isComparable()) condition.operator else RuleOperator.EQUAL,
                                value = if (field.isComparable()) condition.value else 1L,
                            ),
                        )
                    },
                )

                if (condition.field.isComparable()) {
                    Picker(
                        label = stringResource(condition.operator.label()),
                        options = RuleOperator.entries.map { stringResource(it.label()) },
                        onSelect = { onChange(condition.copy(operator = RuleOperator.entries[it])) },
                    )
                    NumberField(
                        value = condition.value,
                        onChange = { onChange(condition.copy(value = it)) },
                    )
                } else {
                    Text(
                        text = stringResource(RuleOperator.EQUAL.label()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val options = condition.field.choices()
                    Picker(
                        label = options.firstOrNull { it.second == condition.value }
                            ?.let { stringResource(it.first) }
                            .orEmpty(),
                        options = options.map { stringResource(it.first) },
                        onSelect = { onChange(condition.copy(value = options[it].second)) },
                    )
                }
            }

            // Both fields hide a deliberate exclusion that would otherwise look like a bug.
            val note = when (condition.field) {
                RuleField.DAYS_SINCE_READ -> MR.strings.rule_field_days_since_read_note
                RuleField.UPDATE_FAILURES -> MR.strings.rule_field_update_failures_note
                else -> null
            }
            note?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun NumberField(value: Long, onChange: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter { it.isDigit() }.take(6)
            text.toLongOrNull()?.let(onChange)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.size(width = 110.dp, height = 56.dp),
    )
}

/** A dropdown chip that reads as part of the sentence instead of as a form field. */
@Composable
internal fun Picker(label: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    // secondaryContainer/onSecondaryContainer is the tonal pair meant for tappable chips: it keeps
    // contrast in both themes, where surfaceVariant text sank into the card behind it.
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .background(color = MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}
