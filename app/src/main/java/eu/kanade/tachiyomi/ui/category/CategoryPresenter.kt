package eu.kanade.tachiyomi.ui.category

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.ui.library.LibrarySort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy
import karasu.domain.category.interactor.DeleteCategories
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.interactor.InsertCategories
import karasu.domain.category.interactor.UpdateCategories
import karasu.domain.category.models.CategoryUpdate
import karasu.i18n.MR
import karasu.util.lang.getString

/**
 * Presenter of [CategoryController]. Used to manage the categories of the library.
 */
class CategoryPresenter(
    private val controller: CategoryController,
) {
    private val deleteCategories: DeleteCategories by injectLazy()
    private val getCategories: GetCategories by injectLazy()
    private val insertCategories: InsertCategories by injectLazy()
    private val updateCategories: UpdateCategories by injectLazy()

    /**
     * Main, deliberately.
     *
     * [categories] is read by the UI and rewritten by every action here, and a drag to reorder
     * fires one of those per drop. Running the coroutines on a background dispatcher meant a
     * reorder landing while the list was being read could throw, or quietly lose a category when
     * two of them rewrote the field at once. Confining the state to one thread and hopping to
     * [Dispatchers.IO] for the database is what makes that impossible rather than unlikely.
     */
    private var scope = CoroutineScope(Job() + Dispatchers.Main)

    /**
     * Categories as the screen currently shows them. Replaced, never mutated in place, and only
     * ever from the main thread.
     */
    private var categories: List<Category> = emptyList()

    /**
     * Called when the presenter is created.
     */
    fun getCategories() {
        if (categories.isNotEmpty()) {
            controller.setCategories(categories.map(::CategoryItem))
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { getCategories.await() }
            categories = listOf(newCategory()) + loaded
            controller.setCategories(categories.map(::CategoryItem))
        }
    }

    private fun newCategory(): Category {
        val default =
            Category.create(controller.view?.context?.getString(MR.strings.create_new_category) ?: "")
        default.order = CREATE_CATEGORY_ORDER
        default.id = Int.MIN_VALUE
        return default
    }

    /**
     * Creates and adds a new category to the database.
     *
     * @param name The name of the category to create.
     */
    fun createCategory(name: String): Boolean {
        // Do not allow duplicate categories.
        if (categoryExists(name, null)) {
            controller.onCategoryExistsError()
            return false
        }

        // Create category.
        val cat = Category.create(name)

        // Set the new item in the last position.
        cat.order = (categories.maxOfOrNull { it.order } ?: 0) + 1

        cat.mangaSort = LibrarySort.Title.categoryValue
        // The duplicate-name check above is what decides the return value, so the write itself
        // doesn't have to block the caller. The insert hands back the new id, which saves
        // re-reading the whole category table just to find the row we just wrote.
        scope.launch {
            val id = withContext(Dispatchers.IO) { insertCategories.awaitOne(cat) } ?: return@launch
            cat.id = id.toInt()
            // Straight after the "create new category" row, unless the list hasn't loaded yet.
            categories = categories.toMutableList().apply { add(minOf(1, size), cat) }
            reorderCategories(categories)
        }
        return true
    }

    /**
     * Deletes the given categories from the database.
     *
     * @param category The category to delete.
     */
    fun deleteCategory(category: Category?) {
        val safeCategory = category?.id ?: return
        scope.launch {
            withContext(Dispatchers.IO) { deleteCategories.awaitOne(safeCategory.toLong()) }
            categories = categories - category
            controller.setCategories(categories.map(::CategoryItem))
        }
    }

    /**
     * Reorders the given categories in the database.
     *
     * @param categories The list of categories to reorder.
     */
    fun reorderCategories(categories: List<Category>) {
        scope.launch {
            // A category still waiting on its insert has no id yet; renumber it in memory anyway
            // and let the next reorder persist it, rather than throwing the whole drag away.
            val updates = categories
                .filter { it.order != CREATE_CATEGORY_ORDER }
                .mapIndexedNotNull { i, category ->
                    category.order = i - 1
                    category.id?.let {
                        CategoryUpdate(id = it.toLong(), order = category.order.toLong())
                    }
                }
            withContext(Dispatchers.IO) { updateCategories.await(updates) }
            this@CategoryPresenter.categories = categories.sortedBy { it.order }
            controller.setCategories(this@CategoryPresenter.categories.map(::CategoryItem))
        }
    }

    /**
     * Renames a category.
     *
     * @param category The category to rename.
     * @param name The new name of the category.
     */
    fun renameCategory(category: Category, name: String): Boolean {
        // Do not allow duplicate categories.
        if (categoryExists(name, category.id)) {
            controller.onCategoryExistsError()
            return false
        }
        if (name.isBlank()) {
            return false
        }
        val id = category.id ?: return false

        category.name = name
        categories.find { it.id == category.id }?.name = name
        // Renaming can't fail once the name is known to be free, so the list redraws from memory
        // straight away and the database catches up behind it.
        controller.setCategories(categories.map(::CategoryItem))
        scope.launch {
            withContext(Dispatchers.IO) {
                updateCategories.awaitOne(CategoryUpdate(id = id.toLong(), name = name))
            }
        }
        return true
    }

    /**
     * Returns true if a category with the given name already exists.
     */
    private fun categoryExists(name: String, id: Int?): Boolean {
        return categories.any { it.name.equals(name, true) && id != it.id }
    }

    companion object {
        const val CREATE_CATEGORY_ORDER = -2
    }
}
