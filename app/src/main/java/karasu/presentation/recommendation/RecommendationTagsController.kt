package karasu.presentation.recommendation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.system.withUIContext
import karasu.domain.recommendation.GetTasteProfile
import karasu.domain.recommendation.RecommendationFeedbackStore
import uy.kohesive.injekt.injectLazy

/**
 * The taste profile, open for editing.
 *
 * Every change is written as it is made: an override is one number, and a save button would only
 * add a way to lose it.
 */
class RecommendationTagsController : BaseComposeController() {

    private val getTasteProfile: GetTasteProfile by injectLazy()
    private val feedback: RecommendationFeedbackStore by injectLazy()

    private var learned by mutableStateOf<Map<String, Float>?>(null)
    private var support by mutableStateOf(emptyMap<String, Int>())
    private var overrides by mutableStateOf(emptyMap<String, Float>())
    private var verdicts by mutableIntStateOf(0)
    private var banned by mutableIntStateOf(0)
    private var merges by mutableIntStateOf(0)

    @Composable
    override fun ScreenContent() {
        LaunchedEffect(Unit) {
            overrides = getTasteProfile.overrides()
            verdicts = feedback.all().size
            banned = getTasteProfile.banned().size
            merges = getTasteProfile.merges().size
            val profile = withIOContext { getTasteProfile.awaitLearned() }
            support = profile.support
            learned = profile.tags
        }
        RecommendationTagsScreen(
            learned = learned,
            support = support,
            overrides = overrides,
            onChange = { changed ->
                overrides = changed
                getTasteProfile.setOverrides(changed)
            },
            verdicts = verdicts,
            onForgetVerdicts = {
                feedback.clear()
                verdicts = 0
            },
            onBan = { tag ->
                getTasteProfile.ban(tag)
                banned += 1
                learned = learned?.filterKeys { !it.equals(tag, ignoreCase = true) }
            },
            banned = banned,
            onRestoreBanned = {
                getTasteProfile.unbanAll()
                banned = 0
                reload()
            },
            merges = merges,
            onMerge = { tags, into ->
                getTasteProfile.merge(tags, into)
                overrides = getTasteProfile.overrides()
                merges = getTasteProfile.merges().size
                reload()
            },
            onUnmergeAll = {
                getTasteProfile.unmergeAll()
                merges = 0
                reload()
            },
        )
    }

    private fun reload() {
        viewScope.launchIO {
            val profile = getTasteProfile.awaitLearned()
            withUIContext {
                support = profile.support
                learned = profile.tags
            }
        }
    }
}
