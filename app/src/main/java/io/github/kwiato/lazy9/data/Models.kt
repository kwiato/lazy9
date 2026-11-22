package io.github.kwiato.lazy9.data

import android.content.ComponentName
import android.os.UserHandle
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.Serializable

/** A launchable activity. [key] is stable across restarts: "pkg/class#userSerial". */
@Immutable
class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
    val t9: T9Name,
    /** Lives in the Private Space profile — only listed while that is unlocked. */
    val isPrivate: Boolean = false,
)

/** The device's Private Space profile, if it has one. */
class PrivateSpace(val user: UserHandle, val locked: Boolean)

/** One thing on a home page: an app shortcut ([appKey]) or a widget ([widgetId]). */
@Serializable
data class HomeItem(
    val id: Long,
    val page: Int,
    val x: Int,
    val y: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val appKey: String? = null,
    val widgetId: Int? = null,
)

@Serializable
data class LauncherState(
    val pages: Int = 2,
    val cols: Int = 4,
    val rows: Int = 5,
    val items: List<HomeItem> = emptyList(),
    /** The four app slots of the dock (the fifth, middle slot is the T9 button). */
    val dock: List<String?> = listOf(null, null, null, null),
    /** Launch counts per app key — ranks T9 results. */
    val usage: Map<String, Int> = emptyMap(),
    val autoLaunch: Boolean = false,
    /** App keys in the order they were last launched, newest first. */
    val recent: List<String> = emptyList(),
    /** The empty keypad lists the last launched apps instead of the most used ones. */
    val showRecent: Boolean = false,
    /**
     * Experimental: launch counts multiply the match score instead of only
     * breaking ties, so an often used app climbs over a better but rarely used match.
     */
    val usageBoost: Boolean = false,
    /** Apps the user chose to keep out of T9 results. */
    val hiddenT9: Set<String> = emptySet(),
    /** The T9 keypad sits on the home screen with a column of three apps beside it, replacing the dock. */
    val persistentSearch: Boolean = false,
    /** Background of the T9 card: 0xRRGGBB and its opacity, 0 (invisible) to 1. */
    val cardColor: Long = 0x171A21,
    val cardAlpha: Float = 1f,
    /** Share of the available width the T9 card takes, 0.5 to 1. */
    val cardWidth: Float = 1f,
    /** Height of one keypad row, dp. */
    val keyHeight: Float = 56f,
    /** Size of the digits and of the letters under them, sp. */
    val digitSize: Float = 22f,
    val letterSize: Float = 10f,
    /** Draw app icons in one colour (the apps' own monochrome artwork where they ship it). */
    val monoIcons: Boolean = false,
    /** Colour of Private Space apps' monochrome icons: [PRIVATE_TINT_OFF], [PRIVATE_TINT_WALLPAPER] or 0xRRGGBB. */
    val privateTint: Long = PRIVATE_TINT_WALLPAPER,
    /** Small mark in the bottom-right corner of Private Space icons: "none", "lock" or "shield". */
    val privateMark: String = PRIVATE_MARK_LOCK,
    /** Starred contacts show up in T9 results; tapping one calls it. */
    val contacts: Boolean = false,
    val nextId: Long = 1,
)

const val PRIVATE_TINT_OFF = -1L
const val PRIVATE_TINT_WALLPAPER = -2L
const val PRIVATE_MARK_NONE = "none"
const val PRIVATE_MARK_LOCK = "lock"
const val PRIVATE_MARK_SHIELD = "shield"
