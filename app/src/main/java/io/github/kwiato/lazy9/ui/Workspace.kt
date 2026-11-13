package io.github.kwiato.lazy9.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kwiato.lazy9.LauncherHost
import io.github.kwiato.lazy9.LauncherViewModel
import io.github.kwiato.lazy9.R
import io.github.kwiato.lazy9.WidgetController
import io.github.kwiato.lazy9.data.HomeItem
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** An item being dragged. Positions are in workspace coordinates. */
private class DragInfo(val itemId: Long, val grab: Offset, val start: Offset) {
    var pointer by mutableStateOf(start)
    var moved by mutableStateOf(false)
}

/** The swipeable home pages: a grid of app icons and widgets, rearranged by long-press drag. */
@Composable
fun Workspace(
    vm: LauncherViewModel,
    widgets: WidgetController,
    host: LauncherHost,
    pagerState: PagerState,
    dnd: DragDrop,
    modifier: Modifier = Modifier,
) {
    val state = vm.state
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    var drag by remember { mutableStateOf<DragInfo?>(null) }
    var menuId by remember { mutableStateOf<Long?>(null) }
    var resizeId by remember { mutableStateOf<Long?>(null) }
    var wsCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    BoxWithConstraints(modifier.onGloballyPositioned { wsCoords = it }) {
        val cellW = maxWidth / state.cols
        val cellH = maxHeight / state.rows
        val cellWpx = with(density) { cellW.toPx() }
        val cellHpx = with(density) { cellH.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val edgePx = with(density) { 28.dp.toPx() }
        SideEffect {
            vm.cellWpx = cellWpx
            vm.cellHpx = cellHpx
        }

        SideEffect {
            dnd.cellAt = { root ->
                val ws = wsCoords
                if (ws == null || !ws.isAttached) {
                    null
                } else {
                    val p = root - ws.positionInRoot()
                    if (p.x < 0f || p.y < 0f || p.x >= ws.size.width || p.y >= ws.size.height) null
                    else IntOffset(
                        (p.x / cellWpx).toInt().coerceIn(0, state.cols - 1),
                        (p.y / cellHpx).toInt().coerceIn(0, state.rows - 1),
                    )
                }
            }
        }

        fun targetCell(d: DragInfo, item: HomeItem): IntOffset {
            val topLeft = d.pointer - d.grab
            return IntOffset(
                (topLeft.x / cellWpx).roundToInt().coerceIn(0, (state.cols - item.spanX).coerceAtLeast(0)),
                (topLeft.y / cellHpx).roundToInt().coerceIn(0, (state.rows - item.spanY).coerceAtLeast(0)),
            )
        }

        // While dragging: resting at a side edge flips to the neighbouring page
        // (and grows a new page past the last one).
        LaunchedEffect(drag, dnd.app) {
            var atEdgeMs = 0
            while (drag != null || dnd.app != null) {
                delay(50)
                val d = drag
                // How far across the desktop the finger is; null while nothing has moved.
                val x = when {
                    d != null -> if (d.moved) d.pointer.x else null
                    dnd.app != null ->
                        wsCoords?.takeIf { it.isAttached }?.let { dnd.pointer.x - it.positionInRoot().x }
                    else -> break
                }
                val dir = when {
                    x == null || dnd.hoverSlot != null -> 0
                    x < edgePx -> -1
                    x > widthPx - edgePx -> 1
                    else -> 0
                }
                if (dir == 0) {
                    atEdgeMs = 0
                    continue
                }
                atEdgeMs += 50
                if (atEdgeMs < 500) continue
                atEdgeMs = -300
                val target = pagerState.currentPage + dir
                if (target == vm.state.pages && !vm.isPageEmpty(pagerState.currentPage)) vm.addPage()
                if (target in 0 until vm.state.pages) pagerState.animateScrollToPage(target)
            }
        }

        HorizontalPager(
            state = pagerState,
            // Every page stays composed, so an item keeps receiving its drag
            // gesture while the pager scrolls away from its page.
            beyondViewportPageCount = state.pages,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                for (item in state.items) {
                    if (item.page != page) continue
                    key(item.id) {
                        val app = item.appKey?.let { vm.appsByKey[it] }
                        if (app != null || item.widgetId != null) {
                            var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                            val d = drag
                            val lifted = d != null && d.itemId == item.id && d.moved
                            Box(
                                Modifier
                                    .offset(cellW * item.x, cellH * item.y)
                                    .size(cellW * item.spanX, cellH * item.spanY)
                                    .onGloballyPositioned { coords = it }
                                    .alpha(if (lifted) 0.3f else 1f)
                                    .pointerInput(item.id) {
                                        detectLongPressDrag(
                                            onStart = { pos ->
                                                val ws = wsCoords
                                                val c = coords
                                                if (ws != null && c != null && c.isAttached) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    menuId = item.id
                                                    drag = DragInfo(item.id, pos, ws.localPositionOf(c, pos))
                                                }
                                            },
                                            onDrag = { change ->
                                                val cur = drag
                                                val ws = wsCoords
                                                val c = coords
                                                if (cur != null && ws != null && c != null && c.isAttached) {
                                                    cur.pointer = ws.localPositionOf(c, change.position)
                                                    if (!cur.moved &&
                                                        (cur.pointer - cur.start).getDistance() > viewConfiguration.touchSlop
                                                    ) {
                                                        cur.moved = true
                                                        menuId = null
                                                    }
                                                    // Apps (not widgets) can be dropped on the dock.
                                                    val isApp = vm.state.items.find { it.id == cur.itemId }?.appKey != null
                                                    dnd.hoverSlot =
                                                        if (cur.moved && isApp) dnd.slotAt(ws.localToRoot(cur.pointer)) else null
                                                }
                                            },
                                            onEnd = {
                                                val cur = drag
                                                val slot = dnd.hoverSlot
                                                drag = null
                                                dnd.hoverSlot = null
                                                val dropped = vm.state.items.find { it.id == cur?.itemId }
                                                if (cur != null && cur.moved && dropped != null) {
                                                    if (slot != null && dropped.appKey != null) {
                                                        vm.moveItemToDock(dropped.id, slot)
                                                    } else {
                                                        val cell = targetCell(cur, dropped)
                                                        vm.moveItem(dropped.id, pagerState.currentPage, cell.x, cell.y)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    .pointerInput(Unit) { consumeDowns() },
                            ) {
                                if (app != null) {
                                    AppIcon(
                                        app,
                                        Modifier
                                            .fillMaxSize()
                                            .pointerInput(app.key) {
                                                detectTapGestures(onTap = { vm.launch(app) })
                                            },
                                    )
                                    AppMenu(menuId == item.id, { menuId = null }, app, vm, host) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_remove_home)) },
                                            leadingIcon = { Icon(Icons.Outlined.Close, null) },
                                            onClick = {
                                                menuId = null
                                                vm.removeItem(item.id)
                                            },
                                        )
                                    }
                                } else if (item.widgetId != null) {
                                    WidgetView(item.widgetId, widgets)
                                    DropdownMenu(menuId == item.id, onDismissRequest = { menuId = null }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_resize)) },
                                            leadingIcon = { Icon(Icons.Outlined.AspectRatio, null) },
                                            onClick = {
                                                menuId = null
                                                resizeId = item.id
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_remove_widget)) },
                                            leadingIcon = { Icon(Icons.Outlined.Close, null) },
                                            onClick = {
                                                menuId = null
                                                vm.removeItem(item.id)
                                                host.deleteWidget(item.widgetId)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // An app dragged out of the drawer: outline the cell it would land on.
        if (dnd.app != null && dnd.hoverSlot == null) {
            val cell = dnd.cellAt?.invoke(dnd.pointer)
            if (cell != null) {
                val fits = vm.canPlace(null, pagerState.currentPage, cell.x, cell.y, 1, 1)
                Box(
                    Modifier
                        .offset(cellW * cell.x, cellH * cell.y)
                        .size(cellW, cellH)
                        .padding(4.dp)
                        .border(
                            2.dp,
                            if (fits) Color.White.copy(alpha = 0.75f) else Color(0xFFFF6B6B).copy(alpha = 0.85f),
                            RoundedCornerShape(18.dp),
                        ),
                )
            }
        }

        // The lifted item follows the finger; an outline shows where it would land.
        val d = drag
        val item = if (d != null && d.moved) state.items.find { it.id == d.itemId } else null
        if (d != null && item != null) {
            val cell = targetCell(d, item)
            val fits = vm.canPlace(item.id, pagerState.currentPage, cell.x, cell.y, item.spanX, item.spanY)
            val shape = RoundedCornerShape(18.dp)
            if (dnd.hoverSlot == null) Box(
                Modifier
                    .offset(cellW * cell.x, cellH * cell.y)
                    .size(cellW * item.spanX, cellH * item.spanY)
                    .padding(4.dp)
                    .border(
                        2.dp,
                        if (fits) Color.White.copy(alpha = 0.75f) else Color(0xFFFF6B6B).copy(alpha = 0.85f),
                        shape,
                    ),
            )
            Box(
                Modifier
                    .offset {
                        val topLeft = d.pointer - d.grab
                        IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt())
                    }
                    .size(cellW * item.spanX, cellH * item.spanY),
            ) {
                val app = item.appKey?.let { vm.appsByKey[it] }
                if (app != null) {
                    AppIcon(app, Modifier.fillMaxSize())
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .background(Color.White.copy(alpha = 0.22f), shape),
                    )
                }
            }
        }
    }

    val resizing = state.items.find { it.id == resizeId }
    if (resizing != null) ResizeDialog(vm, resizing) { resizeId = null }
}

@Composable
private fun WidgetView(widgetId: Int, widgets: WidgetController) {
    val view = remember(widgetId) { widgets.view(widgetId) }
    val density = LocalDensity.current
    if (view == null) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.widget_unavailable),
                color = Color.White,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        AndroidView(
            factory = {
                (view.parent as? ViewGroup)?.removeView(view)
                view
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    val w = (size.width / density.density).roundToInt()
                    val h = (size.height / density.density).roundToInt()
                    @Suppress("DEPRECATION")
                    view.updateAppWidgetSize(null, w, h, w, h)
                },
        )
    }
}

@Composable
private fun ResizeDialog(vm: LauncherViewModel, item: HomeItem, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.done)) } },
        title = { Text(stringResource(R.string.menu_resize)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Stepper(
                    label = stringResource(R.string.widget_width),
                    value = item.spanX,
                    canGrow = vm.canPlace(item.id, item.page, item.x, item.y, item.spanX + 1, item.spanY),
                    onChange = { vm.resizeItem(item.id, it, item.spanY) },
                )
                Stepper(
                    label = stringResource(R.string.widget_height),
                    value = item.spanY,
                    canGrow = vm.canPlace(item.id, item.page, item.x, item.y, item.spanX, item.spanY + 1),
                    onChange = { vm.resizeItem(item.id, item.spanX, it) },
                )
            }
        },
    )
}

@Composable
private fun Stepper(label: String, value: Int, canGrow: Boolean, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        FilledTonalIconButton(onClick = { onChange(value - 1) }, enabled = value > 1) {
            Icon(Icons.Outlined.Remove, null)
        }
        Text(value.toString(), Modifier.width(40.dp), textAlign = TextAlign.Center, fontSize = 18.sp)
        FilledTonalIconButton(onClick = { onChange(value + 1) }, enabled = canGrow) {
            Icon(Icons.Outlined.Add, null)
        }
    }
}
