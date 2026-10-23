package eu.kanade.tachiyomi.ui.browse.explore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.ExploreScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.exploreTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = metroViewModel<ExploreViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    return TabContent(
        titleRes = MR.strings.label_explore,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_webview_refresh),
                icon = MaterialSymbols.Rounded.Refresh,
                onClick = viewModel::refresh,
            ),
        ),
        content = { contentPadding, _ ->
            LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

            ExploreScreen(
                state = state,
                contentPadding = contentPadding,
                getManga = { viewModel.getManga(it) },
                onClickItem = { navigator.push(MangaScreen(it.id, true)) },
            )
        },
    )
}
