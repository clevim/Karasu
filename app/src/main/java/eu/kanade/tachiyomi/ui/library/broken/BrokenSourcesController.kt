package eu.kanade.tachiyomi.ui.library.broken

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.work.NetworkType
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.extension.ExtensionUpdateJob
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withIOContext
import karasu.domain.manga.failures.interactor.BrokenSource
import karasu.domain.manga.failures.interactor.GetBrokenSources
import karasu.i18n.MR
import karasu.presentation.library.BrokenSourcesScreen
import uy.kohesive.injekt.injectLazy

/**
 * Screen listing the sources the library can no longer rely on.
 *
 * Read-only apart from the migrate action, and recomputed on every entry rather than observed:
 * extensions do not change while this is open, and a stale answer here would send someone
 * migrating something that already works.
 */
class BrokenSourcesController : BaseComposeController() {

    private val getBrokenSources: GetBrokenSources by injectLazy()

    // Held on the controller rather than in the composition so coming back from migration shows
    // the previous list while the new one loads, instead of blanking.
    private var sources by mutableStateOf(emptyList<BrokenSource>())

    @Composable
    override fun ScreenContent() {
        LaunchedEffect(Unit) { reload() }

        BrokenSourcesScreen(
            sources = sources,
            contentPadding = PaddingValues(bottom = 32.dp),
            onMigrate = { source ->
                PreMigrationController.navigateToMigration(
                    skipPre = false,
                    router = router,
                    mangaIds = source.entries.map { it.mangaId },
                )
            },
            onUpdateExtensions = {
                val context = activity?.applicationContext ?: return@BrokenSourcesScreen
                ExtensionUpdateJob.runJobAgain(context, NetworkType.CONNECTED)
                activity?.toast(MR.strings.broken_sources_checking_extensions)
            },
            onRetryUpdate = {
                // A whole library update rather than just these entries: scoping the job needs a
                // category to hang the manga list off, and these span categories by definition.
                val context = activity?.applicationContext ?: return@BrokenSourcesScreen
                LibraryUpdateJob.startNow(context)
                activity?.toast(MR.strings.broken_sources_retrying)
            },
        )
    }

    override fun onChangeEnded(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeEnded(handler, type)
        // Migration pops straight back here, and whatever was migrated is no longer in the
        // library — a card still offering to migrate it is worse than no card at all. Costs one
        // more read than the initial load on entry, which is cheaper than reasoning about
        // whether Conductor rebuilt the view this time.
        if (type == ControllerChangeType.POP_ENTER) {
            viewScope.launchUI { reload() }
        }
    }

    private suspend fun reload() {
        sources = withIOContext { getBrokenSources.await() }
    }
}
