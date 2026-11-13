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
import android.graphics.drawable.AdaptiveIconDrawable
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

    /** @param mono draw every icon in one colour, see [monoIcon]. */
    fun load(mono: Boolean = false): List<AppEntry> {
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
            for (info in activities) {
                val cn = info.componentName
                if (cn.packageName == context.packageName) continue
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName }
                val drawable = try {
                    info.getBadgedIcon(metrics.densityDpi)
                } catch (e: Exception) {
                    context.packageManager.defaultActivityIcon
                }
                out += AppEntry(
                    key = "${cn.packageName}/${cn.className}#$serial",
                    label = label,
                    component = cn,
                    user = user,
                    icon = ((if (mono) monoIcon(info, iconPx) else null) ?: drawable.toBitmap(iconPx, iconPx))
                        .asImageBitmap(),
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
    private fun monoIcon(info: LauncherActivityInfo, px: Int): Bitmap? = try {
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
            canvas.drawColor(context.getColor(android.R.color.system_accent2_800))
            // Adaptive layers are 108dp wide with the middle 72dp visible.
            val bleed = px / 4
            layer.mutate().apply {
                setBounds(-bleed, -bleed, px + bleed, px + bleed)
                setTint(context.getColor(android.R.color.system_accent1_100))
                draw(canvas)
            }
        } else {
            val grey = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            canvas.drawBitmap(raw.toBitmap(px, px), 0f, 0f, grey)
        }
        bitmap
    } catch (e: Exception) {
        null
    }

    // ---- Private Space (Android 15+) ----------------------------------------

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
