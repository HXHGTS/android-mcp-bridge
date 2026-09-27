package com.ikun.androidmcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AccessibilityBridgeService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: AccessibilityBridgeService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AccessibilityBridgeService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        private fun requireInstance(): AccessibilityBridgeService =
            instance ?: throw SecurityException("请在系统设置中启用 Android MCP Bridge 的无障碍服务（屏幕操作需要手动开启）")

        fun tapAt(x: Int, y: Int): JSONObject {
            if (x < 0 || y < 0) throw IllegalArgumentException("x/y 必填且为非负像素")
            val service = requireInstance()
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x + 1f, y.toFloat()) }
            return dispatch(service, path, 80)
        }

        fun swipeAt(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): JSONObject {
            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) throw IllegalArgumentException("坐标必须为非负像素")
            val service = requireInstance()
            val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            return dispatch(service, path, durationMs.coerceIn(50, 5000))
        }

        private fun dispatch(service: AccessibilityBridgeService, path: Path, durationMs: Long): JSONObject {
            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            val done = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            service.dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok.set(true); done.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
            }, null)
            done.await(5, TimeUnit.SECONDS)
            return JSONObject().put("dispatched", ok.get())
        }

        fun globalKey(action: String): JSONObject {
            val service = requireInstance()
            val mapped = when (action.lowercase()) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                else -> throw IllegalArgumentException("action 支持 back/home/recents/notifications/quick_settings")
            }
            val ok = service.performGlobalAction(mapped)
            return JSONObject().put("performed", ok).put("action", action)
        }

        fun inputText(text: String): JSONObject {
            if (text.isBlank()) throw IllegalArgumentException("text 必填")
            val service = requireInstance()
            val root = runCatching { service.rootInActiveWindow }.getOrNull()
                ?: throw IllegalStateException("没有可聚焦的窗口")
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: throw IllegalStateException("当前没有输入焦点；先点击输入框")
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            return JSONObject().put("input", ok)
        }
    }
}
