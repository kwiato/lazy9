package io.github.kwiato.lazy9.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.kwiato.lazy9.HomeEvent
import io.github.kwiato.lazy9.LauncherHost
import io.github.kwiato.lazy9.LauncherViewModel
import io.github.kwiato.lazy9.LockService
import io.github.kwiato.lazy9.R
import io.github.kwiato.lazy9.WidgetController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun LauncherScreen(
    vm: LauncherViewModel,
    widgets: WidgetController,
    host: LauncherHost,
    homeEvent: HomeEvent,
    revealPage: Int,
    onRevealed: () -> Unit,
) {
    val pagerState = rememberPagerState { vm.state.pages }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var drawerOpen by remember { mutableStateOf(false) }
    var pickSlot by remember { mutableStateOf<Int?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var widgetsOpen by remember { mutableStateOf(false) }
    var homeMenu by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var lockHelp by remember { mutableStateOf(false) }

    // Bumped to drop whatever is typed into the persistent keypad: when the
    // launcher goes to the background (an app was opened) and on HOME.
    var searchEpoch by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { searchEpoch++ }

    // HOME closes whatever is open; pressed on the bare desktop it returns to the first page.
    LaunchedEffect(homeEvent) {
        if (homeEvent.count == 0) return@LaunchedEffect
        val hadOverlay = drawerOpen || searchOpen || widgetsOpen || homeMenu || settingsOpen || lockHelp
        drawerOpen = false; searchOpen = false; widgetsOpen = false
        homeMenu = false; settingsOpen = false; lockHelp = false
        searchEpoch++
        if (homeEvent.onHome && !hadOverlay) pagerState.animateScrollToPage(0)
    }

    // A widget was just placed: show the page it landed on.
    LaunchedEffect(revealPage) {
        if (revealPage < 0) return@LaunchedEffect
        onRevealed()
        if (revealPage < pagerState.pageCount) pagerState.animateScrollToPage(revealPage)
    }

    // The home screen is the bottom of the back stack: back only closes overlays.
    BackHandler {
        when {
            widgetsOpen -> widgetsOpen = false
            searchOpen -> searchOpen = false
            drawerOpen -> drawerOpen = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { if (!host.lockScreen()) lockHelp = true },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            homeMenu = true
                        },
                    )
                }
                .pointerInput(Unit) {
                    val threshold = 56.dp.toPx()
                    var total = 0f
                    var fired = false
                    detectVerticalDragGestures(
                        onDragStart = {
                            total = 0f
                            fired = false
                        },
                        onVerticalDrag = { _, dy ->
                            total += dy
                            if (!fired && total < -threshold) {
                                fired = true
                                pickSlot = null
                                drawerOpen = true
                            } else if (!fired && total > threshold) {
                                fired = true
                                host.expandNotifications()
                            }
                        },
                    )
                }
                .systemBarsPadding(),
        ) {
            Workspace(vm, widgets, host, pagerState, Modifier.weight(1f))
            PageDots(count = vm.state.pages, current = pagerState.currentPage)
            val pickApp = { slot: Int ->
                pickSlot = slot
                drawerOpen = true
            }
            if (vm.state.persistentSearch) {
                // The keypad lives on the desktop; three apps stand beside it.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 8.dp),
                ) {
                    T9Panel(vm, Modifier.weight(1f), resetKey = searchEpoch)
                    SideDock(
                        vm, host, pickApp,
                        Modifier
                            .width(76.dp)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Dock(
                    vm = vm,
                    host = host,
                    onSearch = { searchOpen = true },
                    onSearchPosition = { searchOrigin = it },
                    onPickApp = pickApp,
                )
            }
        }

        AnimatedVisibility(
            visible = drawerOpen,
            // Fixed, short durations: the default spring has a long invisible
            // tail during which the faded-out drawer kept swallowing touches.
            enter = slideInVertically(tween(220)) { it / 3 } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(140)) { it / 3 } + fadeOut(tween(140)),
        ) {
            AppDrawer(
                vm = vm,
                host = host,
                active = transition.targetState == EnterExitState.Visible,
                pickForSlot = pickSlot,
                onClose = { drawerOpen = false },
                onAddToHome = { app ->
                    val page = vm.addApp(app.key, pagerState.currentPage)
                    drawerOpen = false
                    scope.launch { pagerState.animateScrollToPage(page) }
                },
            )
        }

        AnimatedVisibility(
            visible = widgetsOpen,
            enter = slideInVertically(tween(220)) { it / 3 } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(140)) { it / 3 } + fadeOut(tween(140)),
        ) {
            WidgetPicker(vm) { info ->
                widgetsOpen = false
                host.addWidget(info, pagerState.currentPage)
            }
        }

        // The dim layer only fades; the card itself ripples out of the T9 button
        // (and back into it on close).
        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)),
        ) {
            val reveal by transition.animateFloat(
                transitionSpec = { tween(if (targetState == EnterExitState.Visible) 340 else 220) },
                label = "t9 reveal",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            T9Search(vm, origin = searchOrigin, reveal = { reveal }) { searchOpen = false }
        }
    }

    if (homeMenu) {
        val page = pagerState.currentPage
        ModalBottomSheet(onDismissRequest = { homeMenu = false }) {
            Column(Modifier.navigationBarsPadding()) {
                SheetRow(Icons.Outlined.Widgets, stringResource(R.string.home_widgets)) {
                    homeMenu = false
                    widgetsOpen = true
                }
                SheetRow(Icons.Outlined.Wallpaper, stringResource(R.string.home_wallpaper)) {
                    homeMenu = false
                    host.pickWallpaper()
                }
                SheetRow(Icons.Outlined.Add, stringResource(R.string.home_add_page)) {
                    homeMenu = false
                    val added = vm.addPage()
                    scope.launch { pagerState.animateScrollToPage(added) }
                }
                if (vm.state.pages > 1 && vm.isPageEmpty(page)) {
                    SheetRow(Icons.Outlined.Delete, stringResource(R.string.home_remove_page)) {
                        homeMenu = false
                        vm.removePage(page)
                    }
                }
                SheetRow(Icons.Outlined.Settings, stringResource(R.string.home_settings)) {
                    homeMenu = false
                    settingsOpen = true
                }
            }
        }
    }

    if (settingsOpen) {
        var isDefault by remember { mutableStateOf(host.isDefaultLauncher()) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isDefault = host.isDefaultLauncher() }
        ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
            Column(Modifier.navigationBarsPadding()) {
                SheetRow(
                    Icons.Outlined.Home,
                    stringResource(R.string.settings_default_launcher),
                    stringResource(if (isDefault) R.string.settings_default_yes else R.string.settings_default_no),
                ) { host.openHomeSettings() }
                SheetRow(
                    Icons.Outlined.PowerSettingsNew,
                    stringResource(R.string.settings_gestures),
                    stringResource(
                        if (LockService.connected) R.string.settings_gestures_on else R.string.settings_gestures_off,
                    ),
                ) { host.openAccessibilitySettings() }
                SheetRow(
                    Icons.Outlined.Dialpad,
                    stringResource(R.string.settings_autolaunch),
                    stringResource(R.string.settings_autolaunch_desc),
                    trailing = { Switch(checked = vm.state.autoLaunch, onCheckedChange = { vm.setAutoLaunch(it) }) },
                ) { vm.setAutoLaunch(!vm.state.autoLaunch) }
                SheetRow(
                    Icons.Outlined.PushPin,
                    stringResource(R.string.settings_persistent),
                    stringResource(R.string.settings_persistent_desc),
                    trailing = {
                        Switch(
                            checked = vm.state.persistentSearch,
                            onCheckedChange = { vm.setPersistentSearch(it) },
                        )
                    },
                ) { vm.setPersistentSearch(!vm.state.persistentSearch) }
            }
        }
    }

    if (lockHelp) {
        AlertDialog(
            onDismissRequest = { lockHelp = false },
            title = { Text(stringResource(R.string.lock_dialog_title)) },
            text = { Text(stringResource(R.string.lock_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    lockHelp = false
                    host.openAccessibilitySettings()
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { lockHelp = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > 1) {
            repeat(count) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (i == current) 8.dp else 6.dp)
                        .background(Color.White.copy(alpha = if (i == current) 0.95f else 0.45f), CircleShape),
                )
            }
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
