package io.github.kwiato.lazy9.ui

import android.appwidget.AppWidgetProviderInfo
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kwiato.lazy9.LauncherHost
import io.github.kwiato.lazy9.LauncherViewModel
import io.github.kwiato.lazy9.R
import io.github.kwiato.lazy9.WidgetController
import io.github.kwiato.lazy9.data.HomeItem
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** An item being dragged. Positions are in workspace coordinates. */
private class DragInfo(val itemId: Long, val grab: Offset, val start: Offset) {
    var pointer by mutableStateOf(start)
    var moved by mutableStateOf(false)
}

/** How far a widget may be resized, in cells, and along which axes. */
private class SpanLimits(
    val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
    val horizontal: Boolean, val vertical: Boolean,
)

/** Resize limits declared by a widget, translated to cells of [cellWpx]×[cellHpx]. */
private fun spanLimits(info: AppWidgetProviderInfo?, cols: Int, rows: Int, cellWpx: Float, cellHpx: Float): SpanLimits {
    if (info == null) return SpanLimits(1, 1, cols, rows, horizontal = true, vertical = true)
    fun minSpan(px: Int, cell: Float, max: Int) =
        if (px <= 0 || cell <= 0f) 1 else ceil(px / cell - 0.05f).toInt().coerceIn(1, max)
    var maxX = cols
    var maxY = rows
    if (Build.VERSION.SDK_INT >= 31) {
        if (info.maxResizeWidth > 0 && cellWpx > 0f) maxX = floor(info.maxResizeWidth / cellWpx).toInt().coerceIn(1, cols)
        if (info.maxResizeHeight > 0 && cellHpx > 0f) maxY = floor(info.maxResizeHeight / cellHpx).toInt().coerceIn(1, rows)
    }
    val minX = minSpan(info.minResizeWidth.takeIf { it > 0 } ?: info.minWidth, cellWpx, cols).coerceAtMost(maxX)
    val minY = minSpan(info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight, cellHpx, rows).coerceAtMost(maxY)
    return SpanLimits(
        minX, minY, maxX, maxY,
        horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
        vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
    )
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
    // Widget in resize mode (frame with handles) and its live, unsnapped bounds while a handle is dragged.
    var resizeId by remember { mutableStateOf<Long?>(null) }
    var resizeRect by remember { mutableStateOf<Rect?>(null) }
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

        val resizing = state.items.find { it.id == resizeId }
        val limits = resizing?.widgetId?.let { id ->
            remember(id, state.cols, state.rows, cellWpx, cellHpx) {
                spanLimits(widgets.manager.getAppWidgetInfo(id), state.cols, state.rows, cellWpx, cellHpx)
            }
        }
        // Cells the dragged frame snaps to (left, top, right, bottom); null when no handle is held.
        val preview = resizeRect?.let { r ->
            IntRect(
                (r.left / cellWpx).roundToInt(),
                (r.top / cellHpx).roundToInt(),
                (r.right / cellWpx).roundToInt(),
                (r.bottom / cellHpx).roundToInt(),
            )
        }
        val previewFits = resizing != null && preview != null &&
            vm.canPlace(resizing.id, resizing.page, preview.left, preview.top, preview.width, preview.height)
        LaunchedEffect(preview) {
            if (preview != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        BackHandler(enabled = resizing != null) {
            resizeId = null
            resizeRect = null
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
                            // A widget being resized already takes the size its frame snaps to.
                            val cells = if (item.id == resizeId && preview != null && previewFits) preview
                            else IntRect(item.x, item.y, item.x + item.spanX, item.y + item.spanY)
                            Box(
                                Modifier
                                    .offset(cellW * cells.left, cellH * cells.top)
                                    .size(cellW * cells.width, cellH * cells.height)
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
                                                        dnd.removable = true
                                                    }
                                                    // Apps (not widgets) can be dropped on the dock;
                                                    // anything can be dropped on "Remove".
                                                    val root = ws.localToRoot(cur.pointer)
                                                    val isApp = vm.state.items.find { it.id == cur.itemId }?.appKey != null
                                                    dnd.overRemove = cur.moved && dnd.removeZone.contains(root)
                                                    dnd.hoverSlot =
                                                        if (cur.moved && isApp && !dnd.overRemove) dnd.slotAt(root) else null
                                                }
                                            },
                                            onEnd = {
                                                val cur = drag
                                                val slot = dnd.hoverSlot
                                                val remove = dnd.overRemove
                                                drag = null
                                                dnd.hoverSlot = null
                                                dnd.overRemove = false
                                                dnd.removable = false
                                                val dropped = vm.state.items.find { it.id == cur?.itemId }
                                                if (cur != null && cur.moved && dropped != null) {
                                                    if (remove) {
                                                        vm.removeItem(dropped.id)
                                                        dropped.widgetId?.let { host.deleteWidget(it) }
                                                    } else if (slot != null && dropped.appKey != null) {
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
                                        val resizable = remember(item.widgetId) {
                                            (widgets.manager.getAppWidgetInfo(item.widgetId)?.resizeMode
                                                ?: AppWidgetProviderInfo.RESIZE_BOTH) != AppWidgetProviderInfo.RESIZE_NONE
                                        }
                                        if (resizable) DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_resize)) },
                                            leadingIcon = { Icon(Icons.Outlined.AspectRatio, null) },
                                            onClick = {
                                                menuId = null
                                                resizeRect = null
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
        if (dnd.app != null && dnd.hoverSlot == null && !dnd.overRemove) {
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
            if (dnd.hoverSlot == null && !dnd.overRemove) Box(
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

        // Resize mode: a frame with handles on the sides and corners; a tap elsewhere ends it.
        if (resizing != null && limits != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            resizeId = null
                            resizeRect = null
                        }
                    },
            )
            val shape = RoundedCornerShape(18.dp)
            if (preview != null) Box(
                Modifier
                    .offset(cellW * preview.left, cellH * preview.top)
                    .size(cellW * preview.width, cellH * preview.height)
                    .padding(4.dp)
                    .border(
                        2.dp,
                        if (previewFits) Color.White.copy(alpha = 0.5f) else Color(0xFFFF6B6B).copy(alpha = 0.85f),
                        shape,
                    ),
            )
            val itemRect = Rect(
                resizing.x * cellWpx,
                resizing.y * cellHpx,
                (resizing.x + resizing.spanX) * cellWpx,
                (resizing.y + resizing.spanY) * cellHpx,
            )
            ResizeFrame(
                rect = resizeRect ?: itemRect,
                limits = limits,
                cellWpx = cellWpx,
                cellHpx = cellHpx,
                gridW = state.cols * cellWpx,
                gridH = state.rows * cellHpx,
                onStart = { resizeRect = itemRect },
                onResize = { resizeRect = it },
                onEnd = {
                    val p = preview
                    if (p != null && previewFits) vm.setItemBounds(resizing.id, p.left, p.top, p.width, p.height)
                    resizeRect = null
                },
            )
        }
    }
}

/** Side of a resize handle's touch target; it lies inside the frame, away from the screen's gesture edges. */
private val HANDLE_SIZE = 44.dp

/**
 * The resize frame around [rect] (workspace pixels) with a dot on each side and corner.
 * A handle moves its edges by the finger's travel, kept within the grid and [limits].
 */
@Composable
private fun ResizeFrame(
    rect: Rect,
    limits: SpanLimits,
    cellWpx: Float,
    cellHpx: Float,
    gridW: Float,
    gridH: Float,
    onStart: () -> Unit,
    onResize: (Rect) -> Unit,
    onEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val frameW = with(density) { rect.width.toDp() }
    val frameH = with(density) { rect.height.toDp() }
    // On a small widget the handles shrink so corners and sides do not overlap.
    val handleW = minOf(HANDLE_SIZE, frameW / 3)
    val handleH = minOf(HANDLE_SIZE, frameH / 3)
    val current by rememberUpdatedState(rect)
    val onStartNow by rememberUpdatedState(onStart)
    val onResizeNow by rememberUpdatedState(onResize)
    val onEndNow by rememberUpdatedState(onEnd)
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(frameW, frameH),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(2.dp)
                .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(18.dp)),
        )
        for (hx in -1..1) for (hy in -1..1) {
            if (hx == 0 && hy == 0) continue
            if (hx != 0 && !limits.horizontal) continue
            if (hy != 0 && !limits.vertical) continue
            Box(
                Modifier
                    .align(BiasAlignment(hx.toFloat(), hy.toFloat()))
                    .size(handleW, handleH)
                    // The system back gesture does not take touches that start on a handle.
                    .systemGestureExclusion()
                    .pointerInput(hx, hy, limits, cellWpx, cellHpx, gridW, gridH) {
                        var start = Rect.Zero
                        var travel = Offset.Zero
                        detectDragGestures(
                            onDragStart = {
                                onStartNow()
                                start = current
                                travel = Offset.Zero
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                travel += amount
                                var (l, t, r, b) = start
                                // A widget already outside its declared limits keeps its size as the bound.
                                val minW = minOf(limits.minX * cellWpx, start.width)
                                val maxW = maxOf(limits.maxX * cellWpx, start.width)
                                val minH = minOf(limits.minY * cellHpx, start.height)
                                val maxH = maxOf(limits.maxY * cellHpx, start.height)
                                if (hx < 0) l = (l + travel.x).coerceIn(maxOf(0f, r - maxW), r - minW)
                                if (hx > 0) r = (r + travel.x).coerceIn(l + minW, minOf(gridW, l + maxW))
                                if (hy < 0) t = (t + travel.y).coerceIn(maxOf(0f, b - maxH), b - minH)
                                if (hy > 0) b = (b + travel.y).coerceIn(t + minH, minOf(gridH, t + maxH))
                                onResizeNow(Rect(l, t, r, b))
                            },
                            onDragEnd = { onEndNow() },
                            onDragCancel = { onEndNow() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .background(Color.White, CircleShape)
                        .border(1.5.dp, Color.Black.copy(alpha = 0.3f), CircleShape),
                )
            }
        }
    }
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
