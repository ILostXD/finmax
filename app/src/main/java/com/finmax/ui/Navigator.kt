package com.finmax.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.UUID

sealed interface Route {
    /** Top-level destinations reachable from the top bar. */
    sealed interface Tab : Route
    data object Home : Tab
    data object Movies : Tab
    data object Shows : Tab
    data object MyList : Tab
    data object Search : Tab
    data object Libraries : Tab
    data object Settings : Tab

    data class Library(val id: UUID, val name: String, val type: org.jellyfin.sdk.model.api.CollectionType? = null) : Route
    data class Genre(val name: String, val parentId: UUID?) : Route
    data class Detail(val id: UUID) : Route
    data class Player(
        val itemId: UUID,
        val fromStart: Boolean = false,
        val mediaSourceId: String? = null,
        val audioIndex: Int? = null,
        val subtitleIndex: Int? = null,
    ) : Route
    data object Profiles : Route
}

/**
 * Back stack entry. [holders] keeps per-screen state objects alive while the entry is on the
 * stack (so returning to a screen restores its data and scroll position without reloading).
 */
class Entry(val route: Route) {
    val holders = HashMap<String, Any>()
    var focusKey: String? = null
    var pendingRestore = false

    fun close() = holders.values.forEach { (it as? AutoCloseable)?.close() }
}

class Navigator {
    val stack = mutableStateListOf(Entry(Route.Home))
    val top get() = stack.last()

    fun push(route: Route) { stack.add(Entry(route)) }

    /** @return false when already at the root (caller decides whether to exit). */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex).close()
        top.pendingRestore = true
        return true
    }

    /** Tabs don't stack: Home stays at the root and at most one other tab sits above it. */
    fun tab(tab: Route.Tab) {
        if (top.route == tab) return
        while (stack.size > 1) stack.removeAt(stack.lastIndex).close()
        if (tab != Route.Home) push(tab) else top.pendingRestore = true
    }

    fun replace(route: Route) {
        stack.removeAt(stack.lastIndex).close()
        push(route)
    }

    fun reset() {
        stack.forEach { it.close() }
        stack.clear()
        stack.add(Entry(Route.Home))
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("No navigator") }
val LocalEntry = staticCompositionLocalOf<Entry> { error("No entry") }

/** Screen state that lives as long as the back stack entry (a lightweight ViewModel substitute). */
@Composable
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> entryState(key: String = T::class.java.name, crossinline create: () -> T): T {
    val entry = LocalEntry.current
    return entry.holders.getOrPut(key) { create() } as T
}
