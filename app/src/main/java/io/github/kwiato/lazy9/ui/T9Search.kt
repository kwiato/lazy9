package io.github.kwiato.lazy9.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kwiato.lazy9.LauncherHost
import io.github.kwiato.lazy9.LauncherViewModel
import io.github.kwiato.lazy9.R
import io.github.kwiato.lazy9.data.ContactAction
import io.github.kwiato.lazy9.data.ContactEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.kwiato.lazy9.data.Hit

private const val MAX_RESULTS = 3

private val KEYS = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
)

/** The T9 search card: results on top, the keypad under the thumb. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun T9Search(
    vm: LauncherViewModel,
    /** Centre of the dock's T9 button in root coordinates — where the card grows from. */
    origin: Offset,
    /** 0..1: how far the circular reveal has spread. Read while drawing only. */
    reveal: () -> Float,
    host: LauncherHost,
    onClose: () -> Unit,
) {
    var cardPos by remember { mutableStateOf(Offset.Zero) }

    Box(Modifier.fillMaxSize()) {
        // Tapping the wallpaper above the panel dismisses it.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        T9Panel(
            vm,
            host,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 28.dp, end = 28.dp, bottom = 16.dp)
                .widthIn(max = 340.dp * vm.state.cardWidth)
                .fillMaxWidth()
                .onGloballyPositioned { cardPos = it.positionInRoot() }
                // The card spreads out of the T9 button like a ripple.
                .drawWithContent {
                    val progress = reveal()
                    if (progress >= 1f) {
                        drawContent()
                    } else {
                        val c = if (origin.isSpecified) origin - cardPos else Offset(size.width / 2, size.height)
                        val reach = maxOf(
                            c.getDistance(),
                            (c - Offset(size.width, 0f)).getDistance(),
                            (c - Offset(0f, size.height)).getDistance(),
                            (c - Offset(size.width, size.height)).getDistance(),
                        )
                        val circle = Path().apply { addOval(Rect(c, reach * progress)) }
                        clipPath(circle) { this@drawWithContent.drawContent() }
                    }
                },
            onLaunched = onClose,
        )
    }
}

/**
 * Results on top, the keypad under the thumb. Used both inside the pop-up
 * card and, with "persistent search" on, fixed on the home screen.
 *
 * @param resetKey the typed query is dropped whenever this changes.
 */
@Composable
fun T9Panel(
    vm: LauncherViewModel,
    host: LauncherHost,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    onLaunched: () -> Unit = {},
) {
    var query by remember(resetKey) { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val matches = remember(query, vm.apps, vm.state.usage, vm.contacts) { vm.searchAll(query) }
    val shown = matches.take(MAX_RESULTS)

    if (vm.state.autoLaunch) {
        LaunchedEffect(matches) {
            // Only ever an app: a phone call must not start by itself.
            val only = matches.singleOrNull() as? Hit.App
            if (query.length >= 2 && only != null) {
                vm.launch(only.app)
                onLaunched()
            }
        }
    }

    // The card's colour and opacity are the user's choice; the keys stay
    // readable by switching to dark ink on a light, mostly opaque card.
    val card = Color(0xFF000000 or vm.state.cardColor).copy(alpha = vm.state.cardAlpha)
    val ink = if (vm.state.cardAlpha > 0.5f && card.luminance() > 0.5f) Color(0xFF1F2430) else Color.White

    CompositionLocalProvider(LocalContentColor provides ink) {
    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(card)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Results: up to three icons, best match first, centred when fewer.
        // Deliberately bare: no typed digits, no labels, no hint.
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (shown.isEmpty()) {
                if (query.isNotEmpty()) {
                    Text(
                        stringResource(R.string.t9_no_results),
                        color = ink.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.Center) {
                    for (hit in shown) {
                        val cell = Modifier
                            // Shrinks on narrow panels (the persistent one).
                            .weight(1f, fill = false)
                            .width(88.dp)
                            .height(80.dp)
                            .clip(RoundedCornerShape(18.dp))
                        when (hit) {
                            is Hit.App -> AppIcon(
                                hit.app,
                                cell.clickable {
                                    vm.launch(hit.app)
                                    onLaunched()
                                },
                                showLabel = false,
                                iconSize = 56.dp,
                            )
                            is Hit.Contact -> ContactResult(hit.contact, vm, host, onLaunched, cell)
                        }
                    }
                }
            }
        }

        for (row in KEYS.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((digit, letters) in row) {
                    Key(height = vm.state.keyHeight.dp, onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // 1 carries no letters, so it clears the search instead.
                        if (digit == "1") query = "" else query += digit
                    }) {
                        val digitSp = vm.state.digitSize.sp
                        val letterSp = vm.state.letterSize.sp
                        if (digit == "1") ClearKeyLabel(digitSp, letterSp) else KeyLabel(digit, letters, digitSp, letterSp)
                    }
                }
            }
        }
    }
    }
}

/**
 * A favourite contact among the results: photo or initial, a small handset
 * badge, and the first name — unlike app icons, faces need a caption.
 * A tap calls; holding offers SMS and whatever messengers provide.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactResult(
    contact: ContactEntry,
    vm: LauncherViewModel,
    host: LauncherHost,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalContentColor.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
    ContactMenu(menu, { menu = false }, contact, vm, host, onDone)
    Column(
        Modifier
            .fillMaxSize()
            .combinedClickable(
                onClick = {
                    host.call(contact)
                    onDone()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(48.dp)) {
            if (contact.photo != null) {
                Image(
                    bitmap = contact.photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(ink.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        contact.name.take(1).uppercase(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .background(Color(0xFF2E9E5B), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = stringResource(R.string.t9_call),
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Text(
            contact.name.substringBefore(' '),
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
    }
}

/** Ways to reach a contact: call, SMS, then each messenger's own entries. */
@Composable
private fun ContactMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    contact: ContactEntry,
    vm: LauncherViewModel,
    host: LauncherHost,
    onDone: () -> Unit,
) {
    if (!expanded) return
    val actions by produceState(emptyList<ContactAction>(), contact) {
        value = withContext(Dispatchers.IO) { vm.contactRepo.actions(contact) }
    }
    val pick = { run: () -> Unit ->
        onDismiss()
        run()
        onDone()
    }
    // The menu is always on Material's own surface, whatever the card's ink is.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.t9_call)) },
                leadingIcon = { Icon(Icons.Filled.Call, null) },
                onClick = { pick { host.call(contact) } },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.t9_sms)) },
                leadingIcon = { Icon(Icons.Outlined.Sms, null) },
                onClick = { pick { host.sms(contact) } },
            )
            for (action in actions) {
                val app = remember(action.pkg, vm.apps) { vm.apps.firstOrNull { it.component.packageName == action.pkg } }
                DropdownMenuItem(
                    text = { Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        if (app != null) {
                            Image(app.icon, contentDescription = app.label, modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.AutoMirrored.Outlined.Send, null)
                        }
                    },
                    onClick = { pick { host.contactAction(action) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Key(
    height: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .height(height)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ClearKeyLabel(digitSize: TextUnit, letterSize: TextUnit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("1", fontSize = digitSize, lineHeight = digitSize * 1.1f)
        Icon(
            Icons.AutoMirrored.Outlined.Backspace,
            contentDescription = stringResource(R.string.t9_backspace),
            tint = LocalContentColor.current.copy(alpha = 0.6f),
            modifier = Modifier.size(with(LocalDensity.current) { (letterSize * 1.3f).toDp() }),
        )
    }
}

@Composable
private fun KeyLabel(digit: String, letters: String, digitSize: TextUnit, letterSize: TextUnit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(digit, fontSize = digitSize, lineHeight = digitSize * 1.1f)
        if (letters.isNotEmpty()) {
            Text(
                letters,
                color = LocalContentColor.current.copy(alpha = 0.6f),
                fontSize = letterSize,
                lineHeight = letterSize * 1.2f,
                letterSpacing = 1.5.sp,
            )
        }
    }
}
