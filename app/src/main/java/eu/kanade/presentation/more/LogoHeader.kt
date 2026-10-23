package eu.kanade.presentation.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import kotlin.math.roundToInt

private const val LOGO_SOURCE_WIDTH = 64
private const val LOGO_SOURCE_HEIGHT = 78
private val LOGO_HEIGHT = 96.dp

@Composable
fun LogoHeader(
    iconPadding: PaddingValues = PaddingValues(),
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Pixel art, so it's drawn at a whole multiple of its size to keep the pixels sharp
        val density = LocalDensity.current
        val scale = with(density) { (LOGO_HEIGHT.toPx() / LOGO_SOURCE_HEIGHT).roundToInt().coerceAtLeast(1) }
        Image(
            bitmap = ImageBitmap.imageResource(R.drawable.honguito),
            contentDescription = null,
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .padding(iconPadding)
                .size(
                    width = with(density) { (LOGO_SOURCE_WIDTH * scale).toDp() },
                    height = with(density) { (LOGO_SOURCE_HEIGHT * scale).toDp() },
                ),
        )

        HorizontalDivider()
    }
}
