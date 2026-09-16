package eu.kanade.tachiyomi.ui.manga.chapter

import android.view.View
import androidx.appcompat.widget.PopupMenu
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.system.toast
import karasu.i18n.MR
import karasu.translation.TranslationManager
import karasu.util.lang.getString
import uy.kohesive.injekt.injectLazy

open class BaseChapterHolder(
    view: View,
    private val adapter: BaseChapterAdapter<*>,
) : BaseFlexibleViewHolder(view, adapter) {

    private val translationManager: TranslationManager by injectLazy()

    init {
        view.findViewById<View>(R.id.download_button)?.setOnClickListener { downloadOrRemoveMenu(it) }
    }

    internal fun downloadOrRemoveMenu(downloadButton: View, extraChapter: Chapter? = null, extraStatus: Download.State? = null) {
        val chapter = adapter.getItem(flexibleAdapterPosition) as? BaseChapterItem<*, *> ?: return

        val chapterStatus = extraStatus ?: chapter.status
        if (chapterStatus == Download.State.NOT_DOWNLOADED || chapterStatus == Download.State.ERROR) {
            if (extraChapter != null) {
                (adapter.baseDelegate as? BaseChapterAdapter.GroupedDownloadInterface)
                    ?.downloadChapter(flexibleAdapterPosition, extraChapter)
            } else {
                adapter.baseDelegate.downloadChapter(flexibleAdapterPosition)
            }
        } else {
            downloadButton.post {
                // Create a PopupMenu, giving it the clicked view for an anchor
                val popup = PopupMenu(downloadButton.context, downloadButton)

                // Inflate our menu resource into the PopupMenu's Menu
                popup.menuInflater.inflate(R.menu.chapter_download, popup.menu)

                popup.menu.findItem(R.id.action_start).isVisible = chapterStatus == Download.State.QUEUE

                // Only a downloaded chapter has pages on disk to run OCR over.
                popup.menu.findItem(R.id.action_translate).isVisible =
                    chapterStatus == Download.State.DOWNLOADED

                // Hide download and show delete if the chapter is downloaded
                if (chapterStatus != Download.State.DOWNLOADED) {
                    popup.menu.findItem(R.id.action_delete).title = downloadButton.context.getString(
                        MR.strings.cancel,
                    )
                }

                // Set a listener so we are notified if a menu item is clicked
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_delete -> {
                            if (extraChapter != null) {
                                (adapter.baseDelegate as? BaseChapterAdapter.GroupedDownloadInterface)
                                    ?.downloadChapter(flexibleAdapterPosition, extraChapter)
                            } else {
                                adapter.baseDelegate.downloadChapter(flexibleAdapterPosition)
                            }
                        }
                        R.id.action_start -> {
                            if (extraChapter != null) {
                                (adapter.baseDelegate as? BaseChapterAdapter.GroupedDownloadInterface)
                                    ?.startDownloadNow(flexibleAdapterPosition, extraChapter)
                            } else {
                                adapter.baseDelegate.startDownloadNow(flexibleAdapterPosition)
                            }
                        }
                        R.id.action_translate -> {
                            translationManager.translateChapter(extraChapter ?: chapter.chapter)
                            downloadButton.context.toast(MR.strings.translation_queued)
                        }
                    }
                    true
                }

                // Finally show the PopupMenu
                popup.show()
            }
        }
    }
}
