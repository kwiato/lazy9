package io.github.kwiato.lazy9.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kwiato.lazy9.LauncherHost
import io.github.kwiato.lazy9.LauncherViewModel
import io.github.kwiato.lazy9.R
import io.github.kwiato.lazy9.data.AppEntry

/** Always dark: everything the launcher draws sits on top of the wallpaper or a dark scrim. */
@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(LocalContext.current)
    else darkColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

/** The overlay background (drawer, widget picker, T9 panel). */
val ScrimColor = Color(0xF7101319)

@Composable
fun AppIcon(
    app: AppEntry,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    iconSize: Dp = 52.dp,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = if (showLabel) null else app.label,
            modifier = Modifier.size(iconSize),
        )
        if (showLabel) {
            Text(
                text = app.label,
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = LocalTextStyle.current.copy(
                    shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 4f),
                ),
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp),
            )
        }
    }
}

/**
 * The long-press bubble for an app: its app shortcuts, then [extra] entries
 * (add/remove, depending on where the icon lives), then App info and Uninstall.
 */
@Composable
fun AppMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    app: AppEntry,
    vm: LauncherViewModel,
    host: LauncherHost,
    extra: @Composable () -> Unit = {},
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val shortcuts = remember(app.key) { vm.repo.shortcuts(app) }
        shortcuts.forEach { shortcut ->
            DropdownMenuItem(
                text = { Text((shortcut.shortLabel ?: shortcut.longLabel ?: "").toString()) },
                onClick = {
                    onDismiss()
                    vm.repo.startShortcut(shortcut)
                },
            )
        }
        if (shortcuts.isNotEmpty()) HorizontalDivider()
        extra()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_app_info)) },
            leadingIcon = { Icon(Icons.Outlined.Info, null) },
            onClick = {
                onDismiss()
                host.appInfo(app)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_uninstall)) },
            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
            onClick = {
                onDismiss()
                host.uninstall(app)
            },
        )
    }
}

/**
 * Drag-and-drop shared by the desktop, the dock and the app drawer. All
 * positions are in root (window) coordinates.
 */
class DragDrop {
    /** Where each dock slot currently is on screen. */
    val slots = mutableStateMapOf<Int, Rect>()

    /** The dock slot under the finger while something droppable is dragged. */
    var hoverSlot by mutableStateOf<Int?>(null)

    /** An app being dragged out of the drawer or off the dock, and where the finger is. */
    var app by mutableStateOf<AppEntry?>(null)
    var pointer by mutableStateOf(Offset.Zero)

    /** The dock slot [app] was picked up from; null when it came out of the drawer. */
    var fromSlot by mutableStateOf<Int?>(null)

    /** True while the dragged thing can be dropped on the "Remove" target. */
    var removable by mutableStateOf(false)
    var removeZone = Rect.Zero
    var overRemove by mutableStateOf(false)

    /** Finishes a drag of [app]; set by the launcher screen. */
    var onDrop: () -> Unit = {}

    /** Follows the finger: which dock slot or target it is over. */
    fun track(p: Offset) {
        pointer = p
        overRemove = removable && removeZone.contains(p)
        hoverSlot = if (overRemove) null else slotAt(p)
    }

    /** Desktop cell under a point, or null outside the desktop. Set by the workspace. */
    var cellAt: ((Offset) -> IntOffset?)? = null

    fun slotAt(p: Offset): Int? = slots.entries.firstOrNull { it.value.contains(p) }?.key
}

/**
 * Long press, then drag — detected on the *initial* pass, so it also works on
 * top of children that swallow touches (hosted widgets). Nothing is consumed
 * until the long press fires; from then on the gesture is ours, which cancels
 * whatever the child was doing. [onDrag] also fires for the final (up) event.
 */
suspend fun PointerInputScope.detectLongPressDrag(
    onStart: (Offset) -> Unit,
    onDrag: (PointerInputChange) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        var aborted = false
        val heldLongEnough = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (!aborted) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed ||
                    (change.position - down.position).getDistance() > slop
                ) {
                    aborted = true
                }
            }
        } == null
        if (aborted || !heldLongEnough) return@awaitEachGesture

        onStart(down.position)
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                onDrag(change)
                change.consume()
                if (!change.pressed) break
            }
        } finally {
            onEnd()
        }
    }
}

/** Marks touches that start here as handled, so background gestures ignore them. */
suspend fun PointerInputScope.consumeDowns() {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
}
