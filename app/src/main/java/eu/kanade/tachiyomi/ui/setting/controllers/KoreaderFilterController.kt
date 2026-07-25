package eu.kanade.tachiyomi.ui.setting.controllers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.util.system.withIOContext
import karasu.domain.category.models.RuleCondition
import karasu.domain.koreader.KoreaderPreferences
import karasu.domain.koreader.interactor.SyncKoreaderShelf
import karasu.presentation.koreader.KoreaderFilterScreen
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.injectLazy

/**
 * Editor for the extra conditions the KOReader shelf selects on.
 *
 * Saved as it is edited, like the category rule editor: a filter is a few bytes of JSON and the
 * next sync is the only thing that reads it, so a save button would only be a way to lose work.
 */
class KoreaderFilterController : BaseComposeController() {

    private val preferences: KoreaderPreferences by injectLazy()
    private val syncShelf: SyncKoreaderShelf by injectLazy()

    @Composable
    override fun ScreenContent() {
        var conditions by remember { mutableStateOf(load()) }
        var matchCount by remember { mutableStateOf<Int?>(null) }

        // Counting walks the library, and edits land a keystroke at a time, so it waits for the
        // typing to stop and blanks out meanwhile rather than showing a stale number.
        LaunchedEffect(conditions) {
            matchCount = null
            delay(PREVIEW_DEBOUNCE_MS)
            matchCount = withIOContext { syncShelf.countSelectedManga(conditions) }
        }

        KoreaderFilterScreen(
            conditions = conditions,
            matchCount = matchCount,
            contentPadding = PaddingValues(bottom = 32.dp),
            onChange = { updated ->
                conditions = updated
                save(updated)
            },
        )
    }

    private fun load(): List<RuleCondition> {
        val raw = preferences.selectionConditions().get().takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString<List<RuleCondition>>(raw) }.getOrDefault(emptyList())
    }

    private fun save(conditions: List<RuleCondition>) {
        // Blank rather than "[]", so "no filter" is one representation instead of two.
        preferences.selectionConditions()
            .set(if (conditions.isEmpty()) "" else json.encodeToString(conditions))
    }

    private companion object {
        const val PREVIEW_DEBOUNCE_MS = 400L

        val json = Json { ignoreUnknownKeys = true }
    }
}
