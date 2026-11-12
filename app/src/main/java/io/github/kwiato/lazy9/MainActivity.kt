package io.github.kwiato.lazy9

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import io.github.kwiato.lazy9.data.AppEntry
import io.github.kwiato.lazy9.ui.LauncherScreen
import io.github.kwiato.lazy9.ui.LauncherTheme

/** What the UI may ask the system for. Implemented by [MainActivity]. */
interface LauncherHost {
    /** False when the accessibility service is off (the UI then explains how to enable it). */
    fun lockScreen(): Boolean
    fun expandNotifications()
    fun appInfo(app: AppEntry)
    fun uninstall(app: AppEntry)
    fun pickWallpaper()
    fun openHomeSettings()
    fun openAccessibilitySettings()
    fun isDefaultLauncher(): Boolean
    fun addWidget(info: AppWidgetProviderInfo, page: Int)
    fun deleteWidget(widgetId: Int)
}

/** Owns the AppWidgetHost and one host view per placed widget. */
class WidgetController(private val activity: Activity) {
    val manager: AppWidgetManager = AppWidgetManager.getInstance(activity)
    val host = AppWidgetHost(activity.applicationContext, HOST_ID)
    private val views = HashMap<Int, AppWidgetHostView>()

    fun view(widgetId: Int): AppWidgetHostView? {
        views[widgetId]?.let { return it }
        val info = manager.getAppWidgetInfo(widgetId) ?: return null
        return host.createView(activity, widgetId, info).also { views[widgetId] = it }
    }

    fun delete(widgetId: Int) {
        views.remove(widgetId)
        host.deleteAppWidgetId(widgetId)
    }

    private companion object {
        const val HOST_ID = 0x7439
    }
}

/** A HOME press. [onHome] = the launcher was already in front when it arrived. */
data class HomeEvent(val count: Int, val onHome: Boolean)

class MainActivity : ComponentActivity(), LauncherHost {
    private val vm: LauncherViewModel by viewModels()
    private lateinit var widgets: WidgetController

    private var homeEvent by mutableStateOf(HomeEvent(0, false))

    private class PendingWidget(val id: Int, val info: AppWidgetProviderInfo, val page: Int)
    private var pendingWidget: PendingWidget? = null
    private var widgetAdded by mutableStateOf(-1) // page to reveal after a widget lands

    private val bindWidget =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pending = pendingWidget ?: return@registerForActivityResult
            if (result.resultCode == RESULT_OK) configureOrFinish(pending) else cancelPending()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        widgets = WidgetController(this)

        setContent {
            LauncherTheme {
                LauncherScreen(
                    vm = vm,
                    widgets = widgets,
                    host = this,
                    homeEvent = homeEvent,
                    revealPage = widgetAdded,
                    onRevealed = { widgetAdded = -1 },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            widgets.host.startListening()
        } catch (e: Exception) {
            // TransactionTooLargeException on some devices with many widgets — views still work
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            widgets.host.stopListening()
        } catch (e: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            val onHome = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            homeEvent = HomeEvent(homeEvent.count + 1, onHome)
        }
    }

    // ---- LauncherHost -------------------------------------------------------

    override fun lockScreen(): Boolean = LockService.lockScreen()

    @SuppressLint("WrongConstant", "PrivateApi")
    override fun expandNotifications() {
        if (LockService.openNotifications()) return
        try {
            val service = getSystemService("statusbar") ?: return
            service.javaClass.getMethod("expandNotificationsPanel").invoke(service)
        } catch (e: Exception) {
            // no public API without the accessibility service; nothing else to try
        }
    }

    override fun appInfo(app: AppEntry) {
        try {
            vm.repo.appInfo(app)
        } catch (e: Exception) {
        }
    }

    override fun uninstall(app: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.component.packageName}"))
            .putExtra(Intent.EXTRA_USER, app.user)
        tryStart(intent)
    }

    override fun pickWallpaper() {
        tryStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.home_wallpaper)))
    }

    override fun openHomeSettings() = tryStart(Intent(Settings.ACTION_HOME_SETTINGS))

    override fun openAccessibilitySettings() = tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    override fun isDefaultLauncher(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun tryStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
        }
    }

    // ---- adding a widget: allocate → bind (maybe ask) → configure (maybe) → place

    override fun addWidget(info: AppWidgetProviderInfo, page: Int) {
        val id = widgets.host.allocateAppWidgetId()
        val pending = PendingWidget(id, info, page)
        pendingWidget = pending
        val bound = try {
            widgets.manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (e: Exception) {
            false
        }
        if (bound) {
            configureOrFinish(pending)
        } else {
            val ask = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            try {
                bindWidget.launch(ask)
            } catch (e: Exception) {
                cancelPending()
            }
        }
    }

    private fun configureOrFinish(pending: PendingWidget) {
        val info = pending.info
        // WIDGET_FEATURE_CONFIGURATION_OPTIONAL (API 31) = 4: the widget works unconfigured.
        val optional = info.widgetFeatures and 4 != 0
        if (info.configure != null && !optional) {
            try {
                widgets.host.startAppWidgetConfigureActivityForResult(this, pending.id, 0, REQUEST_CONFIGURE, null)
                return
            } catch (e: Exception) {
                // fall through: place it unconfigured rather than lose it
            }
        }
        finishAdd(pending)
    }

    private fun finishAdd(pending: PendingWidget) {
        pendingWidget = null
        val (spanX, spanY) = vm.spanFor(pending.info)
        widgetAdded = vm.addWidget(pending.id, spanX, spanY, pending.page)
    }

    private fun cancelPending() {
        val pending = pendingWidget ?: return
        pendingWidget = null
        widgets.delete(pending.id)
        Toast.makeText(this, R.string.widget_failed, Toast.LENGTH_SHORT).show()
    }

    @Deprecated("AppWidgetHost only offers the legacy result path for widget configuration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return
        val pending = pendingWidget ?: return
        if (resultCode == RESULT_OK) finishAdd(pending) else cancelPending()
    }

    override fun deleteWidget(widgetId: Int) = widgets.delete(widgetId)

    private companion object {
        const val REQUEST_CONFIGURE = 4201
    }
}
