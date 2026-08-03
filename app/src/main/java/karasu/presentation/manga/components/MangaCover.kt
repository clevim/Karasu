package karasu.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import eu.kanade.tachiyomi.R
import karasu.util.rememberResourceBitmapPainter
import karasu.domain.manga.models.MangaCover as MangaCoverModel

@Composable
fun MangaCover(
    data: Any?,
    modifier: Modifier = Modifier,
    ratio: Float? = null,
    contentDescription: String = "",
    shape: Shape = RoundedCornerShape(12.dp),
    contentScale: ContentScale = ContentScale.Crop,
    onClick: (() -> Unit)? = null,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    AsyncImage(
        // A source lists an entry before it says where the cover is, and the fetcher answers a
        // blank url with "Invalid image". That is not known yet, not broken, so it gets the
        // placeholder — the grid layouts have no url guard of their own the way the list one does.
        model = data?.takeUnless { it is MangaCoverModel && it.url.isBlank() },
        placeholder = ColorPainter(Color(0x1F888888)),
        fallback = ColorPainter(Color(0x1F888888)),
        error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
        contentDescription = contentDescription,
        contentScale = contentScale,
        onLoading = { state -> onState?.invoke(state) },
        onSuccess = { state -> onState?.invoke(state) },
        onError = { state -> onState?.invoke(state) },
        modifier = modifier
            .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier)
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
    )
}

object MangaCoverRatio {
    val SQUARE = 1f / 1f
    val BOOK = 2f / 3f
}
