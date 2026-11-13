package io.github.kwiato.lazy9.data

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
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

    fun load(): List<AppEntry> {
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
                    icon = drawable.toBitmap(iconPx, iconPx).asImageBitmap(),
                    t9 = T9.encode(label),
                    isPrivate = private,
                )
            }
        }
        val collator = Collator.getInstance()
        return out.sortedWith { a, b -> collator.compare(a.label, b.label) }
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
