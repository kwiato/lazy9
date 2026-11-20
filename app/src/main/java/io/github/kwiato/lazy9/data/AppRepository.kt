package io.github.kwiato.lazy9.data

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.github.kwiato.lazy9.R
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import kotlin.math.roundToInt

/** Installed apps (all profiles) via LauncherApps: listing, launching, shortcuts. */
class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    fun serialOf(user: UserHandle): Long = userManager.getSerialNumberForUser(user)

    /**
     * @param mono draw every icon in one colour, see [monoIcon].
     * @param privateTint colour of Private Space icons in [mono] mode, see [LauncherState.privateTint].
     * @param privateMark corner mark on Private Space icons, see [LauncherState.privateMark].
     */
    fun load(
        mono: Boolean = false,
        privateTint: Long = PRIVATE_TINT_OFF,
        privateMark: String = PRIVATE_MARK_NONE,
    ): List<AppEntry> {
        val metrics = context.resources.displayMetrics
        val iconPx = (60 * metrics.density).roundToInt()
        val out = ArrayList<AppEntry>()
        for (user in userManager.userProfiles) {
            val serial = serialOf(user)
            val private = isPrivate(user)
            // A locked Private Space must not leak its apps anywhere.
            if (private && isLocked(user)) continue
            val activities = try {
                launcherApps.getActivityList(null, user)
            } catch (e: Exception) {
                emptyList()
            }
            val palette = if (private) privatePalette(privateTint) else null
            val mark = if (private) markDrawable(privateMark) else null
            for (info in activities) {
                val cn = info.componentName
                if (cn.packageName == context.packageName) continue
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName }
                val drawable = try {
                    // Our own mark replaces the system's profile badge, so there are never two.
                    if (mark != null) info.getIcon(metrics.densityDpi) else info.getBadgedIcon(metrics.densityDpi)
                } catch (e: Exception) {
                    context.packageManager.defaultActivityIcon
                }
                var bitmap = (if (mono) monoIcon(info, iconPx, palette) else null)
                    ?: drawable.toBitmap(iconPx, iconPx)
                if (mark != null) bitmap = withMark(bitmap, mark, palette)
                out += AppEntry(
                    key = "${cn.packageName}/${cn.className}#$serial",
                    label = label,
                    component = cn,
                    user = user,
                    icon = bitmap.asImageBitmap(),
                    t9 = T9.encode(label),
                    isPrivate = private,
                )
            }
        }
        val collator = Collator.getInstance()
        return out.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    // ---- monochrome icons ----------------------------------------------------

    /**
     * A one-colour icon like the system launcher's "themed icons": the app's own
     * monochrome layer (Android 13+) tinted with the wallpaper palette. Apps that
     * ship none get their normal icon with the colour drained, so the set still
     * looks uniform. Null if the icon cannot be loaded at all.
     */
    private fun monoIcon(info: LauncherActivityInfo, px: Int, palette: Palette? = null): Bitmap? = try {
        val raw = info.getIcon(context.resources.displayMetrics.densityDpi)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val layer = if (Build.VERSION.SDK_INT >= 33 && raw is AdaptiveIconDrawable) raw.monochrome else null
        if (layer != null && raw is AdaptiveIconDrawable) {
            // The system's icon shape, defined on a 100x100 grid.
            val shape = Path(raw.iconMask).apply {
                transform(Matrix().apply { setScale(px / 100f, px / 100f) })
            }
            canvas.clipPath(shape)
            canvas.drawColor(palette?.dark ?: context.getColor(android.R.color.system_accent2_800))
            // Adaptive layers are 108dp wide with the middle 72dp visible.
            val bleed = px / 4
            layer.mutate().apply {
                setBounds(-bleed, -bleed, px + bleed, px + bleed)
                setTint(palette?.light ?: context.getColor(android.R.color.system_accent1_100))
                draw(canvas)
            }
        } else {
            val grey = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        setSaturation(0f)
                        // Grey icons of Private Space apps take on its colour too.
                        if (palette != null) {
                            val c = palette.light
                            postConcat(ColorMatrix().apply { setScale(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, 1f) })
                        }
                    },
                )
            }
            canvas.drawBitmap(raw.toBitmap(px, px), 0f, 0f, grey)
        }
        bitmap
    } catch (e: Exception) {
        null
    }

    // ---- Private Space (Android 15+) ----------------------------------------

    /** Background and artwork colours of a tinted icon. */
    private class Palette(val dark: Int, val light: Int)

    private fun privatePalette(tint: Long): Palette? = when {
        tint == PRIVATE_TINT_OFF -> null
        // The wallpaper's third accent: differs from the regular icons, still matches the theme.
        tint == PRIVATE_TINT_WALLPAPER -> if (Build.VERSION.SDK_INT >= 31) Palette(
            context.getColor(android.R.color.system_accent3_800),
            context.getColor(android.R.color.system_accent3_100),
        ) else null
        else -> {
            val c = (0xFF000000 or tint).toInt()
            Palette(ColorUtils.blendARGB(c, Color.BLACK, 0.65f), ColorUtils.blendARGB(c, Color.WHITE, 0.55f))
        }
    }

    private fun markDrawable(mark: String) = when (mark) {
        PRIVATE_MARK_LOCK -> R.drawable.ic_private_lock
        PRIVATE_MARK_SHIELD -> R.drawable.ic_private_shield
        else -> null
    }?.let { ContextCompat.getDrawable(context, it) }

    /** Draws [mark] in a small disc over the bottom-right corner of [icon]. */
    private fun withMark(icon: Bitmap, mark: Drawable, palette: Palette?): Bitmap {
        val out = if (icon.isMutable) icon else icon.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val px = out.width.toFloat()
        val r = px * 0.2f
        val cx = px - r
        val cy = px - r
        val disc = palette?.light ?: Color.WHITE
        val glyph = palette?.dark ?: Color.rgb(0x10, 0x13, 0x19)
        // A dark ring separates the disc from the icon underneath.
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glyph })
        canvas.drawCircle(cx, cy, r * 0.84f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = disc })
        val g = (r * 0.55f).roundToInt()
        mark.mutate().apply {
            setBounds(cx.roundToInt() - g, cy.roundToInt() - g, cx.roundToInt() + g, cy.roundToInt() + g)
            setTint(glyph)
            draw(canvas)
        }
        return out
    }

    private fun isPrivate(user: UserHandle): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        return try {
            launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
        } catch (e: Exception) {
            false
        }
    }

    private fun isLocked(user: UserHandle): Boolean = try {
        userManager.isQuietModeEnabled(user)
    } catch (e: Exception) {
        true
    }

    /** Null when the device has no Private Space (or we are not the default launcher). */
    fun privateSpace(): PrivateSpace? {
        if (Build.VERSION.SDK_INT < 35) return null
        val user = userManager.userProfiles.firstOrNull { isPrivate(it) } ?: return null
        return PrivateSpace(user, isLocked(user))
    }

    /** Locking is immediate; unlocking makes the system ask for the Private Space credentials. */
    fun setPrivateSpaceLocked(space: PrivateSpace, locked: Boolean) {
        try {
            userManager.requestQuietModeEnabled(locked, space.user)
        } catch (e: Exception) {
            // only the default launcher may do this
        }
    }

    fun launch(app: AppEntry) {
        launcherApps.startMainActivity(app.component, app.user, null, null)
    }

    fun appInfo(app: AppEntry) {
        launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
    }

    /** App shortcuts — only available while we are the default launcher. */
    fun shortcuts(app: AppEntry): List<ShortcutInfo> = try {
        if (!launcherApps.hasShortcutHostPermission()) emptyList()
        else {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(app.component.packageName)
                .setActivity(app.component)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                )
            launcherApps.getShortcuts(query, app.user).orEmpty()
                .filter { it.isEnabled }
                .sortedBy { it.rank }
                .take(4)
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun startShortcut(shortcut: ShortcutInfo) {
        try {
            launcherApps.startShortcut(shortcut, null, null)
        } catch (e: Exception) {
            // the shortcut vanished between listing and tapping
        }
    }

    fun register(callback: LauncherApps.Callback) = launcherApps.registerCallback(callback)
    fun unregister(callback: LauncherApps.Callback) = launcherApps.unregisterCallback(callback)
}
