package io.github.kwiato.lazy9

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import androidx.core.content.ContextCompat
import io.github.kwiato.lazy9.data.PrivateSpace
import android.os.Build
import android.os.UserHandle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kwiato.lazy9.data.AppEntry
import io.github.kwiato.lazy9.data.AppRepository
import io.github.kwiato.lazy9.data.HomeItem
import io.github.kwiato.lazy9.data.LauncherState
import io.github.kwiato.lazy9.data.StateStore
import io.github.kwiato.lazy9.data.T9
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    val repo = AppRepository(app)
    private val store = StateStore(app)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val saver = Dispatchers.IO.limitedParallelism(1)

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var appsByKey by mutableStateOf<Map<String, AppEntry>>(emptyMap())
        private set
    var loaded by mutableStateOf(false)
        private set
    var state by mutableStateOf(store.load())
        private set

    /** Size of one home-grid cell in px, reported by the workspace once laid out. */
    var cellWpx = 0f
    var cellHpx = 0f

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            val suffix = "#${repo.serialOf(user)}"
            val gone = { key: String? ->
                key != null && key.startsWith("$packageName/") && key.endsWith(suffix)
            }
            update { s ->
                s.copy(
                    items = s.items.filterNot { gone(it.appKey) },
                    dock = s.dock.map { if (gone(it)) null else it },
                    usage = s.usage.filterKeys { !gone(it) },
                )
            }
            reload()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    var privateSpace by mutableStateOf<PrivateSpace?>(null)
        private set

    // A profile (Private Space, work profile) got locked, unlocked, added or removed.
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    init {
        reload()
        repo.register(packageCallback)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_PROFILE_ADDED)
            addAction(Intent.ACTION_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        }
        ContextCompat.registerReceiver(app, profileReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onCleared() {
        repo.unregister(packageCallback)
        getApplication<Application>().unregisterReceiver(profileReceiver)
    }

    fun reload() {
        viewModelScope.launch {
            val (list, space) = withContext(Dispatchers.Default) { repo.load() to repo.privateSpace() }
            apps = list
            appsByKey = list.associateBy { it.key }
            privateSpace = space
            loaded = true
        }
    }

    fun setPrivateSpaceLocked(locked: Boolean) {
        val space = privateSpace ?: return
        repo.setPrivateSpaceLocked(space, locked)
    }

    private fun update(transform: (LauncherState) -> LauncherState) {
        val next = transform(state)
        if (next == state) return
        state = next
        viewModelScope.launch(saver) { store.save(next) }
    }

    // ---- launching & search -------------------------------------------------

    fun launch(app: AppEntry) {
        try {
            repo.launch(app)
        } catch (e: Exception) {
            return
        }
        // Nothing about Private Space apps is written to disk.
        if (app.isPrivate) return
        update { it.copy(usage = it.usage + (app.key to (it.usage[app.key] ?: 0) + 1)) }
    }

    /** T9 matches, best first. An empty query lists the most used apps. */
    fun search(query: String): List<AppEntry> {
        val usage = state.usage
        if (query.isEmpty()) {
            return apps.filter { (usage[it.key] ?: 0) > 0 }
                .sortedByDescending { usage[it.key] ?: 0 }
        }
        return apps.mapNotNull { app ->
            val score = T9.score(app.t9, query)
            if (score > 0) app to score else null
        }.sortedWith(
            compareByDescending<Pair<AppEntry, Int>> { it.second }
                .thenByDescending { usage[it.first.key] ?: 0 },
        ).map { it.first }
    }

    fun setAutoLaunch(on: Boolean) = update { it.copy(autoLaunch = on) }

    fun setPersistentSearch(on: Boolean) = update { it.copy(persistentSearch = on) }

    /** Dock slots on screen: the column beside the persistent keypad only fits three. */
    val dockSlots: Int get() = if (state.persistentSearch) 3 else state.dock.size

    private fun freeDockSlot(): Int =
        state.dock.take(dockSlots).indexOfFirst { it == null || (loaded && it !in appsByKey) }

    fun dockHasRoom(): Boolean = freeDockSlot() >= 0

    // ---- dock ---------------------------------------------------------------

    fun setDock(slot: Int, key: String?) = update { s ->
        if (slot !in s.dock.indices) s
        else s.copy(dock = s.dock.mapIndexed { i, old ->
            when {
                i == slot -> key
                key != null && old == key -> null // an app sits in one slot only
                else -> old
            }
        })
    }

    /** Returns false when the dock is full. */
    fun addToDock(key: String): Boolean {
        if (key in state.dock.take(dockSlots)) return true
        val slot = freeDockSlot()
        if (slot < 0) return false
        setDock(slot, key)
        return true
    }

    // ---- home grid ----------------------------------------------------------

    fun canPlace(exceptId: Long?, page: Int, x: Int, y: Int, spanX: Int, spanY: Int): Boolean =
        canPlace(state, exceptId, page, x, y, spanX, spanY)

    private fun canPlace(
        s: LauncherState, exceptId: Long?, page: Int, x: Int, y: Int, spanX: Int, spanY: Int,
    ): Boolean {
        if (page !in 0 until s.pages) return false
        if (x < 0 || y < 0 || x + spanX > s.cols || y + spanY > s.rows) return false
        return s.items.none {
            it.id != exceptId && it.page == page &&
                x < it.x + it.spanX && it.x < x + spanX &&
                y < it.y + it.spanY && it.y < y + spanY
        }
    }

    /** First free spot for a spanX×spanY item, starting at [fromPage]; null if no page has room. */
    private fun findFree(s: LauncherState, spanX: Int, spanY: Int, fromPage: Int): Triple<Int, Int, Int>? {
        val order = (fromPage until s.pages) + (0 until fromPage.coerceAtMost(s.pages))
        for (page in order) {
            for (y in 0..s.rows - spanY) for (x in 0..s.cols - spanX) {
                if (canPlace(s, null, page, x, y, spanX, spanY)) return Triple(page, x, y)
            }
        }
        return null
    }

    private fun add(fromPage: Int, spanX: Int, spanY: Int, make: (Long, Int, Int, Int) -> HomeItem): Int {
        var page = 0
        update { s0 ->
            val sx = spanX.coerceIn(1, s0.cols)
            val sy = spanY.coerceIn(1, s0.rows)
            var s = s0
            val spot = findFree(s, sx, sy, fromPage.coerceIn(0, s.pages - 1)) ?: run {
                s = s.copy(pages = s.pages + 1)
                Triple(s.pages - 1, 0, 0)
            }
            page = spot.first
            val item = make(s.nextId, spot.first, spot.second, spot.third).copy(spanX = sx, spanY = sy)
            s.copy(items = s.items + item, nextId = s.nextId + 1)
        }
        return page
    }

    /** Puts an app on the home screen; returns the page it landed on. */
    fun addApp(key: String, fromPage: Int): Int =
        add(fromPage, 1, 1) { id, page, x, y -> HomeItem(id, page, x, y, appKey = key) }

    fun addWidget(widgetId: Int, spanX: Int, spanY: Int, fromPage: Int): Int =
        add(fromPage, spanX, spanY) { id, page, x, y -> HomeItem(id, page, x, y, widgetId = widgetId) }

    fun moveItem(id: Long, page: Int, x: Int, y: Int): Boolean {
        val item = state.items.find { it.id == id } ?: return false
        if (!canPlace(id, page, x, y, item.spanX, item.spanY)) return false
        update { s -> s.copy(items = s.items.map { if (it.id == id) it.copy(page = page, x = x, y = y) else it }) }
        return true
    }

    fun resizeItem(id: Long, spanX: Int, spanY: Int): Boolean {
        val item = state.items.find { it.id == id } ?: return false
        if (spanX < 1 || spanY < 1 || !canPlace(id, item.page, item.x, item.y, spanX, spanY)) return false
        update { s -> s.copy(items = s.items.map { if (it.id == id) it.copy(spanX = spanX, spanY = spanY) else it }) }
        return true
    }

    fun removeItem(id: Long) = update { s -> s.copy(items = s.items.filterNot { it.id == id }) }

    fun addPage(): Int {
        update { it.copy(pages = it.pages + 1) }
        return state.pages - 1
    }

    fun isPageEmpty(page: Int) = state.items.none { it.page == page }

    fun removePage(page: Int) = update { s ->
        if (s.pages <= 1 || s.items.any { it.page == page }) s
        else s.copy(
            pages = s.pages - 1,
            items = s.items.map { if (it.page > page) it.copy(page = it.page - 1) else it },
        )
    }

    /** How many grid cells a widget wants. */
    fun spanFor(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val s = state
        var sx = if (cellWpx > 0) ceil(info.minWidth / cellWpx).toInt() else 2
        var sy = if (cellHpx > 0) ceil(info.minHeight / cellHpx).toInt() else 1
        if (Build.VERSION.SDK_INT >= 31) {
            if (info.targetCellWidth > 0) sx = info.targetCellWidth
            if (info.targetCellHeight > 0) sy = info.targetCellHeight
        }
        return sx.coerceIn(1, s.cols) to sy.coerceIn(1, s.rows)
    }
}
