package eu.kanade.tachiyomi.data.track

import android.content.Context
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.kavita.Kavita
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.komga.Komga
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.suwayomi.Suwayomi

class TrackManager(context: Context) {

    companion object {
        const val MYANIMELIST = 1L
        const val ANILIST = 2L
        const val KITSU = 3L

        // 4 and 5 were Shikimori and Bangumi, dropped because both were still authenticating
        // against the upstream Tachiyomi apps. Retired, not free: a library restored from an old
        // backup still has tracks stamped with them, and handing either number to a new tracker
        // would silently adopt those rows as its own.
        const val KOMGA = 6L
        const val MANGA_UPDATES = 7L
        const val KAVITA = 8L
        const val SUWAYOMI = 9L
    }

    val myAnimeList = MyAnimeList(context, MYANIMELIST)
    val aniList = Anilist(context, ANILIST)
    val kitsu = Kitsu(context, KITSU)
    val komga = Komga(context, KOMGA)
    val mangaUpdates = MangaUpdates(context, MANGA_UPDATES)
    val kavita = Kavita(context, KAVITA)
    val suwayomi = Suwayomi(context, SUWAYOMI)

    val services = listOf(myAnimeList, aniList, kitsu, komga, mangaUpdates, kavita, suwayomi)

    fun getService(id: Long) = services.find { it.id == id }

    fun hasLoggedServices() = services.any { it.isLogged }
}
