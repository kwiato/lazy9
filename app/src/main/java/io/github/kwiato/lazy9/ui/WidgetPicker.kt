package io.github.kwiato.lazy9.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.UserManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.kwiato.lazy9.LauncherViewModel
import io.github.kwiato.lazy9.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class WidgetOption(
    val info: AppWidgetProviderInfo,
    val label: String,
    val appLabel: String,
    val preview: ImageBitmap?,
)

private sealed interface PickerRow
private class HeaderRow(val title: String) : PickerRow
private class WidgetRow(val option: WidgetOption) : PickerRow

/** Every installed widget, grouped by app. */
@Composable
fun WidgetPicker(vm: LauncherViewModel, onPick: (AppWidgetProviderInfo) -> Unit) {
    val context = LocalContext.current
    val rows by produceState<List<PickerRow>?>(null) {
        value = withContext(Dispatchers.Default) { loadRows(context) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ScrimColor)
            .pointerInput(Unit) {} // touches never fall through to the desktop underneath
            .systemBarsPadding(),
    ) {
        val list = rows
        when {
            list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            list.isEmpty() -> Text(
                stringResource(R.string.widgets_none),
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center),
            )
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                items(list) { row ->
                    when (row) {
                        is HeaderRow -> Text(
                            row.title,
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 6.dp),
                        )
                        is WidgetRow -> {
                            val option = row.option
                            val (spanX, spanY) = vm.spanFor(option.info)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(option.info) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(width = 112.dp, height = 76.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.White.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (option.preview != null) {
                                        Image(
                                            bitmap = option.preview,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(6.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(option.label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "$spanX × $spanY",
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadRows(context: Context): List<PickerRow> {
    val manager = AppWidgetManager.getInstance(context)
    val users = context.getSystemService(UserManager::class.java).userProfiles
    val pm = context.packageManager
    val dpi = context.resources.displayMetrics.densityDpi
    val maxPx = (160 * context.resources.displayMetrics.density).roundToInt()

    val options = users.flatMap { user ->
        try {
            manager.getInstalledProvidersForProfile(user)
        } catch (e: Exception) {
            emptyList()
        }
    }.map { info ->
        val label = try {
            info.loadLabel(pm)
        } catch (e: Exception) {
            null
        } ?: info.provider.className.substringAfterLast('.')
        val appLabel = try {
            pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
        } catch (e: Exception) {
            label
        }
        val drawable = try {
            info.loadPreviewImage(context, dpi) ?: info.loadIcon(context, dpi)
        } catch (e: Exception) {
            null
        }
        WidgetOption(info, label, appLabel, drawable?.toPreview(maxPx))
    }

    val out = ArrayList<PickerRow>()
    options.groupBy { it.appLabel }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .forEach { (app, widgets) ->
            out += HeaderRow(app)
            widgets.sortedBy { it.label.lowercase() }.forEach { out += WidgetRow(it) }
        }
    return out
}

/** Rasterises a preview, scaled down so its longer side is at most [maxPx]. */
private fun Drawable.toPreview(maxPx: Int): ImageBitmap? = try {
    val w = intrinsicWidth
    val h = intrinsicHeight
    if (w <= 0 || h <= 0) {
        toBitmap(maxPx / 2, maxPx / 2).asImageBitmap()
    } else {
        val scale = min(1f, maxPx / max(w, h).toFloat())
        toBitmap((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            .asImageBitmap()
    }
} catch (e: Exception) {
    null
}
