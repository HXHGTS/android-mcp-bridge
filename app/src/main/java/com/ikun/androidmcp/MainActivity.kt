package com.ikun.androidmcp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var endpointView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        maybeFirstRunPrompt()
        refresh()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            setBackgroundColor(0xFFF6F4FA.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("Android MCP Bridge · Lite", 25f, true))
        root.addView(text("简版：无 Bearer 密钥，只绑定本机 IPv4/IPv6 回环地址。", 15f, false, 0xFF49454F.toInt()))
        root.addView(spacer(14))
        status = text("权限状态读取中…", 14f, true)
        root.addView(status)
        root.addView(button("申请位置与通知显示权限") { askForRuntimePermissions() })
        root.addView(button("打开通知读取授权设置") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
        root.addView(button("打开使用情况访问设置") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) })
        root.addView(text("通知读取和使用情况访问由安卓系统单独控制；须在设置页明确授权。", 13f, false, 0xFF625B71.toInt()))

        root.addView(spacer(18))
        root.addView(text("MCP 服务", 18f, true))
        endpointView = text("服务尚未启动", 14f, false)
        root.addView(endpointView)
        root.addView(button("启动本机 MCP 服务") { startMcp() })
        root.addView(button("停止 MCP 服务") { stopService(Intent(this, McpService::class.java)); refresh() })
        root.addView(button("复制 IPv4 MCP 地址") { copyAddress("IPv4 MCP 地址", IPV4_URL) })
        root.addView(button("复制 IPv6 MCP 地址") { copyAddress("IPv6 MCP 地址", IPV6_URL) })
        root.addView(button("复制双栈地址（两行）") { copyAddress("IPv4 + IPv6 MCP 地址", "$IPV4_URL\n$IPV6_URL") })
        root.addView(text("IPv4：$IPV4_URL\nIPv6：$IPV6_URL\nRikkaHub 与本 App 必须在同一台手机上。", 13f, false, 0xFF49454F.toInt()))

        root.addView(spacer(14))
        root.addView(text("工具", 18f, true))
        root.addView(text("• device.battery：电量、充电状态、省电模式\n• device.location：系统缓存的最后位置（需定位授权；不持续跟踪）\n• device.notifications：当前活动通知（需开启通知读取）\n• device.usage：近 1–168 小时应用前台使用时长（需开启使用情况访问）", 14f, false, 0xFF49454F.toInt()))

        root.addView(spacer(14))
        root.addView(text("安全提醒：本版按你的要求移除 MCP 密钥，仅监听 127.0.0.1 和 ::1，不对局域网/互联网开放。但同一台手机上的其他 App 也可能访问本地端口；通知正文和应用使用时长可能包含隐私，请勿通过代理把此服务转发到外部网络。", 13f, false, 0xFF8B2E2E.toInt()))
        root.addView(text("服务由用户手动启动，运行时显示常驻通知；无自启动、无后台定位、无数据上传。", 13f, false, 0xFF625B71.toInt()))
    }

    private fun maybeFirstRunPrompt() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("lite_intro_shown", false)) return
        prefs.edit().putBoolean("lite_intro_shown", true).apply()
        root.post {
            AlertDialog.Builder(this)
                .setTitle("配置可选授权")
                .setMessage("通知读取与使用情况访问都由系统设置管理。授权后，设备智能体可通过本机 MCP 查看当前通知和应用使用时长。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("打开授权设置") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
                .show()
        }
    }

    private fun askForRuntimePermissions() {
        val candidates = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) candidates += Manifest.permission.POST_NOTIFICATIONS
        val missing = candidates.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) Toast.makeText(this, "当前可请求权限已授权", Toast.LENGTH_SHORT).show()
        else requestPermissions(missing.toTypedArray(), 301)
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val rows = listOf(
            "定位" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        val lines = rows.map { (label, perms) ->
            val ok = perms.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            "${if (ok) "✓" else "○"} $label"
        }.toMutableList()
        if (Build.VERSION.SDK_INT >= 33) lines += "${if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "✓" else "○"} 通知显示权限"
        lines += "${if (notificationAccessGranted()) "✓" else "○"} 通知读取"
        lines += "${if (usageAccessGranted()) "✓" else "○"} 使用情况访问"
        status.text = "授权状态\n" + lines.joinToString("\n")

        val prefs = getSharedPreferences("mcp_server", MODE_PRIVATE)
        val running = prefs.getBoolean("running", false)
        val v4 = prefs.getBoolean("ipv4_running", false)
        val v6 = prefs.getBoolean("ipv6_running", false)
        endpointView.text = if (running) "服务运行中：IPv4 ${if (v4) "✓" else "×"}；IPv6 ${if (v6) "✓" else "×"}\n$IPV4_URL\n$IPV6_URL" else "服务尚未启动"
    }

    private fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val expected = ComponentName(this, NotificationCaptureService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun usageAccessGranted(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun startMcp() {
        try {
            val i = Intent(this, McpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, "正在启动双栈本机 MCP 服务", Toast.LENGTH_SHORT).show()
            root.postDelayed({ refresh() }, 500)
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败：${e.message ?: "请检查系统限制"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyAddress(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { Toast.makeText(this, "系统未提供该设置页", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Android permission callback retained for broad API compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 301) refresh()
    }

    override fun onResume() { super.onResume(); if (::root.isInitialized) refresh() }

    private fun text(value: String, size: Float, bold: Boolean, color: Int = 0xFF1D1B20.toInt()): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }
    private fun spacer(height: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val IPV4_URL = "http://127.0.0.1:18765/mcp"
        private const val IPV6_URL = "http://[::1]:18765/mcp"
    }
}
