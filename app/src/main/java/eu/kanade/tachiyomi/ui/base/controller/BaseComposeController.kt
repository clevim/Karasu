package eu.kanade.tachiyomi.ui.base.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalDialogHostState
import eu.kanade.tachiyomi.util.compose.LocalRouter
import karasu.domain.DialogHostState
import karasu.presentation.theme.KarasuTheme

abstract class BaseComposeController(bundle: Bundle? = null) :
    BaseController(bundle) {

    override val shouldHideLegacyAppBar = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup,
        savedViewState: Bundle?
    ): View {
        setAppBarVisibility()
        return ComposeView(container.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dialogHostState = remember { DialogHostState() }
                KarasuTheme {
                    // Conductor leaves the controller underneath in place, so a screen that draws
                    // no background of its own shows through to it. Opaque here once instead of
                    // in every ScreenContent.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        CompositionLocalProvider(
                            LocalDialogHostState provides dialogHostState,
                            LocalBackPress provides router::handleBack,
                            LocalRouter provides router,
                        ) {
                            ScreenContent()
                        }
                    }
                }
            }
        }
    }

    @Composable
    abstract fun ScreenContent()
}
