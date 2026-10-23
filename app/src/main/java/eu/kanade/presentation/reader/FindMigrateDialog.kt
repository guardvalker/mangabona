package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.HopRow
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.HopState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun FindMigrateDialog(
    state: HopState,
    onDismissRequest: () -> Unit,
    onClickRow: (HopRow) -> Unit,
    onClickMigrate: (HopRow) -> Unit,
    onConfirmMigrate: (HopRow) -> Unit,
    onDismissMigrate: () -> Unit,
) {
    val hasLibraryEntry = state.rows.any { it.manga.favorite }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.action_find_and_migrate)) },
        text = {
            Column {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    // Sources that have the chapter first, the others stay at the bottom as information
                    items(state.rows.sortedBy { it.chapter == null && !it.isCurrent }) { row ->
                        val enabled = row.chapter != null && !row.isCurrent
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) { onClickRow(row) }
                                .padding(vertical = 8.dp)
                                .alpha(if (row.chapter == null) 0.5f else 1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.sourceName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = when {
                                        row.isCurrent -> stringResource(MR.strings.find_and_migrate_current)
                                        row.chapter == null -> stringResource(MR.strings.find_and_migrate_no_chapter)
                                        else -> "${row.manga.title} · ${row.chapter.name}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (hasLibraryEntry && !row.manga.favorite && row.chapter != null) {
                                TextButton(onClick = { onClickMigrate(row) }) {
                                    Text(text = stringResource(MR.strings.migrate))
                                }
                            }
                        }
                    }
                }
                if (state.searching) {
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(MR.strings.find_and_migrate_searching),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(text = stringResource(MR.strings.action_cancel)) }
        },
    )

    state.migrateConfirm?.let { row ->
        AlertDialog(
            onDismissRequest = onDismissMigrate,
            text = { Text(text = stringResource(MR.strings.find_and_migrate_confirm, row.sourceName)) },
            confirmButton = {
                TextButton(onClick = { onConfirmMigrate(row) }) {
                    Text(text = stringResource(MR.strings.migrate))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissMigrate) { Text(text = stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}
