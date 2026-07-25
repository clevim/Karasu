package karasu.presentation.koreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.pluralStringResource
import dev.icerock.moko.resources.compose.stringResource
import karasu.domain.category.models.RuleCondition
import karasu.domain.category.models.RuleField
import karasu.i18n.MR
import karasu.presentation.category.ConditionRow
import karasu.presentation.category.SectionLabel

/**
 * Narrows what gets sent to the KOReader shelf, on top of the chosen categories.
 *
 * Deliberately the same condition rows as the category rule editor: this is the same question
 * ("which entries do I mean?") asked for a different purpose, and inventing a second filter
 * vocabulary would mean two things to learn and two to keep working. What is missing here is a
 * destination — a filter selects, it does not move anything.
 */
@Composable
fun KoreaderFilterScreen(
    conditions: List<RuleCondition>,
    onChange: (List<RuleCondition>) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    matchCount: Int? = null,
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
                    text = stringResource(MR.strings.koreader_filter),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                Text(
                    text = stringResource(
                        if (conditions.isEmpty()) {
                            MR.strings.koreader_filter_empty
                        } else {
                            MR.strings.koreader_filter_explainer
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                matchCount?.let { count ->
                    Text(
                        text = pluralStringResource(
                            MR.plurals.koreader_filter_preview,
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

        if (conditions.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SectionLabel(stringResource(MR.strings.category_rule_if))
                        conditions.forEachIndexed { index, condition ->
                            if (index > 0) SectionLabel(stringResource(MR.strings.category_rule_and))
                            ConditionRow(
                                condition = condition,
                                onChange = { changed ->
                                    onChange(
                                        conditions.toMutableList().also { it[index] = changed },
                                    )
                                },
                                onDelete = {
                                    onChange(conditions.filterIndexed { i, _ -> i != index })
                                },
                            )
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { onChange(conditions + RuleCondition(RuleField.UNREAD)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(MR.strings.category_rule_add_condition),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
