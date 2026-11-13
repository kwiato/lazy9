package io.github.kwiato.lazy9.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.kwiato.lazy9.LauncherHost
import io.github.kwiato.lazy9.LauncherViewModel
import io.github.kwiato.lazy9.R
import io.github.kwiato.lazy9.data.AppEntry
import kotlin.math.roundToInt

/**
 * The list of all apps. Once the list is at its top, dragging further down
 * drags the whole drawer with the finger; letting go past a third of the
 * screen (or with a downward flick) closes it, otherwise it springs back.
 *
 * @param pickForSlot when set, the drawer is choosing an app for that dock slot
 *        instead of launching.
 */
@Composable
fun AppDrawer(
    vm: LauncherViewModel,
    host: LauncherHost,
    pickForSlot: Int?,
    /** False while the drawer is animating away. */
    active: Boolean,
    onClose: () -> Unit,
    onAddToHome: (AppEntry) -> Unit,
) {
    val close by rememberUpdatedState(onClose)
    val flickPx = with(LocalDensity.current) { 900.dp.toPx() }
    var height by remember { mutableFloatStateOf(0f) }
    var pull by remember { mutableFloatStateOf(0f) } // how far the drawer is dragged down, px

    // Reopened before the closing animation finished: this instance is reused,
    // so it must not come back still dragged off-screen.
    LaunchedEffect(active) { if (active) pull = 0f }

    val dragToClose = remember {
        object : NestedScrollConnection {
            // Dragging back up while the drawer is displaced moves the drawer, not the list.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull <= 0f || available.y >= 0f) return Offset.Zero
                val before = pull
                pull = (pull + available.y).coerceAtLeast(0f)
                return Offset(0f, pull - before)
            }

            // The list is at its top and the finger keeps going down: the drawer follows.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pull += available.y
                return Offset(0f, available.y)
            }

            // Finger lifted.
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull <= 0f) return Velocity.Zero
                val shouldClose = available.y > flickPx || (available.y >= 0f && pull > height / 3f)
                if (shouldClose) {
                    animate(pull, height.coerceAtLeast(pull), animationSpec = tween(160)) { v, _ -> pull = v }
                    close()
                } else {
                    animate(pull, 0f, animationSpec = spring()) { v, _ -> pull = v }
                }
                return available
            }
        }
    }

    val (regular, private) = remember(vm.apps) { vm.apps.partition { !it.isPrivate } }
    val space = vm.privateSpace

    Column(
        Modifier
            .fillMaxSize()
            .onSizeChanged { height = it.height.toFloat() }
            .offset { IntOffset(0, pull.roundToInt()) }
            .background(ScrimColor)
            .pointerInput(Unit) {} // touches never fall through to the desktop underneath
            .systemBarsPadding(),
    ) {
        if (pickForSlot != null) {
            Text(
                stringResource(R.string.drawer_pick_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 4.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(dragToClose),
        ) {
            items(regular, key = { it.key }) { app ->
                DrawerApp(app, vm, host, pickForSlot, onClose, onAddToHome)
            }

            // Private Space: a lockable section at the end. Never offered for the dock.
            if (space != null && pickForSlot == null) {
                item(key = "private-header", span = { GridItemSpan(maxLineSpan) }) {
                    PrivateSpaceHeader(locked = space.locked) { vm.setPrivateSpaceLocked(!space.locked) }
                }
                if (!space.locked) {
                    items(private, key = { it.key }) { app ->
                        DrawerApp(app, vm, host, null, onClose, onAddToHome)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateSpaceHeader(locked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            contentDescription = null,
            tint = Color.White,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(R.string.private_space),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(if (locked) R.string.private_unlock else R.string.private_lock),
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerApp(
    app: AppEntry,
    vm: LauncherViewModel,
    host: LauncherHost,
    pickForSlot: Int?,
    onClose: () -> Unit,
    onAddToHome: (AppEntry) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            app,
            Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = {
                        if (pickForSlot != null) vm.setDock(pickForSlot, app.key) else vm.launch(app)
                        onClose()
                    },
                    onLongClick = if (pickForSlot != null) null else {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu = true
                        }
                    },
                ),
        )
        AppMenu(menu, { menu = false }, app, vm, host) {
            // Private Space apps stay inside their section: pinning one to the
            // desktop or dock would expose it while the space is locked.
            if (!app.isPrivate) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_add_home)) },
                    leadingIcon = { Icon(Icons.Outlined.Home, null) },
                    onClick = {
                        menu = false
                        onAddToHome(app)
                    },
                )
                if (app.key !in vm.state.dock && vm.state.dock.any { it == null || it !in vm.appsByKey }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_add_dock)) },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                        onClick = {
                            menu = false
                            vm.addToDock(app.key)
                        },
                    )
                }
            }
        }
    }
}
