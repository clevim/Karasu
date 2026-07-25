package karasu.presentation.settings.screen.advanced

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import karasu.i18n.MR
import karasu.presentation.AppBarType
import karasu.presentation.component.preference.widget.TextPreferenceWidget
import karasu.presentation.core.enterAlwaysCollapsedAppBarScrollBehavior
import karasu.presentation.settings.SettingsScaffold
import karasu.util.Screen

class StoryBookScreen : Screen() {

    @Composable
    override fun Content() {
        val preferences = remember { Injekt.get<PreferencesHelper>() }
        val textFieldState = rememberTextFieldState()
        val useLargeAppBar by preferences.useLargeToolbar().collectAsState()
        val listState = rememberLazyListState()

        SettingsScaffold(
            title = stringResource(MR.strings.about),
            appBarType = if (useLargeAppBar) AppBarType.LARGE else AppBarType.SMALL,
            appBarScrollBehavior = if (useLargeAppBar) enterAlwaysCollapsedAppBarScrollBehavior(
                canScroll = { listState.canScrollForward || listState.canScrollBackward },
                isAtTop = { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 },
            ) else null,
            textFieldState = textFieldState,
            content = { contentPadding ->
                LazyColumn(
                    contentPadding = contentPadding,
                    state = listState,
                ) {
                    items(100) {
                        TextPreferenceWidget(
                            title = "Item #${it + 1}",
                            onPreferenceClick = {},
                        )
                    }
                }
            },
        )
    }
}
