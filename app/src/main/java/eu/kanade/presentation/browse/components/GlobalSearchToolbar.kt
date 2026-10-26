package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.LibraryFilter
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchFilters
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.FilterList
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun GlobalSearchToolbar(
    searchQuery: String?,
    progress: Int,
    total: Int,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    hideSourceFilter: Boolean,
    sourceFilter: SourceFilter,
    onChangeSearchFilter: (SourceFilter) -> Unit,
    onlyShowHasResults: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    onToggleResults: () -> Unit = {},
    filters: SearchFilters? = null,
    availableTags: List<Pair<String, Int>> = emptyList(),
    onClickFilter: () -> Unit = {},
    detailsProgress: Pair<Int, Int>? = null,
    onChangeFilters: (SearchFilters) -> Unit = {},
) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        Box {
            SearchToolbar(
                searchQuery = searchQuery,
                onChangeSearchQuery = onChangeSearchQuery,
                onSearch = onSearch,
                onClickCloseSearch = navigateUp,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
            if (progress in 1..<total) {
                LinearProgressIndicator(
                    progress = { progress / total.toFloat() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                )
            }
        }

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // TODO: make this UX better; it only applies when triggering a new search
            if (!hideSourceFilter) {
                FilterChip(
                    selected = sourceFilter == SourceFilter.All,
                    onClick = { onChangeSearchFilter(SourceFilter.All) },
                    leadingIcon = {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.DoneAll,
                            contentDescription = null,
                            modifier = Modifier
                                .size(FilterChipDefaults.IconSize),
                        )
                    },
                    label = {
                        Text(text = stringResource(MR.strings.all))
                    },
                )

                VerticalDivider(modifier = Modifier.height(FilterChipDefaults.Height))
            }

            if (filters != null) {
                // Global search: filter the titles by status and tags
                FilterChip(
                    selected = filters.isActive,
                    onClick = onClickFilter,
                    leadingIcon = {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.FilterList,
                            contentDescription = null,
                            modifier = Modifier
                                .size(FilterChipDefaults.IconSize),
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(MR.strings.action_filter) +
                                if (filters.isActive) " (${filters.count})" else "",
                        )
                    },
                )

                // What is being filtered by, one tap to drop each
                filters.statuses.forEach { status ->
                    ActiveFilterChip(
                        label = statusLabel(status),
                        onClick = { onChangeFilters(filters.copy(statuses = filters.statuses - status)) },
                    )
                }
                filters.tags.forEach { tag ->
                    ActiveFilterChip(
                        label = availableTags.firstOrNull { it.first.lowercase() == tag }?.first ?: tag,
                        onClick = { onChangeFilters(filters.copy(tags = filters.tags - tag)) },
                    )
                }
                if (filters.library != LibraryFilter.Any) {
                    ActiveFilterChip(
                        label = libraryLabel(filters.library),
                        onClick = { onChangeFilters(filters.copy(library = LibraryFilter.Any)) },
                    )
                }

                if (detailsProgress != null) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(
                            MR.strings.search_filter_loading,
                            detailsProgress.first,
                            detailsProgress.second,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                FilterChip(
                    selected = onlyShowHasResults,
                    onClick = { onToggleResults() },
                    leadingIcon = {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.FilterList,
                            contentDescription = null,
                            modifier = Modifier
                                .size(FilterChipDefaults.IconSize),
                        )
                    },
                    label = {
                        Text(text = stringResource(MR.strings.has_results))
                    },
                )
            }
        }

        HorizontalDivider()
    }
}

@Composable
private fun ActiveFilterChip(label: String, onClick: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onClick,
        trailingIcon = {
            Icon(
                imageVector = MaterialSymbols.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
        label = { Text(text = label) },
    )
}

@Composable
internal fun statusLabel(status: Long): String = stringResource(
    when (status.toInt()) {
        SManga.ONGOING -> MR.strings.ongoing
        SManga.COMPLETED -> MR.strings.completed
        SManga.LICENSED -> MR.strings.licensed
        SManga.PUBLISHING_FINISHED -> MR.strings.publishing_finished
        SManga.CANCELLED -> MR.strings.cancelled
        SManga.ON_HIATUS -> MR.strings.on_hiatus
        else -> MR.strings.unknown_status
    },
)

@Composable
internal fun libraryLabel(filter: LibraryFilter): String = stringResource(
    when (filter) {
        LibraryFilter.Any -> MR.strings.all
        LibraryFilter.InLibrary -> MR.strings.in_library
        LibraryFilter.NotInLibrary -> MR.strings.not_in_library
    },
)
