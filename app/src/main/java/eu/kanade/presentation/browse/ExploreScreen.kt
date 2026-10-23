package eu.kanade.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.browse.components.rememberLibraryGridCells
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.tachiyomi.ui.browse.explore.ExploreFilterOption
import eu.kanade.tachiyomi.ui.browse.explore.ExploreViewModel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun ExploreScreen(
    state: ExploreViewModel.State,
    contentPadding: PaddingValues,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickItem: (Manga) -> Unit,
) {
    when {
        state.isLoading -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text(
                    text = "${state.done} / ${state.total}",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        state.items.isEmpty() -> EmptyScreen(
            stringRes = MR.strings.no_results_found,
            modifier = Modifier.padding(contentPadding),
        )
        else -> {
            LazyVerticalGrid(
                columns = rememberLibraryGridCells(),
                contentPadding = contentPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
            ) {
                items(
                    items = state.items,
                    key = { "${it.source.id}-${it.manga.url}" },
                ) { entry ->
                    val manga by getManga(entry.manga)
                    MangaComfortableGridItem(
                        title = manga.title,
                        coverData = manga.asMangaCover(),
                        coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                        coverBadgeStart = { InLibraryBadge(enabled = manga.favorite) },
                        coverBadgeEnd = { Badge(text = entry.source.name) },
                        onClick = { onClickItem(manga) },
                        onLongClick = { onClickItem(manga) },
                    )
                }
            }
        }
    }
}

/**
 * Which extensions Explore shows. The ones marked 18+ start hidden.
 */
@Composable
fun ExploreFilterDialog(
    options: List<ExploreFilterOption>,
    onToggle: (ExploreFilterOption) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.action_filter)) },
        text = {
            LazyColumn {
                lazyItems(options, key = { it.pkgName }) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = option.isShown, onCheckedChange = { onToggle(option) })
                        Text(
                            text = option.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (option.isNsfw) {
                            Badge(text = "18+")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}
