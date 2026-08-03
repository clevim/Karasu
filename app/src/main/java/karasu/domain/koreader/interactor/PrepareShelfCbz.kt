package karasu.domain.koreader.interactor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.system.ImageUtil
import java.io.File
import karasu.core.archive.ZipWriter
import karasu.core.archive.util.archiveReader
import karasu.domain.koreader.KoreaderPreferences
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rewrites a chapter's CBZ into pages an e-reader can actually show.
 *
 * A webtoon chapter is one image tens of thousands of pixels tall. KOReader fits a page to the
 * screen, so that single image arrives as one unreadable smear — the "low quality" is the device
 * scaling the whole strip down, not the file. Splitting it into page-shaped pieces is what makes
 * it legible, and cutting on a gutter instead of at an arithmetic offset is what keeps the cut
 * from landing across someone's face.
 *
 * On top of that, and following what [KCC](https://github.com/ciromattia/kcc) does for the same
 * screens: dead margins are cropped away, a double-page spread becomes two pages in reading order,
 * a washed-out scan gets its contrast stretched, and everything is fitted to the whole screen
 * rather than only its width. Output pages are renumbered from one, so page order does not depend
 * on how a given reader chooses to sort an archive.
 *
 * Pages that need none of that are copied byte for byte, and a chapter where that is true of every
 * page is sent exactly as it arrived.
 */
class PrepareShelfCbz(
    private val context: Application,
    private val preferences: KoreaderPreferences,
) {

    /** Everything the page transforms need to know about where the pages are going. */
    private data class Target(
        val width: Int,
        val height: Int,
        val enhance: Boolean,
        val rightToLeft: Boolean,
    ) {
        /** The shape a cut page should come out as. */
        val pageRatio = if (width > 0) height.toFloat() / width else DEFAULT_PAGE_RATIO
    }

    /**
     * What was decided about a page before any of it was decoded at full size.
     *
     * [box] is the area worth keeping, in source pixels. [stretch] is the scale and offset that
     * pull the page's brightness range back out to full black and full white, or null to leave it.
     */
    private class Plan(val box: Rect, val stretch: FloatArray?)

    /** @return the number of pages written, and whether any of them was re-encoded. */
    private data class Written(val pages: Int, val rewritten: Boolean)

    /**
     * @return the CBZ to upload instead of [cbz], or null when the original is already fine.
     * The caller owns the returned file and should delete it once the upload is done.
     */
    suspend fun await(cbz: UniFile, chapterId: Long): UniFile? = withContext(Dispatchers.IO) {
        if (!preferences.optimizePages().get()) return@withContext null

        val width = preferences.deviceScreenWidth().get()
        val target = Target(
            width = width,
            height = if (width > 0) screenHeight(width) else 0,
            enhance = preferences.grayscalePages().get(),
            rightToLeft = preferences.rightToLeft().get(),
        )

        val work = File(context.cacheDir, "$WORK_DIR/$chapterId")
        work.deleteRecursively()
        work.mkdirs()

        try {
            var rewritten = false
            var written = 0
            cbz.archiveReader(context).use { reader ->
                val names = reader.useEntries { entries ->
                    entries.filter { it.isFile }.map { it.name }.toList()
                }.sorted()
                names.forEach { entry ->
                    val bytes = reader.getInputStream(entry)?.use { it.readBytes() }
                        ?: return@forEach
                    // Downloaded CBZs are flat, but an imported one may not be, and the shelf
                    // wants pages it can sort by name.
                    val name = entry.substringAfterLast('/')
                    if (!ImageUtil.isImage(name) { bytes.inputStream() }) {
                        // ComicInfo.xml and anything else along for the ride keeps its own name:
                        // renumbering it would make it a page.
                        File(work, name).writeBytes(bytes)
                        return@forEach
                    }
                    val result = writePage(work, written + 1, name, bytes, target)
                    written += result.pages
                    if (result.rewritten) rewritten = true
                }
            }

            // Nothing needed touching, so the original file is the better upload: no re-encoding,
            // no repacking, no temporary copy to clean up.
            if (!rewritten) return@withContext null
            zip(work, chapterId)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to prepare chapter $chapterId for the shelf, uploading it as is" }
            null
        } finally {
            work.deleteRecursively()
        }
    }

    private fun writePage(work: File, first: Int, name: String, bytes: ByteArray, target: Target): Written {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            // An image this build cannot decode still travels, and still takes a page number: the
            // device may well know the format even when we don't.
            File(work, pageName(first, name.substringAfterLast('.', "jpg"))).writeBytes(bytes)
            return Written(1, false)
        }

        return try {
            val plan = plan(bytes, width, height, target)
            val box = plan.box

            val tall = box.height() >= box.width() * SPLIT_ABOVE_RATIO
            val spread = box.width() >= box.height() * SPREAD_ABOVE_RATIO
            // Only width: a page taller than the screen is scrolled, not shrunk, so there is
            // nothing to re-encode it for.
            val oversized = target.width > 0 && box.width() > target.width
            val cropped = box.width() != width || box.height() != height

            when {
                tall -> Written(splitStrip(work, first, bytes, plan, target), true)
                spread -> Written(splitSpread(work, first, bytes, plan, target), true)
                cropped || oversized || target.enhance -> {
                    writeRegion(work, first, bytes, box, plan.stretch, target)
                    Written(1, true)
                }
                // Already exactly what the device wants. Copying it byte for byte is both the
                // fastest and the highest-quality thing we can do.
                else -> {
                    File(work, pageName(first, name.substringAfterLast('.', "jpg"))).writeBytes(bytes)
                    Written(1, false)
                }
            }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to convert $name, keeping the original page" }
            // A strip that failed halfway has already written pages, and leaving them behind would
            // put the rest of the chapter one number off. Everything this page produced goes.
            work.listFiles()
                ?.filter { (it.nameWithoutExtension.toIntOrNull() ?: 0) >= first }
                ?.forEach { it.delete() }
            File(work, pageName(first, name.substringAfterLast('.', "jpg"))).writeBytes(bytes)
            Written(1, false)
        }
    }

    /**
     * Decides what to do with a page from a thumbnail of it.
     *
     * Everything here — where the ink actually is, whether the page sits on white or black, how
     * far its brightness range falls short — reads the same off a few hundred pixels across as it
     * does off the full page, and reading it small is what keeps a 20000px strip from having to be
     * decoded whole before anything can be decided.
     */
    private fun plan(bytes: ByteArray, width: Int, height: Int, target: Target): Plan {
        var sample = 1
        while (width / (sample * 2) >= ANALYSIS_WIDTH) sample *= 2
        val thumb = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("Could not decode the page")

        return try {
            val pixels = IntArray(thumb.width * thumb.height)
            thumb.getPixels(pixels, 0, thumb.width, 0, 0, thumb.width, thumb.height)

            val box = contentBox(
                pixels,
                thumb.width,
                thumb.height,
                isLightBackground(pixels, thumb.width, thumb.height),
            )
            val scaleX = width.toFloat() / thumb.width
            val scaleY = height.toFloat() / thumb.height
            Plan(
                box = Rect(
                    (box.left * scaleX).toInt().coerceIn(0, width - 1),
                    (box.top * scaleY).toInt().coerceIn(0, height - 1),
                    ceil(box.right * scaleX).toInt().coerceIn(1, width),
                    ceil(box.bottom * scaleY).toInt().coerceIn(1, height),
                ),
                stretch = if (target.enhance) contrastStretch(pixels) else null,
            )
        } finally {
            thumb.recycle()
        }
    }

    /** Cuts a webtoon strip into page-shaped pieces, on the gutters between panels. */
    private fun splitStrip(work: File, first: Int, bytes: ByteArray, plan: Plan, target: Target): Int {
        val box = plan.box
        val decoder = newDecoder(bytes)
        try {
            val edges = listOf(box.top) + cutPoints(decoder, box, target) + listOf(box.bottom)
            edges.zipWithNext().forEachIndexed { index, (top, bottom) ->
                writeRegion(work, first + index, decoder, Rect(box.left, top, box.right, bottom), plan.stretch, target)
            }
            return edges.size - 1
        } finally {
            decoder.recycle()
        }
    }

    /**
     * Turns a landscape page into something a portrait screen can show.
     *
     * Up to [ROTATE_ABOVE_RATIO] it is a double-page spread and becomes two pages, the right one
     * first when the chapter reads right to left. Wider than that it is a panorama rather than two
     * pages, and halving it would cut a single drawing down the middle, so it is turned on its side
     * instead — the same call KCC makes at the same threshold.
     */
    private fun splitSpread(work: File, first: Int, bytes: ByteArray, plan: Plan, target: Target): Int {
        val box = plan.box
        val decoder = newDecoder(bytes)
        try {
            if (box.width() >= box.height() * ROTATE_ABOVE_RATIO) {
                writeRegion(work, first, decoder, box, plan.stretch, target, rotate = true)
                return 1
            }
            // The fold is near the middle, never exactly on it. Cutting on the seam instead of on
            // the arithmetic centre is what keeps a balloon that straddles the fold in one piece.
            val centre = box.left + box.width() / 2
            val window = (box.width() * SEAM_WINDOW).roundToInt().coerceAtLeast(1)
            val middle = (ImageUtil.findSeam(decoder, box, centre, window) ?: centre)
                .coerceIn(box.left + 1, box.right - 1)
            val left = Rect(box.left, box.top, middle, box.bottom)
            val right = Rect(middle, box.top, box.right, box.bottom)
            val order = if (target.rightToLeft) listOf(right, left) else listOf(left, right)
            order.forEachIndexed { index, half ->
                writeRegion(work, first + index, decoder, half, plan.stretch, target)
            }
            return 2
        } finally {
            decoder.recycle()
        }
    }

    private fun writeRegion(
        work: File,
        page: Int,
        bytes: ByteArray,
        box: Rect,
        stretch: FloatArray?,
        target: Target,
    ) {
        val decoder = newDecoder(bytes)
        try {
            writeRegion(work, page, decoder, box, stretch, target)
        } finally {
            decoder.recycle()
        }
    }

    private fun writeRegion(
        work: File,
        page: Int,
        decoder: BitmapRegionDecoder,
        box: Rect,
        stretch: FloatArray?,
        target: Target,
        rotate: Boolean = false,
    ) {
        // Decoding straight down towards the screen size keeps a 4000px scan from ever existing as
        // a 48MB bitmap; the remaining fraction is handled by the draw below.
        val options = BitmapFactory.Options().apply {
            // The screen edge this region is being fitted to, so a page turned on its side is not
            // sampled down against the short edge it is never going to be measured against.
            val limit = if (rotate) target.height else target.width
            if (limit > 0) {
                var sample = 1
                while (box.width() / (sample * 2) >= limit) sample *= 2
                inSampleSize = sample
            }
        }
        val region = decoder.decodeRegion(box, options) ?: error("Region decoder returned nothing")
        writeImage(File(work, pageName(page, "jpg")), render(region, stretch, target, rotate))
    }

    /**
     * Fits a page to the device: down to its screen, grey if that is all it can show, and with its
     * brightness pulled back out to the full range if the scan arrived flat.
     *
     * Colour on a 16-level greyscale screen is nothing but bytes to transfer — JPEG spends real
     * space on chroma the device throws away — so dropping it is free quality-wise and not free
     * size-wise, which is the right way round.
     *
     * ponytail: no quantising down to the device's 16 grey levels, which is the one thing KCC does
     * here that we don't. It pays off for its PNG and AZW3 output; posterising a JPEG only trades
     * smooth gradients for banding at roughly the same file size.
     */
    private fun render(source: Bitmap, stretch: FloatArray?, target: Target, rotate: Boolean): Bitmap {
        val scale = fitScale(source.width, source.height, target, rotate)
        if (scale == 1f && !target.enhance && !rotate) return source

        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)

        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            if (target.enhance) {
                val matrix = ColorMatrix().apply { setSaturation(0f) }
                if (stretch != null) {
                    val gain = stretch[0]
                    val bias = stretch[1]
                    matrix.postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                gain, 0f, 0f, 0f, bias,
                                0f, gain, 0f, 0f, bias,
                                0f, 0f, gain, 0f, bias,
                                0f, 0f, 0f, 1f, 0f,
                            ),
                        ),
                    )
                }
                colorFilter = ColorMatrixColorFilter(matrix)
            }
        }

        val fitted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(fitted).drawBitmap(source, null, Rect(0, 0, width, height), paint)
        source.recycle()
        if (!rotate) return fitted

        // Counter-clockwise, so the top of the panorama ends up on the left and the device is
        // turned the way KCC assumes.
        val turned = Bitmap.createBitmap(fitted, 0, 0, width, height, Matrix().apply { postRotate(-90f) }, true)
        if (turned != fitted) fitted.recycle()
        return turned
    }

    /**
     * How much a region has to shrink to land on the screen at full width. Never upscales.
     *
     * Width only, not the whole screen. A manga page is taller than an e-reader screen — 1.5:1
     * against the screen's 1.33:1 — so fitting its height leaves the page narrower than the panel
     * and throws away the horizontal pixels the device does have: a tenth of them at 1.5:1, a third
     * at 2:1. Those are exactly the pixels small lettering is made of, and no amount of zooming on
     * the device brings them back once they have been dropped here. The reader pans or scrolls the
     * remaining height, which is what it is for.
     *
     * Pieces cut from a strip are cut to the screen's own shape, so they fit either way: this only
     * ever changes the pages that were being letterboxed.
     */
    private fun fitScale(width: Int, height: Int, target: Target, rotate: Boolean): Float {
        if (target.width <= 0) return 1f
        // A page about to be turned on its side is measured against a screen on its side too. Its
        // short side then lands well inside the screen's width, since it took a ratio past
        // [ROTATE_ABOVE_RATIO] to be turned at all.
        val screenWidth = if (rotate) target.height else target.width
        return min(1f, screenWidth.toFloat() / width)
    }

    private fun writeImage(target: File, bitmap: Bitmap) {
        target.outputStream().use { out ->
            // ponytail: 95 rather than 100. The difference is invisible on e-ink and the file is
            // a fraction of the size, which is the part that travels over wifi.
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()
    }

    /**
     * Where to cut a tall image, in source pixels, top to bottom.
     *
     * The even split is only a starting point: each cut is then moved onto the nearest gutter, so
     * a page ends between two panels rather than through one.
     */
    private fun cutPoints(decoder: BitmapRegionDecoder, box: Rect, target: Target): List<Int> {
        val cuts = evenCuts(box.width(), box.height(), target.pageRatio).map { box.top + it }
        val page = box.height() / (cuts.size + 1)
        val window = (page * SEARCH_WINDOW).roundToInt().coerceAtLeast(1)
        var previous = box.top
        return cuts.map { ideal ->
            // Search windows overlap, so without a floor two ideal cuts can snap onto the same
            // gutter and the page between them comes out a few pixels tall. Nothing may be searched
            // — or cut — less than half a page after the cut before it.
            val floor = (previous + page / 2).coerceIn(box.top + 1, box.bottom - 1)
            val area = Rect(box.left, floor, box.right, box.bottom)
            val cut = (ImageUtil.findGutter(decoder, area, ideal.coerceAtLeast(floor), window) ?: ideal)
                .coerceIn(floor, box.bottom - 1)
            cut.also { previous = it }
        }
    }

    private fun newDecoder(bytes: ByteArray): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                ?: error("No region decoder for this image")
        }

    private fun zip(work: File, chapterId: Long): UniFile? {
        val target = File(context.cacheDir, "$WORK_DIR/$chapterId.cbz")
        target.delete()
        target.createNewFile()
        val file = UniFile.fromFile(target) ?: return null
        ZipWriter(context, file).use { writer ->
            work.listFiles()?.sortedBy { it.name }?.forEach { page ->
                UniFile.fromFile(page)?.let { writer.write(it) }
            }
        }
        return file
    }
}

private const val WORK_DIR = "koreader_pages"

/**
 * A rectangle in image pixels, right and bottom exclusive.
 *
 * ponytail: not [android.graphics.Rect]. The page analysis is the part worth testing and this is
 * what keeps it runnable off a device.
 */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Output pages are numbered from one, four digits wide: no chapter has ten thousand pages. */
private fun pageName(page: Int, extension: String) = "%04d.%s".format(page, extension)

/**
 * Screen height for a known screen width.
 *
 * ponytail: keyed off the width the user already picked rather than a second setting. Every e-ink
 * device worth listing has a distinct width, so the width names the device on its own.
 */
private fun screenHeight(width: Int): Int = when (width) {
    600 -> 800 // Kindle 8/10, Kobo Nia/Touch/Glo
    758 -> 1024 // Paperwhite 1/2
    1072 -> 1448 // Paperwhite 3/4, Kindle 11, Kobo Clara/Glo HD
    1236 -> 1648 // Paperwhite 5/Signature Edition
    1264 -> 1680 // Oasis 2/3, Colorsoft, Kobo Libra
    1272 -> 1696 // Paperwhite 6
    1404 -> 1872 // Kobo Elipsa/Aura ONE, reMarkable
    1440 -> 1920 // Kobo Sage/Forma
    1860 -> 2480 // Scribe
    1986 -> 2648 // Scribe 3, Scribe Colorsoft
    else -> (width * DEFAULT_PAGE_RATIO).roundToInt()
}

/**
 * Only images taller than this many times their width are split. A normal page is about 1.5, a
 * double spread is under 1, so this leaves ordinary manga completely alone.
 */
private const val SPLIT_ABOVE_RATIO = 2.5f

/**
 * A page this much wider than it is tall is a double-page spread rather than a page.
 *
 * KCC's number. Slightly over square on purpose: a spread is never exactly 2:1 once its margins
 * are cropped, and a page that is merely a little wide is not one.
 */
private const val SPREAD_ABOVE_RATIO = 1.16f

/** Past this a landscape page is one wide drawing, not two pages side by side. KCC's number. */
private const val ROTATE_ABOVE_RATIO = 1.8f

/** The shape to cut pages to when no device was chosen. */
private const val DEFAULT_PAGE_RATIO = 1.4f

/**
 * How far either side of an even split a gutter is worth looking for.
 *
 * Wide on purpose: a panel that runs long has its next gap well past the even split, and a page
 * that comes out short is a far smaller problem than a page that ends mid-sentence. The nearest
 * gutter always wins, so the width of the window is only ever paid on the pages that had nothing
 * closer — the ones that would otherwise have been cut through the art.
 *
 * Small screens are what settled the number. A 600x800 Kindle takes pieces a third shorter than a
 * Scribe does, so the same fraction is a much smaller window in pixels, and at 0.3 those were the
 * pages that ran out of gutters to snap to.
 */
private const val SEARCH_WINDOW = 0.45f

/** How far either side of the middle of a spread its fold is worth looking for. */
private const val SEAM_WINDOW = 0.08f

/** Pages are analysed at about this width; the answers do not get better with more pixels. */
private const val ANALYSIS_WIDTH = 512

/** Below this brightness a pixel counts as ink rather than page. KCC's threshold at power 1. */
private const val INK_THRESHOLD = 176

/** The fraction of each side treated as an edge, for the speckle check. */
private const val EDGE_BAND = 0.02f

/** An edge band with less ink than this is dirt, and is ignored when finding the content. */
private const val SPECKLE_FRACTION = 0.02f

/** Never take more than this off any one side, however empty it looks. */
private const val MAX_CROP = 0.1f

/** A page whose brightness range is already narrower than this was drawn that way. KCC's rule. */
private const val LOW_CONTRAST = 255 - 32 * 3

private fun luma(pixel: Int): Int =
    ((pixel shr 16 and 0xFF) * 77 + (pixel shr 8 and 0xFF) * 151 + (pixel and 0xFF) * 28) shr 8

/**
 * Where a tall image would be cut if panels didn't matter: evenly, into page-shaped pieces.
 *
 * Excludes 0 and [height], so a list of n cuts means n+1 pages.
 */
internal fun evenCuts(width: Int, height: Int, pageRatio: Float = DEFAULT_PAGE_RATIO): List<Int> {
    val target = (width * pageRatio).roundToInt().coerceAtLeast(1)
    val parts = ceil(height.toDouble() / target).toInt().coerceAtLeast(2)
    val even = height / parts
    return (1 until parts).map { it * even }
}

/**
 * Whether the page's empty space is white rather than black.
 *
 * Read off the four edges, which are margin on any page that has one. Everything downstream needs
 * this: on a dark page the ink is the *bright* pixels, and a crop that assumed otherwise would
 * throw away the drawing and keep the border.
 */
internal fun isLightBackground(pixels: IntArray, width: Int, height: Int): Boolean {
    val bandX = (width * EDGE_BAND).roundToInt().coerceAtLeast(1)
    val bandY = (height * EDGE_BAND).roundToInt().coerceAtLeast(1)
    var total = 0L
    var count = 0
    for (y in 0 until height) {
        val edgeRow = y < bandY || y >= height - bandY
        for (x in 0 until width) {
            if (!edgeRow && x >= bandX && x < width - bandX) continue
            total += luma(pixels[y * width + x])
            count++
        }
    }
    return count == 0 || total / count >= 128
}

/**
 * The part of the page worth sending: everything inside the outermost ink, plus its margins.
 *
 * Screen space is the scarcest thing an e-reader has, and a scan's border is pure waste — dropping
 * it is what lets the drawing itself be shown bigger. Two guards keep that from going wrong: a few
 * stray pixels along one edge, which is dirt rather than art, do not pin the crop to the full page;
 * and no side ever loses more than [MAX_CROP] of the page, because a wide gap can be composition.
 */
internal fun contentBox(pixels: IntArray, width: Int, height: Int, light: Boolean): Box {
    val ink = BooleanArray(width * height)
    for (i in pixels.indices) {
        val level = luma(pixels[i])
        ink[i] = if (light) level <= INK_THRESHOLD else level >= 255 - INK_THRESHOLD
    }

    val bandX = (width * EDGE_BAND).roundToInt().coerceAtLeast(1)
    val bandY = (height * EDGE_BAND).roundToInt().coerceAtLeast(1)
    listOf(
        Box(0, 0, width, bandY),
        Box(0, height - bandY, width, height),
        Box(0, 0, bandX, height),
        Box(width - bandX, 0, width, height),
    ).forEach { clearSpeckle(ink, width, it) }

    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (!ink[y * width + x]) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    // A page with no ink at all is a blank or a solid fill; there is nothing to crop towards.
    if (right < 0 || bottom < 0) return Box(0, 0, width, height)

    return Box(
        min(left, (width * MAX_CROP).roundToInt()),
        min(top, (height * MAX_CROP).roundToInt()),
        max(right + 1, (width * (1 - MAX_CROP)).roundToInt()),
        max(bottom + 1, (height * (1 - MAX_CROP)).roundToInt()),
    )
}

private fun clearSpeckle(ink: BooleanArray, width: Int, band: Box) {
    var count = 0
    for (y in band.top until band.bottom) {
        for (x in band.left until band.right) {
            if (ink[y * width + x]) count++
        }
    }
    val area = band.width * band.height
    if (count == 0 || area == 0 || count.toFloat() / area >= SPECKLE_FRACTION) return
    for (y in band.top until band.bottom) {
        for (x in band.left until band.right) {
            ink[y * width + x] = false
        }
    }
}

/**
 * The gain and bias that pull a flat page's brightness back out to full black and full white, or
 * null when it is already using the range — or so nearly not using it that it was meant to.
 *
 * A grey scan looks grey on a backlit phone and washed out on e-ink, where there is no backlight to
 * make up the difference. This is the single biggest readability win on the device after the size.
 */
internal fun contrastStretch(pixels: IntArray): FloatArray? {
    var min = 255
    var max = 0
    for (pixel in pixels) {
        val level = luma(pixel)
        if (level < min) min = level
        if (level > max) max = level
    }
    if (max - min < LOW_CONTRAST) return null
    if (min == 0 && max == 255) return null

    val gain = 255f / (max - min)
    return floatArrayOf(gain, -min * gain)
}
