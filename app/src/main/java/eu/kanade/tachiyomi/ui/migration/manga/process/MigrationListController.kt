package eu.kanade.tachiyomi.ui.migration.manga.process

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.MigrationListControllerBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.smartsearch.SmartSearchEngine
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.base.controller.FadeChangeHandler
import eu.kanade.tachiyomi.ui.main.BottomNavBarInterface
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.migration.SearchController
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.lang.toNormalized
import eu.kanade.tachiyomi.util.system.getParcelableCompat
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.view.RecyclerWindowInsetsListener
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.isControllerVisible
import eu.kanade.tachiyomi.util.view.liftAppbarWith
import eu.kanade.tachiyomi.util.view.setPositiveButton
import eu.kanade.tachiyomi.util.view.setTextColorAlpha
import eu.kanade.tachiyomi.util.view.setTitle
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import uy.kohesive.injekt.injectLazy
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.interactor.UpdateManga
import karasu.i18n.MR
import karasu.util.lang.getString
import android.R as AR

class MigrationListController(bundle: Bundle? = null) :
    BaseLegacyController<MigrationListControllerBinding>(bundle),
    MigrationProcessAdapter.MigrationProcessInterface,
    BottomNavBarInterface,
    SmallToolbarInterface,
    CoroutineScope {

    init {
        setHasOptionsMenu(true)
    }

    private var adapter: MigrationProcessAdapter? = null

    override val coroutineContext: CoroutineContext = Job() + Dispatchers.Default

    val config = args.getParcelableCompat(CONFIG_EXTRA, MigrationProcedureConfig::class.java)

    private val getManga: GetManga by injectLazy()
    private val updateManga: UpdateManga by injectLazy()

    private val preferences: PreferencesHelper by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val smartSearchEngine = SmartSearchEngine(coroutineContext)

    private var migratingManga: MutableList<MigratingManga>? = null

    /** Which entry the open search belongs to. An id, not a row: rows move when one is skipped. */
    private var selectedMangaId: Long? = null
    private var manaulMigrations = 0

    /** The view is rebuilt every time the search screen is popped; the search opens once. */
    private var openedFirstSearch = false

    override fun createBinding(inflater: LayoutInflater) = MigrationListControllerBinding.inflate(inflater)
    override fun getTitle(): String {
        // Used to count finished searches; with the search picked by hand it counts the entries
        // that have a target, which is the same thing from the reader's side and stays at 0
        // until something is actually chosen.
        val picked = adapter?.items?.count { it.manga.migrationStatus == MigrationStatus.MANGA_FOUND }
        return activity?.getString(MR.strings.migration) + " ($picked/${adapter?.itemCount})"
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        liftAppbarWith(binding.recycler)
        val toolbarTextView = activityBinding?.toolbar?.toolbarTitle
        toolbarTextView?.setTextColorAlpha(255)
        val config = this.config ?: return

        val newMigratingManga = migratingManga ?: run {
            val new = config.mangaIds.map {
                MigratingManga(sourceManager, it, coroutineContext)
            }
            migratingManga = new.toMutableList()
            new
        }

        adapter = MigrationProcessAdapter(this)

        binding.recycler.adapter = adapter
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        binding.recycler.setHasFixedSize(true)
        binding.recycler.setOnApplyWindowInsetsListener(RecyclerWindowInsetsListener)

        // Nothing is searched automatically any more. Guessing a target per source meant every
        // enabled source got a search and a chapter list before anything could be shown, and the
        // guess was still wrong often enough to be checked by hand — so the hand search is the
        // whole flow now, and this list is only where the picks are reviewed.
        newMigratingManga.forEach {
            if (!it.searchResult.initialized) {
                it.migrationStatus = MigrationStatus.MANGA_NOT_FOUND
                it.searchResult.initialize(null)
            }
        }

        adapter?.updateDataSet(newMigratingManga.map { it.toModal() })

        // A single entry has nothing to review, so it goes straight to the search that is the
        // only thing left to do. Several entries stay on this list, where each empty slot opens
        // its own search — picking the order is yours, and nothing is searched behind your back.
        if (!openedFirstSearch && newMigratingManga.size == 1) {
            openedFirstSearch = true
            openManualSearch(0)
        }
    }

    override fun searchManually(position: Int) = openManualSearch(position)

    /**
     * Pushes the manual search for the entry at [position], across the sources chosen on the
     * previous screen.
     */
    private fun openManualSearch(position: Int) {
        launchUI {
            val manga = adapter?.getItem(position)?.manga?.manga() ?: return@launchUI
            selectedMangaId = manga.id
            val sources = preferences.migrationSources().get().split("/").mapNotNull {
                val value = it.toLongOrNull() ?: return@mapNotNull null
                sourceManager.get(value) as? CatalogueSource
            }
            val validSources = if (sources.size == 1) {
                sources
            } else {
                sources.filter { it.id != manga.source }
            }
            manga.title = manga.title.toNormalized()
            val searchController = SearchController(manga, validSources)
            searchController.targetController = this@MigrationListController
            router.pushController(searchController.withFadeTransaction())
        }
    }

    override fun updateCount() {
        viewScope.launchUI {
            if (isControllerVisible) {
                setTitle()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun enableButtons() {
        activity?.invalidateOptionsMenu()
    }

    override fun removeManga(item: MigrationProcessItem) {
        val ids = config?.mangaIds?.toMutableList() ?: return
        val index = ids.indexOf(item.manga.mangaId)
        if (index > -1) {
            ids.removeAt(index)
            config.mangaIds = ids
            val index2 = migratingManga?.indexOf(item.manga) ?: return
            if (index2 > -1) migratingManga?.removeAt(index2)
        }
    }

    override fun noMigration() {
        launchUI {
            if (activity != null) {
                activity!!.toast(
                    activity!!.getString(
                        MR.plurals.manga_migrated,
                        manaulMigrations,
                        manaulMigrations,
                    ),
                )
            }
            router.popCurrentController()
        }
    }

    override fun onMenuItemClick(position: Int, item: MenuItem) {
        when (item.itemId) {
            R.id.action_search_manually -> openManualSearch(position)
            R.id.action_skip -> adapter?.removeManga(position)
            R.id.action_migrate_now -> {
                adapter?.migrateManga(position, false)
                manaulMigrations++
            }
            R.id.action_copy_now -> {
                adapter?.migrateManga(position, true)
                manaulMigrations++
            }
        }
    }

    fun useMangaForMigration(manga: Manga, source: Source) {
        val mangaId = selectedMangaId ?: return
        val migratingManga = adapter?.currentItems?.firstOrNull { it.manga.mangaId == mangaId } ?: return
        migratingManga.manga.migrationStatus = MigrationStatus.RUNNUNG
        notifyChanged(mangaId)
        launchUI {
            val result = CoroutineScope(migratingManga.manga.migrationJob).async {
                val localManga = smartSearchEngine.networkToLocalManga(manga, source.id)
                try {
                    val update = source.getMangaUpdate(
                        manga = localManga,
                        chapters = emptyList(),
                        fetchDetails = true,
                        fetchChapters = true,
                    )
                    try {
                        localManga.copyFrom(update.manga)
                        updateManga.await(localManga.toMangaUpdate())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Details sync is non-fatal; chapters may still migrate.
                    }
                    withIOContext { syncChaptersWithSource(update.chapters, localManga, source) }
                    localManga
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@async null
                }
            }.await()

            if (result != null) {
                migratingManga.manga.migrationStatus = MigrationStatus.MANGA_FOUND
                migratingManga.manga.searchResult.set(result.id)
                notifyChanged(mangaId)
                if (adapter?.itemCount == 1) {
                    // One entry, one pick: the pick is the confirmation, so this list never has
                    // to be looked at. With several it comes back to the list, where the rest
                    // are picked and then copied or migrated together.
                    migrateMangas()
                }
            } else {
                migratingManga.manga.migrationStatus = MigrationStatus.MANGA_NOT_FOUND
                activity?.toast(MR.strings.no_chapters_found_for_migration, Toast.LENGTH_LONG)
                notifyChanged(mangaId)
            }
        }
    }

    /**
     * Redraws the row for [mangaId].
     *
     * The position is looked up every time rather than remembered: skipping an entry removes a
     * row and shifts everything below it, so a position captured before the source was queried
     * can point at somebody else by the time the answer arrives.
     */
    private fun notifyChanged(mangaId: Long) {
        val index = adapter?.currentItems?.indexOfFirst { it.manga.mangaId == mangaId } ?: return
        if (index >= 0) adapter?.notifyItemChanged(index)
    }

    fun migrateMangas() {
        launchUI {
            adapter?.performMigrations(false)
            navigateOut()
        }
    }

    fun copyMangas() {
        launchUI {
            adapter?.performMigrations(true)
            navigateOut()
        }
    }

    private fun navigateOut() {
        if (migratingManga?.size == 1) {
            launchUI {
                val hasDetails = router.backstack.any { it.controller is MangaDetailsController }
                if (hasDetails) {
                    val manga = migratingManga?.firstOrNull()?.searchResult?.get()?.let { getManga.awaitById(it) }
                    if (manga != null) {
                        val newStack = router.backstack.filter {
                            it.controller !is MangaDetailsController &&
                                it.controller !is MigrationListController &&
                                it.controller !is PreMigrationController
                        } + MangaDetailsController(manga).withFadeTransaction()
                        router.setBackstack(newStack, FadeChangeHandler())
                        return@launchUI
                    }
                }
                router.popCurrentController()
            }
        } else {
            router.popCurrentController()
        }
    }

    /** True once any entry has a target picked, i.e. once there is something to lose by leaving. */
    private fun hasPicks() = adapter?.currentItems?.any { it.manga.searchResult.content != null } == true

    override fun handleBack(): Boolean {
        // Backing out before picking anything cancels nothing, so it does not ask.
        if (!hasPicks()) return false
        view?.let { view ->
            if (view.x != 0f || view.alpha != 1f) {
                val animatorSet = AnimatorSet()
                animatorSet.play(ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 1f))
                val tA = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, view.translationX, 0f)
                tA.addUpdateListener {
                    activityBinding?.backShadow?.let { backShadow ->
                        backShadow.x = view.x - backShadow.width
                    }
                }
                animatorSet.duration = 150
                animatorSet.doOnEnd {
                    activityBinding?.backShadow?.alpha = 0.25f
                    activityBinding?.backShadow?.isVisible = false
                }
                animatorSet.play(tA)
                animatorSet.start()
            }
        }
        activity?.materialAlertDialog()
            ?.setTitle(MR.strings.stop_migrating)
            ?.setPositiveButton(MR.strings.stop) { _, _ ->
                router.popCurrentController()
            }
            ?.setNegativeButton(AR.string.cancel, null)?.show()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.migration_list, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        // Initialize menu items.

        val allMangasDone = adapter?.allMangasDone() ?: return

        val menuCopy = menu.findItem(R.id.action_copy_manga)
        val menuMigrate = menu.findItem(R.id.action_migrate_manga)

        if (adapter?.itemCount == 1) {
            menuMigrate.icon = VectorDrawableCompat.create(
                resources!!,
                R.drawable.ic_done_24dp,
                null,
            )
        }

        menuCopy.icon?.mutate()
        menuMigrate.icon?.mutate()
        val tintColor = activity?.getResourceColor(R.attr.actionBarTintColor) ?: Color.WHITE
        val translucentWhite = ColorUtils.setAlphaComponent(tintColor, 127)
        menuCopy.icon?.setTint(if (allMangasDone) tintColor else translucentWhite)
        menuMigrate?.icon?.setTint(if (allMangasDone) tintColor else translucentWhite)
        menuCopy.isEnabled = allMangasDone
        menuMigrate.isEnabled = allMangasDone
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val totalManga = adapter?.itemCount ?: 0
        val mangaSkipped = adapter?.mangasSkipped() ?: 0
        when (item.itemId) {
            R.id.action_copy_manga, R.id.action_migrate_manga -> {
                showCopyMigrateDialog(
                    R.id.action_copy_manga == item.itemId,
                    totalManga,
                    mangaSkipped,
                )
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showCopyMigrateDialog(copy: Boolean, totalManga: Int, mangaSkipped: Int) {
        val activity = activity ?: return
        val confirmRes = if (copy) MR.plurals.copy_manga else MR.plurals.migrate_manga
        val skipping by lazy { activity.getString(MR.strings.skipping_, mangaSkipped) }
        val additionalString = if (mangaSkipped > 0) " $skipping" else ""
        val confirmString = activity.getString(
            confirmRes,
            totalManga,
            totalManga,
            additionalString,
        )
        activity.materialAlertDialog()
            .setMessage(confirmString)
            .setPositiveButton(if (copy) MR.strings.copy_value else MR.strings.migrate) { _, _ ->
                if (copy) {
                    copyMangas()
                } else {
                    migrateMangas()
                }
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

    override fun canChangeTabs(block: () -> Unit): Boolean {
        if (hasPicks()) {
            activity?.materialAlertDialog()
                ?.setTitle(MR.strings.stop_migrating)
                ?.setPositiveButton(MR.strings.stop) { _, _ -> block() }
                ?.setNegativeButton(AR.string.cancel, null)
            return false
        }
        return true
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) {
        super.handleOnBackProgressed(backEvent)
        if (router.backstackSize > 1 && isControllerVisible) {
            router.backstack[router.backstackSize - 2].controller.view?.let { view2 ->
                view2.alpha = 0f
                view2.x = 0f
            }
        }
    }

    companion object {
        const val CONFIG_EXTRA = "config_extra"
        const val TAG = "migration_list"

        fun create(config: MigrationProcedureConfig): MigrationListController {
            return MigrationListController(
                Bundle().apply {
                    putParcelable(CONFIG_EXTRA, config)
                },
            )
        }
    }
}
