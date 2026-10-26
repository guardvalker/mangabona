package eu.kanade.presentation.browse.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.ChapterCount
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.UnifiedEntry
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.UnifiedGroup
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

/**
 * Search results with one card per title, however many sources have it.
 */
@Composable
fun UnifiedSearchContent(
    groups: List<UnifiedGroup>,
    contentPadding: PaddingValues,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickGroup: (UnifiedGroup) -> Unit,
    canLoadMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
) {
    if (groups.isEmpty() && !canLoadMore) {
        EmptyScreen(
            stringRes = MR.strings.no_results_found,
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    LazyVerticalGrid(
        columns = rememberLibraryGridCells(),
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(items = groups, key = { it.key }) { group ->
            val manga by getManga(group.entries.first().manga)
            MangaComfortableGridItem(
                title = group.title,
                coverData = manga.asMangaCover(),
                coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                coverBadgeStart = { InLibraryBadge(enabled = manga.favorite) },
                coverBadgeEnd = {
                    if (group.entries.size > 1) Badge(text = "×${group.entries.size}")
                },
                onClick = { onClickGroup(group) },
                onLongClick = { onClickGroup(group) },
            )
        }
        if (canLoadMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    if (loadingMore) {
                        CircularProgressIndicator()
                    } else {
                        Button(onClick = onLoadMore) {
                            Text(text = stringResource(MR.strings.search_load_more))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lists the sources that have the title with how many chapters each one offers, and can open the fullest.
 */
@Composable
fun UnifiedSourceDialog(
    group: UnifiedGroup,
    chapterCounts: Map<Long, ChapterCount>,
    onSelect: (UnifiedEntry) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val best = group.entries
        .mapNotNull { entry -> (chapterCounts[entry.manga.id] as? ChapterCount.Count)?.let { entry to it.value } }
        .maxByOrNull { it.second }
        ?.first

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = group.title, maxLines = 2) },
        text = {
            LazyColumn {
                items(group.entries) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(entry) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${entry.source.name} (${LocaleHelper.getLocalizedDisplayName(entry.source.lang)})",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        when (val count = chapterCounts[entry.manga.id]) {
                            is ChapterCount.Count -> Text(
                                text = (if (entry == best) "★ " else "") +
                                    stringResource(MR.strings.unified_chapters, count.value),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (entry == best) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            ChapterCount.Failed -> Text(text = "—")
                            else -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { best?.let(onSelect) },
                enabled = best != null,
            ) {
                Text(text = stringResource(MR.strings.unified_open_best))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
