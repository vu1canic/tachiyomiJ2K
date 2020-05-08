package eu.kanade.tachiyomi.ui.library

import android.os.Build
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.lang.removeArticles
import eu.kanade.tachiyomi.util.system.timeSpanFromNow
import uy.kohesive.injekt.injectLazy
import java.util.Locale

/**
 * Adapter storing a list of manga in a certain category.
 *
 * @param view the fragment containing this adapter.
 */
class LibraryCategoryAdapter(val controller: LibraryController) :
        FlexibleAdapter<IFlexible<*>>(null, controller, true) {

    init {
        setDisplayHeadersAtStartUp(true)
    }
    /**
     * The list of manga in this category.
     */
    private var mangas: List<LibraryItem> = emptyList()

    val libraryListener: LibraryListener = controller

    val isSingleCategory
        get() = controller.singleCategory || !controller.presenter.showAllCategories

    /**
     * Sets a list of manga in the adapter.
     *
     * @param list the list to set.
     */
    fun setItems(list: List<LibraryItem>) {
        // A copy of manga always unfiltered.
        mangas = list.toList()

        performFilter()
    }

    /**
     * Returns the position in the adapter for the given manga.
     *
     * @param manga the manga to find.
     */
    fun indexOf(categoryOrder: Int): Int {
        return currentItems.indexOfFirst {
            if (it is LibraryHeaderItem) it.category.order == categoryOrder
            else false }
    }

    /**
     * Returns the position in the adapter for the given manga.
     *
     * @param manga the manga to find.
     */
    fun indexOf(manga: Manga): Int {
        return currentItems.indexOfFirst {
            if (it is LibraryItem) it.manga.id == manga.id
            else false }
    }

    fun getHeaderPositions(): List<Int> {
        return currentItems.mapIndexedNotNull { index, it ->
            if (it is LibraryHeaderItem) index
            else null }
    }

    /**
     * Returns the position in the adapter for the given manga.
     *
     * @param manga the manga to find.
     */
    fun allIndexOf(manga: Manga): List<Int> {
        return currentItems.mapIndexedNotNull { index, it ->
            if (it is LibraryItem && it.manga.id == manga.id) index
            else null }
    }

    fun performFilter() {
        val s = getFilter(String::class.java)
        if (s.isNullOrBlank()) {
            updateDataSet(mangas)
        } else {
            updateDataSet(mangas.filter { it.filter(s) })
        }
        isLongPressDragEnabled = libraryListener.canDrag() && s.isNullOrBlank()
    }

    private fun getFirstLetter(name: String): String {
        val letter = name.firstOrNull() ?: '#'
        return if (letter.isLetter()) getFirstChar(name) else "#"
    }

    private fun getFirstChar(string: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val chars = string.codePoints().toArray().firstOrNull() ?: return ""
            val char = Character.toChars(chars)
            return String(char).toUpperCase(Locale.US)
        } else {
            return string.toCharArray().firstOrNull()?.toString()?.toUpperCase(Locale.US) ?: ""
        }
    }

    override fun onCreateBubbleText(position: Int): String {
        val preferences: PreferencesHelper by injectLazy()
        val db: DatabaseHelper by injectLazy()
        if (position == itemCount - 1) return recyclerView.context.getString(R.string.bottom)
        return when (val item: IFlexible<*>? = getItem(position)) {
            is LibraryHeaderItem ->
                if (!preferences.hideCategories().getOrDefault()) item.category.name
                else recyclerView.context.getString(R.string.top)
            is LibraryItem -> {
                val text = if (item.manga.isBlank()) ""
                else when (getSort(position)) {
                    LibrarySort.DRAG_AND_DROP -> {
                        if (!preferences.hideCategories().getOrDefault()) {
                            val title = item.manga.title
                            if (preferences.removeArticles().getOrDefault())
                                title.removeArticles().chop(15)
                            else title.take(10)
                        } else {
                            val category = db.getCategoriesForManga(item.manga)
                                .executeAsBlocking().firstOrNull()?.name
                            category ?: recyclerView.context.getString(R.string.default_value)
                        }
                    }
                    LibrarySort.LAST_READ -> {
                        val id = item.manga.id ?: return ""
                        val history = db.getHistoryByMangaId(id).executeAsBlocking()
                        val last = history.maxBy { it.last_read }
                        if (last != null && last.last_read > 100) last.last_read.timeSpanFromNow
                        else "N/A"
                    }
                    LibrarySort.UNREAD -> {
                        val unread = item.manga.unread
                        if (unread > 0) recyclerView.context.getString(R.string._unread, unread)
                        else recyclerView.context.getString(R.string.read)
                    }
                    LibrarySort.TOTAL -> {
                        val total = item.chapterCount
                        if (total > 0) recyclerView.resources.getQuantityString(R.plurals
                            .chapters, total, total)
                        else "N/A"
                    }
                    LibrarySort.LATEST_CHAPTER -> {
                        val lastUpdate = item.manga.last_update
                        if (lastUpdate > 0) lastUpdate.timeSpanFromNow
                        else "N/A"
                    }
                    LibrarySort.DATE_ADDED -> {
                        val lastUpdate = item.manga.date_added
                        if (lastUpdate > 0) lastUpdate.timeSpanFromNow
                        else "N/A"
                    }
                    else -> {
                        val title = if (preferences.removeArticles()
                                .getOrDefault()
                        ) item.manga.title.removeArticles()
                        else item.manga.title
                        getFirstLetter(title)
                    }
                }
                if (isSingleCategory) {
                    text
                } else {
                    item.header?.category?.name.orEmpty() + ": " + text
                }
            }
            else -> ""
        }
    }

    private fun getSort(position: Int? = null): Int {
        val preferences: PreferencesHelper by injectLazy()
        return if (position != null) {
            val header = (getItem(position) as? LibraryItem)?.header
            if (header != null) {
                header.category.sortingMode() ?: LibrarySort.DRAG_AND_DROP
            } else {
                LibrarySort.DRAG_AND_DROP
            }
        } else if (!preferences.showAllCategories().get() && !preferences.hideCategories()
                .getOrDefault()
        ) {
            controller.presenter.getCurrentCategory()?.sortingMode() ?: LibrarySort.DRAG_AND_DROP
        } else {
            preferences.librarySortingMode().getOrDefault()
        }
    }

    interface LibraryListener {
        fun startReading(position: Int)
        fun onItemReleased(position: Int)
        fun canDrag(): Boolean
        fun updateCategory(catId: Int): Boolean
        fun sortCategory(catId: Int, sortBy: Int)
        fun selectAll(position: Int)
        fun allSelected(position: Int): Boolean
        fun toggleCategoryVisibility(position: Int)
        fun manageCategory(position: Int)
        fun globalSearch(query: String)
    }
}
