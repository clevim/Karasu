package eu.kanade.tachiyomi.ui.library.broken

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.util.system.withIOContext
import karasu.domain.manga.failures.interactor.BrokenSource
import karasu.domain.manga.failures.interactor.GetBrokenSources
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

    @Composable
    override fun ScreenContent() {
        var sources by remember { mutableStateOf(emptyList<BrokenSource>()) }

        LaunchedEffect(Unit) {
            sources = withIOContext { getBrokenSources.await() }
        }

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
        )
    }
}
