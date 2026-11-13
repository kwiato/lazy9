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
    /** The T9 keypad sits on the home screen with a column of three apps beside it, replacing the dock. */
    val persistentSearch: Boolean = false,
    /** Background of the T9 card: 0xRRGGBB and its opacity, 0 (invisible) to 1. */
    val cardColor: Long = 0x171A21,
    val cardAlpha: Float = 1f,
    /** Draw app icons in one colour (the apps' own monochrome artwork where they ship it). */
    val monoIcons: Boolean = false,
    val nextId: Long = 1,
)
