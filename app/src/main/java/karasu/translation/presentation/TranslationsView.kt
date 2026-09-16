package karasu.translation.presentation

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.isVisible
import karasu.translation.model.PageTranslation
import karasu.translation.model.TranslationBlock
import karasu.translation.model.luminance
import kotlinx.coroutines.flow.MutableStateFlow
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.Injekt
import karasu.translation.TranslationManager
import androidx.compose.runtime.key
import kotlin.math.max

/**
 * Overlay for the paged reader. The page image pans and zooms under it, so the whole overlay is
 * offset by the image's top-left corner and scaled by its zoom.
 */
class PagerTranslationsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val translation: PageTranslation = PageTranslation.EMPTY,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    val scaleState = MutableStateFlow(1f)
    val viewTopLeftState = MutableStateFlow(PointF())

    @Composable
    override fun Content() {
        val topLeft by viewTopLeftState.collectAsState()
        val scale by scaleState.collectAsState()
        Box(modifier = Modifier.absoluteOffset(topLeft.x.pxToDp(), topLeft.y.pxToDp())) {
            TranslationBlocks(translation, scale)
        }
    }

    fun show() { isVisible = true }
    fun hide() { isVisible = false }
}

/**
 * Overlay for the webtoon reader. Pages are laid out at the view's width with no independent
 * zoom, so the scale is just how much the page image was stretched to fit.
 */
class WebtoonTranslationsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val translation: PageTranslation = PageTranslation.EMPTY,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    @Composable
    override fun Content() {
        var size by remember { mutableStateOf(IntSize.Zero) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size = it },
        ) {
            if (size == IntSize.Zero || translation.imgWidth <= 0f) return@Box
            TranslationBlocks(translation, size.width / translation.imgWidth)
        }
    }

    fun show() { isVisible = true }
    fun hide() { isVisible = false }
}

@Composable
private fun TranslationBlocks(translation: PageTranslation, scale: Float) {
    // The blocks are edited in place, which Compose cannot see; the manager's revision is what
    // says "something changed", and reading it here is what makes the overlay redraw.
    val manager = remember { Injekt.get<TranslationManager>() }
    val revision by manager.revision.collectAsState()
    key(revision) {
        // Backgrounds first, so a box never covers the text of a neighbouring block.
        translation.blocks.forEach { BlockBackground(it, scale) }
        translation.blocks.forEach { BlockText(it, scale) }
    }
}

@Composable
private fun BlockBackground(block: TranslationBlock, scale: Float) {
    Box(
        modifier = Modifier
            .offset(block.paddedX(scale).pxToDp(), block.paddedY(scale).pxToDp())
            .requiredSize(block.paddedWidth(scale).pxToDp(), block.paddedHeight(scale).pxToDp())
            .rotate(block.uprightAngle())
            .background(Color(block.background), RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun BlockText(block: TranslationBlock, scale: Float) {
    // Black on a light page, white on a dark one. The fill matches the page now, so the lettering
    // has to follow it or a caption on a black gutter comes out black on black.
    val ink = if (luminance(block.background) > 0.5f) Color.Black else Color.White
    Box(
        modifier = Modifier
            .offset(block.paddedX(scale).pxToDp(), block.paddedY(scale).pxToDp())
            .requiredSize(block.paddedWidth(scale).pxToDp(), block.paddedHeight(scale).pxToDp())
            .rotate(block.uprightAngle()),
    ) {
        BasicText(
            // Comic lettering is set in caps, and the eye reads a lower-case overlay as a
            // subtitle pasted on the art rather than as the bubble's own text.
            text = block.translation.uppercase(),
            style = TextStyle(
                color = ink,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                // A bubble is narrow and translations bring long words. Without hyphenation one
                // word that does not fit forces the auto-size all the way down and the whole
                // bubble ends up unreadably small, which is the same problem comic-translate
                // solves with its own `hyphen_textwrap`. Android already ships the dictionaries.
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
            ),
            // Shrink the text until the translation fits the original bubble.
            autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 32.sp, stepSize = 1.sp),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * ML Kit's box hugs the glyphs; padding it by half a symbol puts the white box roughly where the
 * original lettering sat inside the bubble.
 */
private fun TranslationBlock.paddedX(scale: Float) = max((x - symWidth * PAD) * scale, 0f)
private fun TranslationBlock.paddedY(scale: Float) = max((y - symHeight * PAD) * scale, 0f)
private fun TranslationBlock.paddedWidth(scale: Float) = (width + symWidth * PAD * 2) * scale
private fun TranslationBlock.paddedHeight(scale: Float) = (height + symHeight * PAD * 2) * scale

/**
 * Padding around the glyph box, in glyphs.
 *
 * A bubble is always roomier than the lettering inside it, and the box handed back is the
 * lettering. Fitting the translation into the tighter box is what made the auto-size give up and
 * render 6pt text in a bubble with space to spare — the complaint manga-image-translator answers
 * with its `--manga2eng` renderer, which fits the balloon instead of the text line.
 */
private const val PAD = 0.4f

/** Vertical text comes back at ~90°; rotating the box by that would lay it on its side. */
private fun TranslationBlock.uprightAngle() = if (angle > 85f) 0f else angle

@Composable
private fun Float.pxToDp(): Dp = (this / LocalDensity.current.density).dp
