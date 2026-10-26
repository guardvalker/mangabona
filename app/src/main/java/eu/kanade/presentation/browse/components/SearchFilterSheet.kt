package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchFilters
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private val STATUSES = listOf(
    SManga.ONGOING,
    SManga.COMPLETED,
    SManga.ON_HIATUS,
    SManga.PUBLISHING_FINISHED,
    SManga.CANCELLED,
    SManga.LICENSED,
    SManga.UNKNOWN,
).map { it.toLong() }

/**
 * Status and tags to narrow the global search results with. The tags are the ones the results have, with
 * how many titles carry each.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSheet(
    filters: SearchFilters,
    availableTags: List<Pair<String, Int>>,
    detailsProgress: Pair<Int, Int>?,
    onToggleStatus: (Long) -> Unit,
    onToggleTag: (String) -> Unit,
    onClear: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = stringResource(MR.strings.action_filter), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClear, enabled = filters.isActive) {
                    Text(text = stringResource(MR.strings.action_reset))
                }
            }

            if (detailsProgress != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
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
            }

            Text(text = stringResource(MR.strings.status), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                STATUSES.forEach { status ->
                    FilterChip(
                        selected = status in filters.statuses,
                        onClick = { onToggleStatus(status) },
                        label = { Text(text = statusLabel(status)) },
                    )
                }
            }

            Text(text = stringResource(MR.strings.search_tags), style = MaterialTheme.typography.titleSmall)
            if (availableTags.isEmpty()) {
                Text(
                    text = stringResource(MR.strings.search_tags_empty),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                    availableTags.forEach { (tag, count) ->
                        FilterChip(
                            selected = tag.lowercase() in filters.tags,
                            onClick = { onToggleTag(tag) },
                            label = { Text(text = "$tag · $count") },
                        )
                    }
                }
            }
        }
    }
}
