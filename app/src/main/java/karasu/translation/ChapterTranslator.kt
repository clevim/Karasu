package karasu.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import co.touchlab.kermit.Logger
import com.google.mlkit.vision.common.InputImage
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.MergedSourceFallback
import eu.kanade.tachiyomi.source.SourcedPages
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.readingModeType
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.settings.ReadingModeType
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.withIOContext
import karasu.core.archive.util.archiveReader
import karasu.domain.translation.TranslationPreferences
import karasu.translation.data.CachedTranslator
import karasu.translation.data.TranslationCache
import karasu.translation.data.TranslationContexts
import karasu.translation.data.TranslationProvider
import karasu.translation.model.BalloonBox
import karasu.translation.model.PageTranslation
import karasu.translation.model.SeriesNotes
import karasu.translation.model.Progress
import karasu.translation.model.Translation
import karasu.translation.model.TranslationBlock
import karasu.translation.model.luminance
import karasu.translation.model.WHITE
import karasu.translation.model.dropDuplicateBlocks
import karasu.translation.model.mergeStackedBlocks
import karasu.translation.model.dropOverlappingBlocks
import karasu.translation.model.pageSlices
import karasu.translation.model.sliceScale
import karasu.translation.recognizer.OcrLanguage
import karasu.translation.translator.BOX_SCALE
import karasu.translation.translator.FoundText
import karasu.translation.translator.GeminiPageReader
import karasu.translation.translator.TranslationEngine
import karasu.translation.translator.inHalves
import java.io.ByteArrayOutputStream
import karasu.translation.recognizer.IntRect
import karasu.translation.recognizer.TextRecognizer
import karasu.translation.recognizer.findBalloon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import kotlin.math.min
import kotlin.math.max

/**
 * Runs OCR + translation over the pages of already downloaded chapters and writes the result
 * next to the download, as JSON keyed by page file name.
 *
 * ponytail: one chapter at a time. OCR is CPU bound and the API engine sends the whole chapter
 * in a single request, so a parallel queue would only add contention and rate limits.
 */
class ChapterTranslator(private val context: Context) {

    private val provider: TranslationProvider by injectLazy()
    private val downloadProvider: DownloadProvider by injectLazy()
    private val preferences: TranslationPreferences by injectLazy()
    private val cache: TranslationCache by injectLazy()
    private val contexts: TranslationContexts by injectLazy()
    private val readerPreferences: PreferencesHelper by injectLazy()
    private val chapterCache: ChapterCache by injectLazy()
    private val mergedSourceFallback: MergedSourceFallback by injectLazy()

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * The queue entry to watch, or null when the chapter is already translated.
     *
     * Suspending because the check is a SAF lookup: the chapter list's menu called this straight
     * from the tap, once per chapter, and "translate every downloaded chapter" of a long series
     * was hundreds of directory reads on the main thread.
     */
    suspend fun queueChapter(manga: Manga, chapter: Chapter, source: Source): Translation? {
        if (withIOContext { provider.findTranslationFile(chapter, manga, source) } != null) return null
        val fresh = Translation(source, manga, chapter).apply { status = Translation.State.QUEUE }
        // Checked and added in one update: the reader and the downloader queue from different
        // threads, and a check-then-add let the same chapter in twice.
        val queue = _queueState.updateAndGet { queue ->
            if (queue.any { it.chapter.id == chapter.id }) queue else queue + fresh
        }
        start()
        return queue.firstOrNull { it.chapter.id == chapter.id } ?: fresh
    }

    /**
     * Synchronized, here and in [pause] and [cancel]: [job] is read and replaced from whichever
     * thread queued, and two callers passing the check at once started two loops — which is two
     * chapters translated in parallel, and on a metered engine twice the requests.
     */
    @Synchronized
    fun start() {
        if (isRunning || _queueState.value.isEmpty()) return
        job = scope.launch {
            while (true) {
                val next = _queueState.value.firstOrNull { it.status == Translation.State.QUEUE } ?: break
                try {
                    translateChapter(next)
                } catch (e: CancellationException) {
                    next.status = Translation.State.QUEUE
                    throw e
                } catch (e: Throwable) {
                    Logger.e(e) { "Failed to translate ${next.chapter.name}" }
                    // Kept rather than only logged: "could not be translated" is the same message
                    // for a rate limit, a bad key and a reply that made no sense, and the only
                    // one of those the user can act on is the one the message does not say.
                    next.error = e.message ?: e::class.simpleName
                    next.status = Translation.State.ERROR
                }
                // Dropped even when it failed: a stuck entry would make every retry a no-op.
                removeFromQueue(next)
            }
        }
    }

    @Synchronized
    fun pause() {
        job?.cancel()
        job = null
    }

    fun clearQueue() {
        pause()
        _queueState.value = emptyList()
    }

    fun removeFromQueue(chapter: Chapter) = removeFromQueueIf { it.chapter.id == chapter.id }

    /**
     * Drops [chapter] from the queue, stopping it part way through if it is the one running.
     *
     * Unlike [pause], whatever else is queued carries on: cancelling the chapter you are waiting
     * on in the reader is not a statement about the rest of the queue.
     */
    @Synchronized
    fun cancel(chapter: Chapter) {
        val wasRunning = _queueState.value.any {
            it.chapter.id == chapter.id && it.status == Translation.State.TRANSLATING
        }
        removeFromQueue(chapter)
        if (wasRunning) {
            job?.cancel()
            job = null
            start()
        }
    }

    fun removeFromQueue(manga: Manga) = removeFromQueueIf { it.manga.id == manga.id }

    private fun removeFromQueue(translation: Translation) = removeFromQueueIf { it === translation }

    private fun removeFromQueueIf(predicate: (Translation) -> Boolean) {
        _queueState.update { queue -> queue.filterNot(predicate) }
    }

    private suspend fun translateChapter(translation: Translation) {
        translation.status = Translation.State.TRANSLATING
        // A retry of an entry that failed before must not carry the old reason into the toast.
        translation.error = null

        val fromLang = preferences.translateFrom().get()
        val toLang = preferences.translateTo().get()
        val chapterPath = downloadProvider.findChapterDir(
            translation.chapter,
            translation.manga,
            translation.source,
        )
        val mangaDir = provider.getMangaDir(translation.manga, translation.source)
            ?: error("Could not create the translations directory")

        val rtl = readsRightToLeft(translation.manga)

        val pageFiles = if (chapterPath != null) getChapterPages(chapterPath) else getOnlinePages(translation)
        translation.progress = Progress(pagesRead = 0, pages = pageFiles.size)

        val engine = preferences.engine().get()
        if (engine == TranslationEngine.GEMINI && preferences.geminiReadsPages().get()) {
            val pages = readWithGemini(translation, pageFiles, fromLang, toLang, rtl)
            writeTranslation(mangaDir, translation, pages)
            return
        }

        val pages = mutableMapOf<String, PageTranslation>()
        TextRecognizer(fromLang).use { recognizer ->
            pageFiles.forEachIndexed { index, (fileName, openStream) ->
                currentCoroutineContext().ensureActive()
                // Decoding from a byte array rather than the stream: archive entry streams are
                // not seekable, which the decoders need.
                val bytes = openStream().use(InputStream::readBytes)
                val pageTranslation = recognizePage(bytes, recognizer, rtl)
                if (pageTranslation != null && pageTranslation.blocks.isNotEmpty()) {
                    pages[fileName] = pageTranslation
                }
                translation.progress = translation.progress.copy(pagesRead = index + 1)
            }
        }

        if (pages.isNotEmpty()) {
            translation.progress = translation.progress.copy(phase = Progress.Phase.TRANSLATING)
            val context = contextFor(translation.manga.id)
            val translator = CachedTranslator(
                engine.build(preferences, fromLang, toLang, context),
                cache,
                engineKey(context),
            )
            translator.use { it.translate(pages) }
            rememberTerms(translation.manga.id, translator.learned)
        }

        writeTranslation(mangaDir, translation, pages)
    }

    private fun writeTranslation(mangaDir: UniFile, translation: Translation, pages: Map<String, PageTranslation>) {
        val file = mangaDir.createFile(provider.getTranslationFileName(translation.chapter))
            ?: error("Could not create the translation file")
        file.openOutputStream().use { Json.encodeToStream(pages.toMap(), it) }
        translation.status = Translation.State.TRANSLATED
    }

    /**
     * Reads and translates the chapter with Gemini looking at the pages, in place of ML Kit's OCR
     * followed by a text translation. See [GeminiPageReader] for why.
     *
     * Images go out [GeminiPageReader.IMAGES_PER_REQUEST] at a time, as the pages are read, so
     * only one batch of page bytes is held at once. What the model cannot tell — the colour behind
     * the text and the balloon around it — is still measured here from the pixels, exactly as the
     * OCR path does, so the overlay draws both the same way.
     */
    private suspend fun readWithGemini(
        translation: Translation,
        pageFiles: List<Pair<String, () -> InputStream>>,
        fromLang: OcrLanguage,
        toLang: String,
        rtl: Boolean,
    ): Map<String, PageTranslation> {
        val reader = GeminiPageReader(
            fromLang = fromLang,
            toLang = toLang,
            apiKey = preferences.engineApiKey(TranslationEngine.GEMINI).get(),
            modelName = preferences.engineModel(TranslationEngine.GEMINI).get(),
            context = contextFor(translation.manga.id),
            rtl = rtl,
        )
        val pages = linkedMapOf<String, PageTranslation>()
        val pending = mutableListOf<PendingSlice>()
        var failure: Throwable? = null
        var readAny = false

        suspend fun send(batch: List<PendingSlice>) {
            try {
                inHalves(batch) { part ->
                    val found = reader.read(part.map { it.jpeg })
                    readAny = true
                    part.zip(found).forEach { (slice, texts) ->
                        if (texts.isEmpty()) return@forEach
                        pages.getOrPut(slice.key) {
                            PageTranslation(imgWidth = slice.pageWidth.toFloat(), imgHeight = slice.pageHeight.toFloat())
                        }.blocks += blocksFor(slice, texts)
                    }
                }?.let { failure = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Before anything went through, it is the key, the model or the day's quota, and
                // every later batch would fail the same way: stop rather than spend requests.
                if (!readAny) throw e
                failure = e
                Logger.e(e) { "Gemini could not read a batch of ${batch.size} images" }
            }
            translation.progress = translation.progress.copy(pagesRead = batch.last().pageIndex + 1)
        }

        pageFiles.forEachIndexed { index, (fileName, openStream) ->
            currentCoroutineContext().ensureActive()
            val bytes = openStream().use(InputStream::readBytes)
            pending += slicesOf(index, fileName, bytes)
            while (pending.size >= GeminiPageReader.IMAGES_PER_REQUEST) {
                val batch = pending.take(GeminiPageReader.IMAGES_PER_REQUEST)
                pending.subList(0, batch.size).clear()
                send(batch)
            }
        }
        if (pending.isNotEmpty()) send(pending.toList())

        failure?.let { if (!readAny) throw it }
        pages.values.forEach { it.blocks = dropOverlappingBlocks(it.blocks) }
        rememberTerms(translation.manga.id, reader.learned)
        return pages
    }

    /**
     * One page as the images sent for it: whole, or in bands when it is a long strip. A page that
     * cannot be decoded is skipped rather than failing the chapter, as the OCR path does.
     */
    private fun slicesOf(index: Int, key: String, bytes: ByteArray): List<PendingSlice> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        return pageSlices(width, height).mapNotNull { band ->
            val slice = PendingSlice(key, index, bytes, width, height, band.first, band.last + 1)
            val bitmap = decodeSlice(slice) ?: return@mapNotNull null
            val jpeg = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            bitmap.recycle()
            slice.also { it.jpeg = jpeg }
        }
    }

    /**
     * The band at the size it is sent, decoded again after the reply to sample the colours: the
     * scaled bitmap is not kept across the request, sixteen of them would be a hundred megabytes.
     */
    private fun decodeSlice(slice: PendingSlice): Bitmap? {
        val targetWidth = max(1, (slice.pageWidth * slice.scale).toInt())
        val targetHeight = max(1, ((slice.bottom - slice.top) * slice.scale).toInt())
        // Decoded at the largest power-of-two reduction that is still at least the target size,
        // so a big page is never inflated to full resolution only to be shrunk.
        var sample = 1
        while (slice.pageWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        @Suppress("DEPRECATION")
        val region = runCatching {
            BitmapRegionDecoder.newInstance(slice.bytes, 0, slice.bytes.size, false)
        }.getOrNull()
        val decoded = if (region != null) {
            try {
                region.decodeRegion(Rect(0, slice.top, slice.pageWidth, slice.bottom), options)
            } finally {
                region.recycle()
            }
        } else {
            // Formats region decoding refuses: the whole page, then the band out of it.
            BitmapFactory.decodeByteArray(slice.bytes, 0, slice.bytes.size, options)?.let { whole ->
                val top = slice.top / sample
                val height = min(whole.height - top, (slice.bottom - slice.top) / sample)
                Bitmap.createBitmap(whole, 0, top, whole.width, max(1, height))
                    .also { if (it !== whole) whole.recycle() }
            }
        } ?: return null
        if (decoded.width == targetWidth && decoded.height == targetHeight) return decoded
        return Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    /** What Gemini found on one band, in page pixels, with the colours measured off the band. */
    private fun blocksFor(slice: PendingSlice, texts: List<FoundText>): List<TranslationBlock> {
        val bitmap = decodeSlice(slice) ?: return emptyList()
        try {
            return texts.map { found ->
                val (ymin, xmin, ymax, xmax) = found.box
                val bounds = Rect(
                    xmin * bitmap.width / BOX_SCALE,
                    ymin * bitmap.height / BOX_SCALE,
                    max(xmin + 1, xmax) * bitmap.width / BOX_SCALE,
                    max(ymin + 1, ymax) * bitmap.height / BOX_SCALE,
                )
                val background = sampleBackground(bitmap, bounds)
                val width = bounds.width() / slice.scale
                val height = bounds.height() / slice.scale
                // No glyph to measure, so a third of the box's short side stands in for one:
                // it is what the overlay pads by when no balloon was found.
                val em = min(width, height) / 3f
                TranslationBlock(
                    text = found.source,
                    translation = found.translation,
                    width = width,
                    height = height,
                    x = bounds.left / slice.scale,
                    y = bounds.top / slice.scale + slice.top,
                    symWidth = em,
                    symHeight = em,
                    angle = 0f,
                    background = background,
                    balloon = findBalloon(bitmap, bounds, background, slice.scale, slice.top),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Folds the terms this chapter settled on into the manga's notes, under a heading of their own.
     *
     * The notes go into the prompt of every later chapter, which is the whole point: chapter forty
     * then calls a character what chapter one called them. Kept apart from what the user wrote so
     * their own notes are never edited, appended only so a name already decided stays decided, and
     * capped because the whole thing is resent with every batch.
     */
    private suspend fun rememberTerms(mangaId: Long?, learned: Map<String, String>) {
        if (mangaId == null || learned.isEmpty()) return
        val merged = mergeLearnedTerms(contexts.get(mangaId), learned) ?: return
        contexts.set(mangaId, merged)
    }

    /** The user's notes on the manga, for engines that can read them. */
    suspend fun contextFor(mangaId: Long?): SeriesNotes {
        if (!preferences.engine().get().needsApiKey) return SeriesNotes()
        return parseSeriesNotes(mangaId?.let { contexts.get(it) }.orEmpty())
    }

    /**
     * What the cache files translations under. The model and the reader's notes are part of it for
     * the LLM engine: change either and the same line is, deliberately, a different translation.
     */
    fun engineKey(context: SeriesNotes): String {
        val engine = preferences.engine().get()
        return if (engine.needsApiKey) {
            // Only the reader's own notes. See [SeriesNotes] for why the glossary stays out.
            "${engine.name}:${preferences.engineModel(engine).get()}:${context.notes.hashCode()}"
        } else {
            engine.name
        }
    }

    /**
     * Whether the pages are read right to left, which is the order the bubbles have to be handed
     * to the translator in.
     *
     * Taken from the manga's own reader direction rather than guessed from the OCR language: the
     * user already sets it in the reader, an unset one falls back to the same global default the
     * reader uses, and it is right for the cases a language guess gets wrong — a Japanese manga
     * scanlated into English still reads right to left, a Chinese webtoon does not.
     */
    private fun readsRightToLeft(manga: Manga): Boolean {
        val mode = manga.readingModeType.takeIf { it != 0 } ?: readerPreferences.defaultReadingMode().get()
        return ReadingModeType.fromPreference(mode) == ReadingModeType.RIGHT_TO_LEFT
    }

    /**
     * Reads one page, in horizontal slices.
     *
     * A webtoon chapter is one image tens of thousands of pixels tall. Decoded whole it is
     * hundreds of megabytes, and it reaches the recognizer as a single image whose lettering,
     * once the detector has scaled it down to its working size, is a few pixels high — which is
     * why long strips came back as gibberish. Slicing bounds both.
     */
    private fun recognizePage(bytes: ByteArray, recognizer: TextRecognizer, rtl: Boolean): PageTranslation? {
        // The deprecated overload on purpose: the replacement was added in API 31 and this app
        // ships to API 26. Do not "fix" the warning without a version branch.
        @Suppress("DEPRECATION")
        val decoder = runCatching { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) }
            .getOrNull()
            ?: return recognizeWhole(bytes, recognizer, rtl)

        try {
            val width = decoder.width
            val height = decoder.height
            val scale = ocrScale(width)
            val blocks = mutableListOf<TranslationBlock>()

            var top = 0
            while (top < height) {
                val bottom = min(top + SLICE_HEIGHT, height)
                val slice = decoder.decodeRegion(Rect(0, top, width, bottom), null) ?: break
                blocks += readBlocks(slice, recognizer, yOffset = top, scale = scale)
                if (bottom == height) break
                // Overlap, so lettering sitting on a seam is read whole by the next slice
                // instead of being cut through the middle of its glyphs in both.
                top = bottom - SLICE_OVERLAP
            }

            return PageTranslation(
                blocks = mergeStackedBlocks(dropDuplicateBlocks(blocks), rtl),
                imgWidth = width.toFloat(),
                imgHeight = height.toFloat(),
            )
        } finally {
            decoder.recycle()
        }
    }

    /** For the formats region decoding refuses; the whole page at once, as before. */
    private fun recognizeWhole(bytes: ByteArray, recognizer: TextRecognizer, rtl: Boolean): PageTranslation? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val blocks = readBlocks(bitmap, recognizer, yOffset = 0, scale = ocrScale(width))
        return PageTranslation(
            blocks = mergeStackedBlocks(blocks, rtl),
            imgWidth = width.toFloat(),
            imgHeight = height.toFloat(),
        )
    }

    /**
     * Recognizes one slice and maps what it finds back into the page's own pixels. Recycles
     * [bitmap]; the caller must not touch it afterwards.
     */
    private fun readBlocks(
        bitmap: Bitmap,
        recognizer: TextRecognizer,
        yOffset: Int,
        scale: Float,
    ): List<TranslationBlock> {
        val scaled = if (scale > 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } else {
            bitmap
        }
        // Everything that reads pixels has to happen before the recycle, [sampleBackground]
        // included — a recycled bitmap throws on getPixel, and this runs once per page, so it
        // failed every page of every chapter rather than degrading.
        try {
            return recognizer.recognize(InputImage.fromBitmap(scaled, 0)).textBlocks
                // At least two letters: a block of pure punctuation or digits translates to noise
                // and still paints a box over the art.
                .filter { it.boundingBox != null && it.text.count(Char::isLetter) > 1 }
                .mapNotNull { block ->
                    val bounds = block.boundingBox!!
                    val symbol = block.lines.firstOrNull()?.elements?.firstOrNull()
                        ?.symbols?.firstOrNull()?.boundingBox ?: return@mapNotNull null
                    // Sampled once and used twice: it is the fill the overlay paints, and the
                    // colour the balloon walk measures against.
                    val background = sampleBackground(scaled, bounds)
                    TranslationBlock(
                        text = block.text,
                        width = bounds.width() / scale,
                        height = bounds.height() / scale,
                        x = bounds.left / scale,
                        y = bounds.top / scale + yOffset,
                        symWidth = symbol.width() / scale,
                        symHeight = symbol.height() / scale,
                        angle = block.lines.first().angle,
                        background = background,
                        balloon = findBalloon(scaled, bounds, background, scale, yOffset),
                    )
                }
        } finally {
            scaled.recycle()
        }
    }

    /**
     * The page's own colour immediately around a text box.
     *
     * The overlay used to stamp white behind every translation, which is right on a white bubble
     * and wrong everywhere else: a caption on a black gutter, a dark panel or a screentone got a
     * bright rectangle punched into the art. Sampled here because this is the last place the
     * page's pixels exist — the reader decodes the page again for itself and never sees this
     * bitmap, which is recycled a few lines below.
     *
     * The same pixels also give the balloon itself — see [findBalloon].
     */
    private fun sampleBackground(bitmap: Bitmap, bounds: Rect): Int {
        val margin = max(2, bounds.height() / 4)
        val left = bounds.left - margin
        val right = bounds.right + margin
        val top = bounds.top - margin
        val bottom = bounds.bottom + margin
        val colors = listOf(
            bounds.centerX() to top, bounds.centerX() to bottom,
            left to bounds.centerY(), right to bounds.centerY(),
            left to top, right to top, left to bottom, right to bottom,
        )
            .filter { (x, y) -> x in 0 until bitmap.width && y in 0 until bitmap.height }
            .map { (x, y) -> bitmap.getPixel(x, y) }
        // Median by brightness: one sample that landed on the bubble outline or on a neighbouring
        // glyph cannot drag the fill away from what the page actually is.
        return colors.sortedBy(::luminance).getOrNull(colors.size / 2) ?: WHITE
    }

    /**
     * The balloon around a text box, in page coordinates, or null when there is none to find.
     *
     * Runs here for the same reason the background is sampled here: this is the last moment the
     * page's pixels exist. The answer is stored with the translation, so the reader pays nothing
     * for it and a page translated once never looks for its balloons again.
     */
    private fun findBalloon(
        bitmap: Bitmap,
        bounds: Rect,
        background: Int,
        scale: Float,
        yOffset: Int,
    ): BalloonBox? {
        val found = findBalloon(
            width = bitmap.width,
            height = bitmap.height,
            pixelAt = bitmap::getPixel,
            text = IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            fill = background,
        ) ?: return null
        return BalloonBox(
            x = found.left / scale,
            y = found.top / scale + yOffset,
            width = found.width / scale,
            height = found.height / scale,
        )
    }

    /**
     * Upscales pages saved too small. ML Kit needs lettering of a reasonable pixel size, and old
     * scans routinely come in under it — enlarging is the only lever available once the detail
     * is already gone, but it does recover text the detector otherwise skips entirely.
     */
    private fun ocrScale(width: Int): Float =
        if (width >= MIN_OCR_WIDTH) 1f else min(MAX_OCR_SCALE, MIN_OCR_WIDTH.toFloat() / width)


    /** Page file name to a stream factory, in the same order the reader will show them. */
    /**
     * The pages of a chapter that was never downloaded, through the reader's image cache.
     *
     * Keyed by position rather than file name, since there is no file: `page-0`, `page-1`… The
     * loaders look a page up by both, so the translation survives a later download too. Images
     * already in the cache from reading are reused; the rest are fetched and cached the same way
     * the reader would, so this costs the source nothing the reader would not have.
     */
    private suspend fun getOnlinePages(translation: Translation): List<Pair<String, () -> InputStream>> {
        val primary = translation.source as? HttpSource ?: error("Chapter is not downloaded")
        // A chapter borrowed from a merged source is served by that source, with its urls, headers
        // and client — the same resolution the reader and the downloader do before fetching pages.
        // Without it the translator asks the wrong source and translates nothing.
        val served = translation.manga.id
            ?.let { mergedSourceFallback.getPages(it, translation.chapter, primary) }
            ?: SourcedPages(primary, primary.getPageList(translation.chapter))
        val source = served.source
        return served.pages.mapIndexed { index, page ->
            val url = page.imageUrl ?: source.getImageUrl(page).also { page.imageUrl = it }
            if (!chapterCache.isImageInCache(url)) {
                chapterCache.putImageToCache(url, source.getImage(page))
            }
            onlinePageKey(index) to { chapterCache.getImageFile(url).inputStream() }
        }
    }

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> {
        if (chapterPath.isFile) {
            val reader = chapterPath.archiveReader(context)
            return reader.useEntries { entries ->
                entries
                    .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                    .map { entry -> entry.name to { reader.getInputStream(entry.name)!! } }
                    .toList()
            }
        }
        return chapterPath.listFiles().orEmpty()
            .filter { ImageUtil.isImage(it.name) }
            .sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
            .map { file -> file.name!! to { file.openInputStream() } }
    }

    companion object {

        /**
         * Splits the notes the user wrote from the ones the translator learned. Anything after it
         * is rewritten on every chapter, so nothing the user types below it survives.
         */
        internal const val LEARNED_HEADING = "--- learned while translating ---"

        /** Resent with every batch of every chapter, so it cannot be allowed to grow forever. */
        internal const val MAX_LEARNED_TERMS = 40
        /** The key a page translated without a download sits under: its position in the chapter. */
        fun onlinePageKey(index: Int) = "page-$index"

        /**
         * Slice height in the page's own pixels. Set above any ordinary manga page on purpose:
         * a page that already fits goes through in one piece, exactly as before, and only long
         * strips get seams — every seam is a chance for two bubbles to be stitched wrongly.
         */
        private const val SLICE_HEIGHT = 4096
        private const val SLICE_OVERLAP = 160
        private const val MIN_OCR_WIDTH = 1024
        private const val MAX_OCR_SCALE = 2f

        /** Lettering survives this fine, and it is a third of the upload of quality 95. */
        private const val JPEG_QUALITY = 85
    }

}

/**
 * [existing] notes with [learned] folded into their own section, or null when nothing was new.
 *
 * The user's own notes are whatever sits above [ChapterTranslator.LEARNED_HEADING] and are
 * returned untouched. Below it, a term already decided keeps the translation it was given — the
 * point is that chapter forty calls a character what chapter one called them — and the total is
 * capped, because the whole thing is resent with every batch of every chapter.
 */
internal fun mergeLearnedTerms(existing: String, learned: Map<String, String>): String? {
    val parsed = parseSeriesNotes(existing)
    val mine = parsed.notes
    val known = LinkedHashMap(parsed.glossary)

    val before = known.size
    learned.forEach { (term, translated) ->
        if (known.size >= ChapterTranslator.MAX_LEARNED_TERMS) return@forEach
        known.putIfAbsent(term, translated)
    }
    if (known.size == before) return null

    val terms = known.entries.joinToString("\n") { (term, translated) -> "$term = $translated" }
    return listOf(mine, ChapterTranslator.LEARNED_HEADING, terms)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

/** Splits a stored notes blob back into what the reader wrote and what the translator learned. */
internal fun parseSeriesNotes(raw: String): SeriesNotes = SeriesNotes(
    notes = raw.substringBefore(ChapterTranslator.LEARNED_HEADING).trim(),
    glossary = raw.substringAfter(ChapterTranslator.LEARNED_HEADING, "")
        .lineSequence()
        .mapNotNull { line ->
            val term = line.substringBefore('=', "").trim()
            val translated = line.substringAfter('=', "").trim()
            if (term.isBlank() || translated.isBlank()) null else term to translated
        }
        .toMap(linkedMapOf()),
)

/** One image sent to [GeminiPageReader]: a page, or a band of a long one. */
private class PendingSlice(
    /** The page's key in the translation file. */
    val key: String,
    val pageIndex: Int,
    /** The whole page, kept until the reply so the band can be decoded again for its colours. */
    val bytes: ByteArray,
    val pageWidth: Int,
    val pageHeight: Int,
    val top: Int,
    val bottom: Int,
) {
    val scale: Float = sliceScale(pageWidth, bottom - top)
    lateinit var jpeg: ByteArray
}
