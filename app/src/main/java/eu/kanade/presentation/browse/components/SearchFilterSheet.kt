package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.LibraryFilter
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
 * Library, status and tags to narrow the global search results with. The choices are only kept in the sheet
 * until Search is pressed, so nothing gets requested while picking them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSheet(
    filters: SearchFilters,
    availableTags: List<Pair<String, Int>>,
    extensions: List<Pair<String, String>>,
    onApply: (SearchFilters) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var draft by remember { mutableStateOf(filters) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { draft = SearchFilters() }, enabled = draft.isActive) {
                        Text(text = stringResource(MR.strings.action_reset))
                    }
                    Button(
                        onClick = {
                            onApply(draft)
                            onDismissRequest()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_search))
                    }
                }
            }

            Text(text = stringResource(MR.strings.label_library), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                LibraryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = draft.library == option,
                        onClick = { draft = draft.copy(library = option) },
                        label = { Text(text = libraryLabel(option)) },
                    )
                }
            }

            if (extensions.isNotEmpty()) {
                Text(text = stringResource(MR.strings.search_extensions), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                    extensions.forEach { (pkg, name) ->
                        FilterChip(
                            selected = pkg !in draft.excludedExtensions,
                            onClick = {
                                draft = draft.copy(
                                    excludedExtensions = if (pkg in draft.excludedExtensions) {
                                        draft.excludedExtensions - pkg
                                    } else {
                                        draft.excludedExtensions + pkg
                                    },
                                )
                            },
                            label = { Text(text = name.removePrefix("Tachiyomi: ")) },
                        )
                    }
                }
            }

            Text(text = stringResource(MR.strings.status), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                STATUSES.forEach { status ->
                    FilterChip(
                        selected = status in draft.statuses,
                        onClick = {
                            draft = draft.copy(
                                statuses = if (status in draft.statuses) draft.statuses - status else draft.statuses + status,
                            )
                        },
                        label = { Text(text = statusLabel(status)) },
                    )
                }
            }

            Text(text = stringResource(MR.strings.search_tags), style = MaterialTheme.typography.titleSmall)

            var query by remember { mutableStateOf("") }
            val typed = query.trim()
            val typedLower = typed.lowercase()
            val knownTags = availableTags.associate { it.first.lowercase() to it.first }

            fun toggleTag(tag: String) {
                val key = tag.lowercase()
                draft = draft.copy(tags = if (key in draft.tags) draft.tags - key else draft.tags + key)
                query = ""
            }

            // Typing narrows the known tags down; a word that isn't one of them can still be used as it is
            fun toggleTyped() {
                if (typed.isEmpty()) return
                val match = availableTags.firstOrNull { it.first.lowercase() == typedLower }
                    ?: availableTags.firstOrNull { it.first.lowercase().contains(typedLower) }
                toggleTag(match?.first ?: typed)
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(text = stringResource(MR.strings.search_tags_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { toggleTyped() }),
            )

            // Hundreds of tags can turn up, so only the most common ones show until something is typed
            val suggestions = availableTags
                .filter { it.first.lowercase() !in draft.tags && it.first.lowercase().contains(typedLower) }
                .take(if (typed.isEmpty()) 24 else 60)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                draft.tags.forEach { tag ->
                    FilterChip(
                        selected = true,
                        onClick = { toggleTag(tag) },
                        label = { Text(text = knownTags[tag] ?: tag) },
                    )
                }
                if (typed.isNotEmpty() && typedLower !in knownTags && typedLower !in draft.tags) {
                    FilterChip(
                        selected = false,
                        onClick = { toggleTyped() },
                        label = { Text(text = stringResource(MR.strings.search_tags_add, typed)) },
                    )
                }
                suggestions.forEach { (tag, _) ->
                    FilterChip(
                        selected = false,
                        onClick = { toggleTag(tag) },
                        label = { Text(text = tag) },
                    )
                }
            }
        }
    }
}
