package io.github.kwiato.lazy9

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Exists only for its global actions: locking the screen (the one way to turn
 * the display off that keeps biometric unlock working) and opening the
 * notification shade. It subscribes to no events and reads no window content.
 */
class LockService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        connected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        connected = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: LockService? = null

        /** Observable from Compose. */
        var connected by mutableStateOf(false)
            private set

        fun lockScreen(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        fun openNotifications(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false
    }
}
