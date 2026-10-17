package eu.kanade.tachiyomi.ui.source.releases

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.bundleOf
import eu.kanade.tachiyomi.source.ReleasesSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.view.openInBrowser
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import karasu.domain.releases.Release
import karasu.domain.releases.ReleaseDetail
import karasu.domain.releases.ReleasesRepository
import karasu.domain.releases.searchNames
import karasu.presentation.releases.ReleaseDetailsScreen
import uy.kohesive.injekt.injectLazy

/**
 * The page of one entry of the "Releases" window. One request when it opens (the catalogue's
 * full record, remembered for a day) and one more only if the reader searches their sources.
 */
class ReleaseDetailsController(bundle: Bundle) : BaseComposeController(bundle) {

    constructor(key: String) : this(bundleOf(KEY to key))

    private val repository: ReleasesRepository by injectLazy()
    private val sourceManager: SourceManager by injectLazy()

    private val release: Release? by lazy {
        (sourceManager.get(ReleasesSource.ID) as? ReleasesSource)?.release(args.getString(KEY).orEmpty())
    }

    private var detail by mutableStateOf<ReleaseDetail?>(null)
    private var loading by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    private var portuguese by mutableStateOf(emptyList<String>())
    private var searching by mutableStateOf(false)

    @Composable
    override fun ScreenContent() {
        // Gone from the list since (a refresh dropped it): nothing to show, back to the list.
        val release = release ?: run {
            LaunchedEffect(Unit) { router.popController(this@ReleaseDetailsController) }
            return
        }
        LaunchedEffect(Unit) {
            if (detail != null) return@LaunchedEffect
            val loaded = withIOContext { runCatching { repository.detail(release) }.getOrNull() }
            detail = loaded
            failed = loaded == null
            loading = false
        }
        ReleaseDetailsScreen(
            release = release,
            detail = detail,
            loading = loading,
            failed = failed,
            portugueseNames = portuguese,
            searching = searching,
            onBack = { router.handleBack() },
            onSearch = { search(release) },
            onOpenLink = { openInBrowser(it) },
        )
    }

    /** The app's global search, with every name the series has to fall back on. */
    private fun search(release: Release) {
        searching = true
        viewScope.launchUI {
            if (portuguese.isEmpty()) portuguese = withIOContext { repository.portugueseNames(release) }
            searching = false
            val names = searchNames((detail?.release ?: release).names, portuguese)
            router.pushController(ReleaseSearchController(names.first(), names.drop(1)).withFadeTransaction())
        }
    }

    private companion object {
        const val KEY = "release"
    }
}
