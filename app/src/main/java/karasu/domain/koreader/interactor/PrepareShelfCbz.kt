package karasu.domain.koreader.interactor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import java.io.File
import karasu.core.archive.ZipWriter
import karasu.core.archive.util.archiveReader
import karasu.domain.koreader.KoreaderPreferences
import kotlin.math.abs
import kotlin.math.ceil
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
 * Pages that are already page-shaped are copied byte for byte. Nothing is re-encoded unless it
 * was split, so a chapter of normal manga pages leaves here exactly as it arrived.
 */
class PrepareShelfCbz(
    private val context: Application,
    private val preferences: KoreaderPreferences,
) {

    /**
     * @return the CBZ to upload instead of [cbz], or null when the original is already fine.
     * The caller owns the returned file and should delete it once the upload is done.
     */
    suspend fun await(cbz: UniFile, chapterId: Long): UniFile? = withContext(Dispatchers.IO) {
        if (!preferences.optimizePages().get()) return@withContext null

        val work = File(context.cacheDir, "$WORK_DIR/$chapterId")
        work.deleteRecursively()
        work.mkdirs()

        try {
            var rewritten = false
            cbz.archiveReader(context).use { reader ->
                val names = reader.useEntries { entries ->
                    entries.filter { it.isFile }.map { it.name }.toList()
                }
                names.forEach { entry ->
                    val bytes = reader.getInputStream(entry)?.use { it.readBytes() }
                        ?: return@forEach
                    // Downloaded CBZs are flat, but an imported one may not be, and the shelf
                    // wants pages it can sort by name.
                    if (writePage(work, entry.substringAfterLast('/'), bytes)) rewritten = true
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

    /** @return true when [bytes] was rewritten rather than copied through untouched. */
    private fun writePage(work: File, name: String, bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight
        val maxWidth = preferences.deviceScreenWidth().get()
        val grayscale = preferences.grayscalePages().get()

        val tooTall = height >= width * SPLIT_ABOVE_RATIO
        val tooWide = maxWidth > 0 && width > maxWidth
        // Not an image at all (ComicInfo.xml), or already exactly what the device wants. Copying
        // it byte for byte is both the fastest and the highest-quality thing we can do.
        if (width <= 0 || height <= 0 || !(tooTall || tooWide || grayscale)) {
            File(work, name).writeBytes(bytes)
            return false
        }

        return try {
            val base = name.substringBeforeLast('.')
            if (tooTall) {
                splitPage(work, base, bytes, width, height, maxWidth, grayscale)
            } else {
                val page = decodeScaled(bytes, width, maxWidth)
                    ?: error("Could not decode $name")
                writeImage(File(work, "$base.jpg"), render(page, maxWidth, grayscale))
            }
            true
        } catch (e: Exception) {
            Logger.e(e) { "Failed to convert $name, keeping the original page" }
            val base = name.substringBeforeLast('.')
            work.listFiles()?.filter { it.name.startsWith(base) }?.forEach { it.delete() }
            File(work, name).writeBytes(bytes)
            false
        }
    }

    private fun splitPage(
        work: File,
        base: String,
        bytes: ByteArray,
        width: Int,
        height: Int,
        maxWidth: Int,
        grayscale: Boolean,
    ) {
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } ?: error("No region decoder for this image")

        try {
            val cuts = cutPoints(decoder, width, height)
            (listOf(0) + cuts + listOf(height)).zipWithNext().forEachIndexed { index, (top, bottom) ->
                val part = decoder.decodeRegion(Rect(0, top, width, bottom), BitmapFactory.Options())
                    ?: error("Region decoder returned nothing")
                writeImage(
                    File(work, "%s__%03d.jpg".format(base, index + 1)),
                    render(part, maxWidth, grayscale),
                )
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun writeImage(target: File, bitmap: Bitmap) {
        target.outputStream().use { out ->
            // ponytail: 95 rather than 100. The difference is invisible on e-ink and the file is
            // a fraction of the size, which is the part that travels over wifi.
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()
    }

    /** Decodes at the smallest power-of-two scale that still covers [maxWidth]. */
    private fun decodeScaled(bytes: ByteArray, width: Int, maxWidth: Int): Bitmap? {
        val options = BitmapFactory.Options()
        if (maxWidth > 0) {
            var sample = 1
            while (width / (sample * 2) >= maxWidth) sample *= 2
            options.inSampleSize = sample
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Fits a page to the device: down to its screen width, and grey if that is all it can show.
     *
     * Colour on a 16-level greyscale screen is nothing but bytes to transfer — JPEG spends real
     * space on chroma the device throws away — so dropping it is free quality-wise and not free
     * size-wise, which is the right way round.
     */
    private fun render(source: Bitmap, maxWidth: Int, grayscale: Boolean): Bitmap {
        val scale = if (maxWidth > 0 && source.width > maxWidth) {
            maxWidth.toFloat() / source.width
        } else {
            1f
        }
        if (scale == 1f && !grayscale) return source

        val target = Bitmap.createBitmap(
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            if (grayscale) {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
        }
        Canvas(target).drawBitmap(source, null, Rect(0, 0, target.width, target.height), paint)
        source.recycle()
        return target
    }

    /**
     * Where to cut a tall image, in source pixels, top to bottom.
     *
     * The even split is only a starting point: each cut is then nudged to the flattest row
     * nearby, which on a webtoon is the gutter between two panels.
     */
    private fun cutPoints(decoder: BitmapRegionDecoder, width: Int, height: Int): List<Int> {
        val cuts = evenCuts(width, height)
        val window = ((height / (cuts.size + 1)) * SEARCH_WINDOW).roundToInt().coerceAtLeast(1)
        return cuts.map { ideal -> findGutter(decoder, width, height, ideal, window) ?: ideal }
    }

    /**
     * The most uniform row within [window] pixels of [ideal], or null when nothing was readable.
     *
     * A gutter is a row where every pixel is the same colour, so rows are scored by how much
     * their pixels differ. The distance from [ideal] is added to that score, which keeps the cut
     * near where the pages should divide when the whole window is equally plain.
     */
    private fun findGutter(
        decoder: BitmapRegionDecoder,
        width: Int,
        height: Int,
        ideal: Int,
        window: Int,
    ): Int? {
        val top = (ideal - window).coerceAtLeast(0)
        val bottom = (ideal + window).coerceAtMost(height)
        if (bottom - top < SAMPLE_STEP * 2) return null

        val options = BitmapFactory.Options().apply { inSampleSize = SAMPLE_STEP }
        val strip = try {
            decoder.decodeRegion(Rect(0, top, width, bottom), options)
        } catch (e: Exception) {
            Logger.w(e) { "Could not scan for a gutter, cutting at the even split" }
            null
        } ?: return null

        return try {
            val columns = IntArray(strip.width)
            val spreads = IntArray(strip.height) { y ->
                strip.getPixels(columns, 0, strip.width, 0, y, strip.width, 1)
                var min = 255
                var max = 0
                for (pixel in columns) {
                    // Green alone tracks brightness closely enough to spot a flat row, and is a
                    // third of the work of a real luminance.
                    val value = (pixel shr 8) and 0xFF
                    if (value < min) min = value
                    if (value > max) max = value
                }
                max - min
            }
            val row = flattestRow(spreads)
            if (row < 0) null else (top + row * SAMPLE_STEP).coerceIn(top, bottom)
        } finally {
            strip.recycle()
        }
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
 * Only images taller than this many times their width are split. A normal page is about 1.5, a
 * double spread is under 1, so this leaves ordinary manga completely alone.
 */
private const val SPLIT_ABOVE_RATIO = 2.5f

/** Roughly the shape of an e-ink screen, which is what the pieces should end up as. */
private const val TARGET_PAGE_RATIO = 1.4f

/** How far either side of an even split a gutter is worth looking for. */
private const val SEARCH_WINDOW = 0.15f

/** Rows are scanned at 1/8 scale; a gutter is far thicker than eight pixels. */
private const val SAMPLE_STEP = 8

/** How much a row's flatness outweighs its distance from the even split. */
private const val SPREAD_WEIGHT = 40

/**
 * Where a tall image would be cut if panels didn't matter: evenly, into page-shaped pieces.
 *
 * Excludes 0 and [height], so a list of n cuts means n+1 pages.
 */
internal fun evenCuts(width: Int, height: Int): List<Int> {
    val target = (width * TARGET_PAGE_RATIO).roundToInt().coerceAtLeast(1)
    val parts = ceil(height.toDouble() / target).toInt().coerceAtLeast(2)
    val even = height / parts
    return (1 until parts).map { it * even }
}

/**
 * The flattest row in a scanned window — the gutter — or -1 when there is nothing to pick from.
 *
 * [spreads] holds how much each row's pixels differ from each other, so the smallest one is the
 * most uniform. Distance from the middle is added on top, which keeps the cut near the even split
 * when the whole window is equally plain instead of drifting to whichever edge scored first.
 */
internal fun flattestRow(spreads: IntArray): Int {
    if (spreads.isEmpty()) return -1
    val centre = spreads.size / 2f
    var best = -1
    var bestScore = Int.MAX_VALUE
    spreads.forEachIndexed { row, spread ->
        val score = spread * SPREAD_WEIGHT + abs(row - centre).toInt()
        if (score < bestScore) {
            bestScore = score
            best = row
        }
    }
    return best
}
