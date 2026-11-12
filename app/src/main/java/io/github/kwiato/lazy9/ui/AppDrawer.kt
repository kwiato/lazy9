package io.github.kwiato.lazy9.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.kwiato.lazy9.LauncherHost
import io.github.kwiato.lazy9.LauncherViewModel
import io.github.kwiato.lazy9.R
import io.github.kwiato.lazy9.data.AppEntry

/**
 * The list of all apps.
 *
 * @param pickForSlot when set, the drawer is choosing an app for that dock slot
 *        instead of launching.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(
    vm: LauncherViewModel,
    host: LauncherHost,
    pickForSlot: Int?,
    onClose: () -> Unit,
    onAddToHome: (AppEntry) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val closeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val close by rememberUpdatedState(onClose)

    // Pulling down past the top of the list closes the drawer.
    val pullToClose = remember {
        object : NestedScrollConnection {
            var pulled = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pulled += available.y
                    if (pulled > closeThreshold) {
                        pulled = 0f
                        close()
                    }
                } else if (consumed.y != 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
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
                .nestedScroll(pullToClose),
        ) {
            items(vm.apps, key = { it.key }) { app ->
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
                                    if (pickForSlot != null) vm.setDock(pickForSlot, app.key)
                                    else vm.launch(app)
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
    }
}
