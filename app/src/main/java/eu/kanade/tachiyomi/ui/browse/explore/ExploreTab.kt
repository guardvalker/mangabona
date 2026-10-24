package eu.kanade.tachiyomi.ui.browse.explore

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.ExploreFilterDialog
import eu.kanade.presentation.browse.ExploreScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.launch
import mihon.icons.custommaterialsymbols.CustomMaterialSymbols
import mihon.icons.custommaterialsymbols.rounded.ArrowsPointingIn
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.core.common.i18n.stringResource as contextString
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.exploreTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = metroViewModel<ExploreViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showFilter by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    return TabContent(
        titleRes = MR.strings.label_explore,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.explore_random),
                icon = CustomMaterialSymbols.Rounded.ArrowsPointingIn,
                enabled = !state.isLoading && !state.isPicking,
                onClick = {
                    scope.launch {
                        val manga = viewModel.pickRandom()
                        if (manga != null) {
                            navigator.push(MangaScreen(manga.id, true))
                        } else {
                            Toast.makeText(context, context.contextString(MR.strings.explore_random_none), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = MaterialSymbols.Rounded.FilterList,
                onClick = { showFilter = true },
            ),
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

            if (showFilter) {
                ExploreFilterDialog(
                    options = state.filterOptions,
                    onToggle = viewModel::toggleExtension,
                    onDismissRequest = {
                        showFilter = false
                        viewModel.applyFilter()
                    },
                )
            }
        },
    )
}
