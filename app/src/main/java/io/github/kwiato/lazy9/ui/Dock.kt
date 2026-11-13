package io.github.kwiato.lazy9.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kwiato.lazy9.LauncherHost
import io.github.kwiato.lazy9.LauncherViewModel
import io.github.kwiato.lazy9.R

/** Five slots: two apps, the T9 search button, two apps. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Dock(
    vm: LauncherViewModel,
    host: LauncherHost,
    onSearch: () -> Unit,
    onSearchPosition: (Offset) -> Unit,
    onPickApp: (slot: Int) -> Unit,
    dnd: DragDrop,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(76.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (slot in listOf(0, 1, SEARCH, 2, 3)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                if (slot == SEARCH) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .onGloballyPositioned { onSearchPosition(it.boundsInRoot().center) }
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.92f))
                            .clickable(onClick = onSearch),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Dialpad,
                            contentDescription = stringResource(R.string.dock_search),
                            tint = Color(0xFF1F2430),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                } else {
                    DockSlot(vm, host, slot, onPickApp, dnd)
                }
            }
        }
    }
}

private const val SEARCH = -1

/** The column of three apps beside the persistent T9 keypad. */
@Composable
fun SideDock(
    vm: LauncherViewModel,
    host: LauncherHost,
    onPickApp: (slot: Int) -> Unit,
    dnd: DragDrop,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        for (slot in 0 until 3) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                DockSlot(vm, host, slot, onPickApp, dnd)
            }
        }
    }
}

/**
 * One dock app, or — when empty — an invisible spot to hold. Holding an app
 * opens its menu; moving on from there drags it to another slot, onto the
 * desktop, or to "Remove".
 */
@Composable
private fun DockSlot(
    vm: LauncherViewModel,
    host: LauncherHost,
    slot: Int,
    onPickApp: (slot: Int) -> Unit,
    dnd: DragDrop,
) {
    val haptic = LocalHapticFeedback.current
    // The slot reports where it is, so icons dragged over it can be dropped in.
    DisposableEffect(slot) { onDispose { dnd.slots.remove(slot) } }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { dnd.slots[slot] = it.boundsInRoot() },
        contentAlignment = Alignment.Center,
    ) {
        if (dnd.hoverSlot == slot) {
            Box(
                Modifier
                    .size(66.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
            )
        }
        val app = vm.state.dock.getOrNull(slot)?.let { vm.appsByKey[it] }
        if (app != null) {
            var menu by remember { mutableStateOf(false) }
            var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Box(
                Modifier
                    .onGloballyPositioned { coords = it }
                    .alpha(if (dnd.fromSlot == slot) 0.3f else 1f)
                    .pointerInput(app.key, slot) {
                        var start = Offset.Zero
                        var dragging = false
                        detectLongPressDrag(
                            onStart = { pos ->
                                start = pos
                                dragging = false
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu = true
                            },
                            onDrag = { change ->
                                val c = coords
                                if (c != null && c.isAttached) {
                                    val root = c.localToRoot(change.position)
                                    if (!dragging &&
                                        (change.position - start).getDistance() > viewConfiguration.touchSlop
                                    ) {
                                        dragging = true
                                        menu = false
                                        dnd.pointer = root
                                        dnd.fromSlot = slot
                                        dnd.removable = true
                                        dnd.app = app
                                    }
                                    if (dragging) dnd.track(root)
                                }
                            },
                            onEnd = {
                                if (dragging) dnd.onDrop()
                                dragging = false
                            },
                        )
                    },
            ) {
                AppIcon(
                    app,
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { vm.launch(app) },
                    showLabel = false,
                    iconSize = 54.dp,
                )
            }
            AppMenu(menu, { menu = false }, app, vm, host) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_replace_dock)) },
                    leadingIcon = { Icon(Icons.Outlined.SwapHoriz, null) },
                    onClick = {
                        menu = false
                        onPickApp(slot)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_remove_dock)) },
                    leadingIcon = { Icon(Icons.Outlined.Close, null) },
                    onClick = {
                        menu = false
                        vm.setDock(slot, null)
                    },
                )
            }
        } else if (vm.loaded) {
            // An empty slot draws nothing; holding it picks an app.
            val label = stringResource(R.string.dock_empty)
            Box(
                Modifier
                    .size(54.dp)
                    .semantics { contentDescription = label }
                    .pointerInput(slot) {
                        detectTapGestures(onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPickApp(slot)
                        })
                    },
            )
        }
    }
}
