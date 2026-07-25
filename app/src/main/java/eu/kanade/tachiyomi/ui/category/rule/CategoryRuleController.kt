package eu.kanade.tachiyomi.ui.category.rule

import android.os.Bundle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.withIOContext
import karasu.domain.category.CategoryRepository
import karasu.domain.category.interactor.ApplyCategoryRules
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.models.CategoryRule
import karasu.domain.category.models.CategoryTransition
import karasu.i18n.MR
import karasu.presentation.category.CategoryRuleScreen
import karasu.presentation.category.RuleTarget
import karasu.util.lang.getString
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.injectLazy

/**
 * Screen for the rules of one category.
 *
 * Edits are written as they are made — a rule is a handful of bytes of JSON, and a save button
 * would only add a way to lose work.
 */
class CategoryRuleController(bundle: Bundle) : BaseComposeController(bundle) {

    constructor(categoryId: Long, categoryName: String) : this(
        bundleOf(CATEGORY_ID to categoryId, CATEGORY_NAME to categoryName),
    )

    private val categoryId = args.getLong(CATEGORY_ID)
    private val categoryName = args.getString(CATEGORY_NAME).orEmpty()

    /** Set by [save] so leaving without editing does not re-file the whole library. */
    private var ruleChanged = false

    private val getCategories: GetCategories by injectLazy()
    private val categoryRepository: CategoryRepository by injectLazy()
    private val applyCategoryRules: ApplyCategoryRules by injectLazy()

    @Composable
    override fun ScreenContent() {
        var transitions by remember { mutableStateOf(emptyList<CategoryTransition>()) }
        var targets by remember { mutableStateOf(emptyList<RuleTarget>()) }
        var matchCount by remember { mutableStateOf<Int?>(null) }

        // Counting scans the whole library, and edits land a keystroke at a time, so the count
        // waits for the typing to stop and blanks out meanwhile rather than showing a stale number.
        LaunchedEffect(transitions) {
            matchCount = null
            if (transitions.isEmpty()) return@LaunchedEffect
            delay(PREVIEW_DEBOUNCE_MS)
            matchCount = withIOContext { applyCategoryRules.countMatches(categoryId, transitions) }
        }

        LaunchedEffect(Unit) {
            val categories = getCategories.await()
            targets = buildList {
                categories
                    .filter { it.id?.toLong() != categoryId }
                    .forEach { category ->
                        category.id?.let { add(RuleTarget(it.toLong(), category.name)) }
                    }
                // Sending an entry to the default category is how it leaves the graph. It goes
                // last so a new transition never defaults to un-filing the manga: the screen
                // seeds the target with the first entry, and that must not be the destructive one.
                add(RuleTarget(DEFAULT_CATEGORY, MR.strings.default_value.getString(activity!!)))
            }
            transitions = categories
                .firstOrNull { it.id?.toLong() == categoryId }
                ?.rule
                ?.let { runCatching { json.decodeFromString<CategoryRule>(it) }.getOrNull() }
                ?.transitions
                .orEmpty()
        }

        CategoryRuleScreen(
            categoryName = categoryName,
            transitions = transitions,
            targets = targets,
            matchCount = matchCount,
            contentPadding = PaddingValues(bottom = 32.dp),
            onChange = { updated ->
                transitions = updated
                save(updated)
            },
        )
    }

    /**
     * Applying moves manga between categories, so it waits until the rule is finished. Running it
     * per edit re-files the library against half-typed conditions: typing "30" into a day count
     * applies "3" first, sweeping everything read in the last three days.
     */
    override fun onDestroy() {
        super.onDestroy()
        if (!ruleChanged) return
        MainScope().launch { launchIO { applyCategoryRules.await() } }
    }

    private fun save(transitions: List<CategoryTransition>) {
        ruleChanged = true
        MainScope().launch {
            launchIO {
                val rule = transitions
                    .takeIf { it.isNotEmpty() }
                    ?.let { json.encodeToString(CategoryRule(it)) }
                categoryRepository.setRule(categoryId, rule)
            }
        }
    }

    private companion object {
        const val CATEGORY_ID = "category_id"
        const val CATEGORY_NAME = "category_name"
        const val DEFAULT_CATEGORY = 0L
        const val PREVIEW_DEBOUNCE_MS = 400L

        val json = Json { ignoreUnknownKeys = true }
    }
}
