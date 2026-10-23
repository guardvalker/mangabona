package eu.kanade.presentation.browse.components

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import mihon.app.di.appGraph
import tachiyomi.presentation.core.util.collectAsState

/**
 * Columns for a grid of covers, the same the library uses so every grid in the app looks the same size.
 */
@Composable
fun rememberLibraryGridCells(): GridCells {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val libraryPreferences = remember { context.appGraph.libraryPreferences }
    val columns by (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
        .collectAsState()
    return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
}
