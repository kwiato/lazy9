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
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Palette
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

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
    val dnd = remember { DragDrop() }
    var cardEditor by remember { mutableStateOf(false) }

    // Bumped to drop whatever is typed into the persistent keypad: when the
    // launcher goes to the background (an app was opened) and on HOME.
    var searchEpoch by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { searchEpoch++ }

    // HOME closes whatever is open; pressed on the bare desktop it returns to the first page.
    LaunchedEffect(homeEvent) {
        if (homeEvent.count == 0) return@LaunchedEffect
        val hadOverlay = drawerOpen || searchOpen || widgetsOpen || homeMenu || settingsOpen || lockHelp
        drawerOpen = false; searchOpen = false; widgetsOpen = false
        homeMenu = false; settingsOpen = false; lockHelp = false; cardEditor = false
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
            cardEditor -> {
                cardEditor = false
                searchOpen = false
            }
            widgetsOpen -> widgetsOpen = false
            searchOpen -> searchOpen = false
            drawerOpen -> drawerOpen = false
        }
    }

    // An app dragged out of the drawer or off the dock was let go.
    SideEffect {
        dnd.onDrop = {
            val app = dnd.app
            val from = dnd.fromSlot
            val slot = dnd.hoverSlot
            val remove = dnd.overRemove
            val cell = dnd.cellAt?.invoke(dnd.pointer)
            val page = pagerState.currentPage
            dnd.app = null
            dnd.fromSlot = null
            dnd.hoverSlot = null
            dnd.overRemove = false
            dnd.removable = false
            drawerOpen = false
            if (app != null) {
                when {
                    // off the dock: remove, swap with another slot, or move to a free desktop cell
                    from != null && remove -> vm.setDock(from, null)
                    from != null && slot != null -> vm.swapDock(from, slot)
                    from != null -> if (cell != null && vm.addAppAt(app.key, page, cell.x, cell.y)) vm.setDock(from, null)
                    // out of the drawer: into a dock slot, or onto a free desktop cell
                    slot != null -> vm.setDock(slot, app.key)
                    cell != null -> vm.addAppAt(app.key, page, cell.x, cell.y)
                }
            }
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
            Workspace(vm, widgets, host, pagerState, dnd, Modifier.weight(1f))
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
                    Box(Modifier.weight(1f)) {
                        T9Panel(vm, host, Modifier.fillMaxWidth(vm.state.cardWidth), resetKey = searchEpoch)
                    }
                    SideDock(
                        vm, host, pickApp, dnd,
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
                    dnd = dnd,
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
                dnd = dnd,
                onDrop = { dnd.onDrop() },
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
            T9Search(vm, origin = searchOrigin, reveal = { reveal }, host = host) {
                // While its looks are being edited the card stays put as the preview.
                if (!cardEditor) searchOpen = false
            }
        }

        // Shown while a desktop item or a dock app is dragged: drop here to take it off.
        if (dnd.removable) {
            val over = dnd.overRemove
            val reach = with(LocalDensity.current) { 28.dp.toPx() }
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .onGloballyPositioned { dnd.removeZone = it.boundsInRoot().inflate(reach) }
                    .background(if (over) Color(0xFFD64545) else ScrimColor, RoundedCornerShape(50))
                    .padding(horizontal = 26.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Close, null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.drag_remove), color = Color.White)
            }
        }

        if (cardEditor) {
            CardEditor(vm, Modifier.align(Alignment.TopCenter)) {
                cardEditor = false
                searchOpen = false
            }
        }

        // The app being dragged out of the drawer or off the dock rides under the finger, above everything.
        dnd.app?.let { app ->
            AppIcon(
                app,
                Modifier
                    .offset {
                        IntOffset(
                            (dnd.pointer.x - 40.dp.toPx()).roundToInt(),
                            (dnd.pointer.y - 50.dp.toPx()).roundToInt(),
                        )
                    }
                    .size(80.dp, 100.dp),
            )
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
        ModalBottomSheet(
            onDismissRequest = { settingsOpen = false },
            // Opens at full height: half-open it would cut the last settings off.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
            ) {
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
                val toggleContacts = { on: Boolean ->
                    vm.setContacts(on)
                    if (on) host.requestContactAccess()
                }
                SheetRow(
                    Icons.Outlined.Call,
                    stringResource(R.string.settings_contacts),
                    stringResource(R.string.settings_contacts_desc),
                    trailing = { Switch(checked = vm.state.contacts, onCheckedChange = toggleContacts) },
                ) { toggleContacts(!vm.state.contacts) }
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
                SheetRow(
                    Icons.Outlined.InvertColors,
                    stringResource(R.string.settings_mono),
                    stringResource(R.string.settings_mono_desc),
                    trailing = { Switch(checked = vm.state.monoIcons, onCheckedChange = { vm.setMonoIcons(it) }) },
                ) { vm.setMonoIcons(!vm.state.monoIcons) }
                SheetRow(
                    Icons.Outlined.Palette,
                    stringResource(R.string.settings_card),
                    stringResource(R.string.settings_card_desc),
                ) {
                    // Edited over the real card, so every change shows as it is made.
                    settingsOpen = false
                    cardEditor = true
                    if (!vm.state.persistentSearch) searchOpen = true
                }
                SheetRow(
                    Icons.Outlined.FileUpload,
                    stringResource(R.string.settings_export),
                    stringResource(R.string.settings_export_desc),
                ) { host.exportSettings() }
                SheetRow(
                    Icons.Outlined.FileDownload,
                    stringResource(R.string.settings_import),
                    stringResource(R.string.settings_import_desc),
                ) {
                    settingsOpen = false
                    host.importSettings()
                }
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

private val CARD_COLORS = listOf(
    0x171A21L, 0x000000L, 0x3A3F4BL, 0xFFFFFFL, 0x1B3558L, 0x1D4035L, 0x4A2338L, 0x4A3A1CL,
)

/**
 * Floats at the top of the screen while the real T9 card stays visible at the
 * bottom, so colour, opacity, size and text changes are seen as they are made.
 */
@Composable
private fun CardEditor(vm: LauncherViewModel, modifier: Modifier = Modifier, onDone: () -> Unit) {
    Surface(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures {} }, // taps never reach the desktop
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                for (rgb in CARD_COLORS) {
                    val selected = vm.state.cardColor == rgb
                    Box(
                        Modifier
                            .size(34.dp)
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            )
                            .padding(if (selected) 5.dp else 1.dp)
                            .background(Color(0xFF000000 or rgb), CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { vm.setCardColor(rgb) },
                    )
                }
            }
            val s = vm.state
            SliderRow(R.string.settings_card_opacity, s.cardAlpha, 0f..1f, "${(s.cardAlpha * 100).roundToInt()}%") {
                vm.setCardAlpha(it)
            }
            SliderRow(R.string.card_width, s.cardWidth, 0.5f..1f, "${(s.cardWidth * 100).roundToInt()}%") {
                vm.setCardWidth(it)
            }
            SliderRow(R.string.card_height, s.keyHeight, 36f..88f, "${s.keyHeight.roundToInt()}") {
                vm.setKeyHeight(it)
            }
            SliderRow(R.string.card_digits, s.digitSize, 12f..40f, "${s.digitSize.roundToInt()}") {
                vm.setDigitSize(it)
            }
            SliderRow(R.string.card_letters, s.letterSize, 6f..20f, "${s.letterSize.roundToInt()}") {
                vm.setLetterSize(it)
            }
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    shown: String,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            shown,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(38.dp),
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
