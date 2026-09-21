package eu.kanade.tachiyomi.ui.extension

import android.content.pm.PackageInstaller
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.extension.ExtensionInstallerJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.InstalledExtensionsOrder
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.ui.migration.BaseMigrationPresenter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import karasu.i18n.MR
import karasu.util.lang.getString

typealias ExtensionTuple =
    Triple<List<Extension.Installed>, List<Extension.Untrusted>, List<Extension.Available>>
typealias ExtensionIntallInfo = Pair<InstallStep, PackageInstaller.SessionInfo?>

/**
 * Presenter of [ExtensionBottomSheet].
 */
class ExtensionBottomPresenter : BaseMigrationPresenter<ExtensionBottomSheet>() {

    private var extensions = emptyList<IFlexible<*>>()

    /**
     * Every extension row, whatever is rolled up.
     *
     * Search runs over this rather than over what is on screen: a group the user collapsed is
     * still a group their search should find things in, and [extensions] deliberately does not
     * carry the rows of one.
     */
    private var searchable = emptyList<ExtensionItem>()

    val downloadManager: DownloadManager = Injekt.get()

    private var currentDownloads = hashMapOf<String, ExtensionIntallInfo>()

    private var firstLoad = true

    override fun onCreate() {
        super.onCreate()

        presenterScope.launch {
            val extensionJob = async {
                extensionManager.findAvailableExtensions()
                extensions = toItems(
                    Triple(
                        extensionManager.installedExtensionsFlow.value,
                        extensionManager.untrustedExtensionsFlow.value,
                        extensionManager.availableExtensionsFlow.value,
                    ),
                )
                withContext(Dispatchers.Main) { view?.setExtensions(extensions, false) }
            }
            val migrationJob = async { firstTimeMigration() }
            listOf(migrationJob, extensionJob).awaitAll()
        }
        presenterScope.launch {
            extensionManager.downloadSharedFlow
                .collect {
                    if (it.first.startsWith("Finished") || it.first.startsWith("Uninstalled")) {
                        if (it.first.startsWith("Finished")) {
                            firstLoad = true
                            currentDownloads.clear()
                        }
                        extensions = toItems(
                            Triple(
                                extensionManager.installedExtensionsFlow.value,
                                extensionManager.untrustedExtensionsFlow.value,
                                extensionManager.availableExtensionsFlow.value,
                            ),
                        )
                        withUIContext { view?.setExtensions(extensions) }
                        return@collect
                    }
                    val extension = extensions.filterIsInstance<ExtensionItem>().find { item ->
                        it.first == item.extension.pkgName
                    } ?: return@collect
                    when (it.second.first) {
                        InstallStep.Installed, InstallStep.Error -> {
                            currentDownloads.remove(extension.extension.pkgName)
                        }
                        else -> {
                            currentDownloads[extension.extension.pkgName] = it.second
                        }
                    }
                    val item = updateInstallStep(extension.extension, it.second.first, it.second.second)
                    if (item != null) {
                        withUIContext { view?.downloadUpdate(item) }
                    }
                }
        }
    }

    fun refreshExtensions() {
        presenterScope.launch {
            extensions = toItems(
                Triple(
                    extensionManager.installedExtensionsFlow.value,
                    extensionManager.untrustedExtensionsFlow.value,
                    extensionManager.availableExtensionsFlow.value,
                ),
            )
            withContext(Dispatchers.Main) { view?.setExtensions(extensions, false) }
        }
    }

    @Synchronized
    private fun toItems(tuple: ExtensionTuple): List<IFlexible<*>> {
        val context = view?.context ?: return emptyList()
        val collapsed = preferences.collapsedExtensionGroups().get()
        val activeLangs = preferences.enabledLanguages().get()
        val showNsfwSources = preferences.showNsfwSources().get()

        val (installed, untrusted, available) = tuple

        val items = mutableListOf<IFlexible<*>>()

        // A rolled up group contributes its header and nothing else. The header has to be added
        // by hand then: it is normally pulled in by the items that point at it, and a group with
        // no items in the list would otherwise vanish, taking with it the only way to reopen it.
        val searchableItems = mutableListOf<ExtensionItem>()
        fun addGroup(header: ExtensionGroupItem, extensions: List<Extension>) {
            val rows = extensions.map { ExtensionItem(it, header, currentDownloads[it.pkgName]) }
            searchableItems += rows
            if (header.collapsed) items += header else items += rows
        }

        if (firstLoad) {
            val listOfExtensions = installed + untrusted + available
            listOfExtensions.forEach {
                val installInfo = extensionManager.getInstallInfo(it.pkgName) ?: return@forEach
                currentDownloads[it.pkgName] = installInfo
            }
            firstLoad = false
        }

        val updatesSorted = installed.filter { it.hasUpdate && (showNsfwSources || !it.isNsfw) }.sortedBy { it.name }
        val sortOrder = InstalledExtensionsOrder.fromPreference(preferences)
        val installedSorted = installed
            .filter { !it.hasUpdate && (showNsfwSources || !it.isNsfw) }
            .sortedWith(
                compareBy(
                    { !it.isObsolete },
                    {
                        when (sortOrder) {
                            InstalledExtensionsOrder.Name -> it.name
                            InstalledExtensionsOrder.RecentlyUpdated -> Long.MAX_VALUE - ExtensionLoader.extensionUpdateDate(context, it)
                            InstalledExtensionsOrder.RecentlyInstalled -> Long.MAX_VALUE - ExtensionLoader.extensionInstallDate(context, it)
                            InstalledExtensionsOrder.Language -> it.lang
                        }
                    },
                    { it.name },
                ),
            )
        val untrustedSorted = untrusted.sortedBy { it.name }
        val availableSorted = available
            // Filter out already installed extensions and disabled languages
            .filter { avail ->
                installed.none { it.pkgName == avail.pkgName } &&
                    untrusted.none { it.pkgName == avail.pkgName } &&
                    (avail.lang in activeLangs) &&
                    (showNsfwSources || !avail.isNsfw)
            }
            // Repo url decides the tie so the same copy wins every refresh, rather than the list
            // reshuffling because two repos answered in a different order.
            .sortedWith(compareBy({ it.name }, { it.repoUrl.orEmpty() }))
            // One row per extension *version*. The same extension carried by several repos is one
            // extension, and at the same version there is nothing to choose between the copies.
            // Different versions stay apart, because choosing between those is a real choice.
            .distinctBy { it.pkgName to it.versionCode }

        if (updatesSorted.isNotEmpty()) {
            val title = context.getString(
                MR.plurals._updates_pending,
                updatesSorted.size,
                updatesSorted.size,
            )
            val header = ExtensionGroupItem(
                title,
                key = UPDATES_GROUP,
                size = updatesSorted.size,
                canUpdate = items.filterIsInstance<ExtensionItem>()
                    .count { it.extension.pkgName in currentDownloads.keys } != updatesSorted.size,
                collapsed = UPDATES_GROUP in collapsed,
            )
            addGroup(header, updatesSorted)
        }
        if (installedSorted.isNotEmpty() || untrustedSorted.isNotEmpty()) {
            val title = context.getString(MR.strings.installed)
            val header = ExtensionGroupItem(
                title,
                key = INSTALLED_GROUP,
                size = installedSorted.size + untrustedSorted.size,
                installedSorting = preferences.installedExtensionsOrder().get(),
                collapsed = INSTALLED_GROUP in collapsed,
            )
            addGroup(header, installedSorted + untrustedSorted)
        }
        if (availableSorted.isNotEmpty()) {
            // Grouped by language *code*, which is what the collapse state is keyed on, then
            // ordered by the name the user actually reads.
            availableSorted
                .groupBy { it.lang }
                .toList()
                .sortedBy { (lang, _) -> LocaleHelper.getSourceDisplayName(lang, context) }
                .forEach { (lang, group) ->
                    val header = ExtensionGroupItem(
                        LocaleHelper.getSourceDisplayName(lang, context),
                        key = lang,
                        size = group.size,
                        collapsed = lang in collapsed,
                    )
                    addGroup(header, group)
                }
        }

        this.extensions = items
        this.searchable = searchableItems
        return items
    }

    /** Rolls a group up or back down, and redraws the list from the new state. */
    fun toggleGroup(name: String) {
        val pref = preferences.collapsedExtensionGroups()
        val collapsed = pref.get()
        pref.set(if (name in collapsed) collapsed - name else collapsed + name)
        refreshExtensions()
    }

    /** Every extension row, ignoring which groups are rolled up. Search uses it. */
    fun searchableExtensions(): List<ExtensionItem> = searchable

    fun getExtensionUpdateCount(): Int = preferences.extensionUpdatesCount().get()

    @Synchronized
    private fun updateInstallStep(
        extension: Extension,
        state: InstallStep?,
        session: PackageInstaller.SessionInfo?,
    ): ExtensionItem? {
        val extensions = extensions.toMutableList()
        // A rolled up group leaves bare headers in here, which are not rows of an extension.
        val position = extensions.indexOfFirst {
            it is ExtensionItem && it.extension.pkgName == extension.pkgName
        }

        return if (position != -1) {
            val item = (extensions[position] as ExtensionItem).copy(
                installStep = state,
                session = session,
            )
            extensions[position] = item

            this.extensions = extensions
            item
        } else {
            null
        }
    }

    fun cancelExtensionInstall(extItem: ExtensionItem) {
        val sessionId = extItem.session?.sessionId ?: return
        extensionManager.cancelInstallation(sessionId)
    }

    fun installExtension(extension: Extension.Available) {
        presenterScope.launch {
            extensionManager.installExtension(
                ExtensionManager.ExtensionInfo(extension),
                presenterScope,
            )
                .collect {
                    when (it.first) {
                        InstallStep.Installed, InstallStep.Error -> {
                            currentDownloads.remove(extension.pkgName)
                        }
                        else -> {
                            currentDownloads[extension.pkgName] = it
                        }
                    }
                    val item = updateInstallStep(extension, it.first, it.second)
                    if (item != null) {
                        withUIContext { view?.downloadUpdate(item) }
                    }
                }
        }
    }

    fun updateExtension(extension: Extension.Installed) {
        val availableExt =
            extensionManager.availableExtensionsFlow.value.find { it.pkgName == extension.pkgName } ?: return
        installExtension(availableExt)
    }

    fun updateExtensions(extensions: List<Extension.Installed>) {
        if (extensions.isEmpty()) return
        val context = view?.context ?: return
        extensions.forEach {
            val pkgName = it.pkgName
            currentDownloads[pkgName] = InstallStep.Pending to null
            val item = updateInstallStep(it, InstallStep.Pending, null) ?: return@forEach
            view?.downloadUpdate(item)
        }
        ExtensionInstallerJob.start(
            context,
            extensions.mapNotNull { extension ->
                extensionManager.availableExtensionsFlow.value.find { it.pkgName == extension.pkgName }
            },
        )
    }

    fun uninstallExtension(pkgName: String) {
        extensionManager.uninstallExtension(pkgName)
    }

    fun findAvailableExtensions() {
        presenterScope.launch {
            extensionManager.findAvailableExtensions()
        }
    }

    fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) {
        presenterScope.launch {
            extensionManager.trust(pkgName, versionCode, signatureHash)
        }
    }
}

/** Stable collapse keys for the two groups whose titles are built rather than fixed. */
private const val UPDATES_GROUP = "updates"
private const val INSTALLED_GROUP = "installed"
