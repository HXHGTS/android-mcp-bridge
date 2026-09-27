package com.ikun.androidmcp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var keyView: TextView
    private lateinit var endpointView: TextView
    private var keyShown = false

    private val requestPermissions = registerForActivityResultCompat { refresh() }

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

        root.addView(text("Android MCP Bridge", 26f, true))
        root.addView(text("把用户明确授权的少量设备能力，以带密钥的本机 MCP 服务提供给 RikkaHub。", 15f, false, 0xFF49454F.toInt()))
        root.addView(spacer(18))
        status = text("权限状态读取中…", 15f, true)
        root.addView(status)
        root.addView(button("申请当前可请求的权限") { askForRuntimePermissions() })
        root.addView(text("授权遵循安卓系统规则：App 不能静默获得权限。只申请可由用户授予的权限；拒绝后仍可使用不依赖该权限的工具。", 13f, false, 0xFF625B71.toInt()))

        root.addView(spacer(18))
        root.addView(text("特殊访问（由系统设置管理）", 18f, true))
        root.addView(button("打开通知读取设置") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
        root.addView(button("打开使用情况访问设置") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) })
        root.addView(button("打开无障碍设置") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        root.addView(text("这些特殊访问不会由本版 MCP 工具读取或启用。无障碍、通知读取、使用情况访问和全文件访问属于高敏感能力；只有在后续增加对应工具并明确说明用途时才建议授权。", 13f, false, 0xFF625B71.toInt()))

        root.addView(spacer(18))
        root.addView(text("MCP 服务", 18f, true))
        endpointView = text("本机地址：尚未启动", 14f, false)
        root.addView(endpointView)
        root.addView(button("启动本机 MCP 服务") { startMcp() })
        root.addView(button("停止 MCP 服务") { stopService(Intent(this, McpService::class.java)); refresh() })
        root.addView(text("本版仅监听 127.0.0.1:18765，不监听局域网或公网。RikkaHub 与本 App 位于同一台手机时，可尝试配置 http://127.0.0.1:18765/mcp。", 13f, false, 0xFF625B71.toInt()))

        root.addView(spacer(14))
        root.addView(text("公网访问", 18f, true))
        root.addView(text("互联网暴露选项将在 HTTPS 中继/反向代理接入后启用。本版不把服务改绑到 0.0.0.0：仅生成密钥但通过明文 HTTP 暴露会泄露密钥和设备数据。", 13f, false, 0xFF8B2E2E.toInt()))

        root.addView(spacer(14))
        root.addView(text("访问密钥（256 位随机；仅本机 MCP 服务使用）", 16f, true))
        keyView = text("启动服务后显示", 13f, false)
        root.addView(keyView)
        root.addView(button("显示 / 复制访问密钥") { showKey() })
        root.addView(button("轮换访问密钥") { rotateKey() })

        root.addView(spacer(18))
        root.addView(text("当前 MCP 工具", 18f, true))
        root.addView(text("• device.battery：读取电量、充电和省电模式（无需敏感权限）\n• device.location：读取系统最后一次缓存位置（需要位置权限；不启动后台跟踪）", 14f, false, 0xFF49454F.toInt()))
        root.addView(spacer(14))
        root.addView(text("服务仅在用户启动时运行，显示常驻通知；无后台定位、无自启动、无数据上传。", 13f, false, 0xFF625B71.toInt()))
    }

    private fun maybeFirstRunPrompt() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("permission_intro_shown", false)) return
        prefs.edit().putBoolean("permission_intro_shown", true).apply()
        root.post {
            AlertDialog.Builder(this)
                .setTitle("配置可选设备权限")
                .setMessage("你可以查看并申请本机 MCP 工具可能使用的权限。安卓不会允许 App 静默获取权限，拒绝也不会阻止电池等非敏感工具。")
                .setNegativeButton("稍后") { _, _ -> refresh() }
                .setPositiveButton("查看并申请") { _, _ -> askForRuntimePermissions() }
                .show()
        }
    }

    private fun askForRuntimePermissions() {
        val candidates = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 31) {
            candidates += Manifest.permission.BLUETOOTH_CONNECT
            candidates += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) candidates += Manifest.permission.POST_NOTIFICATIONS
        val missing = candidates.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            Toast.makeText(this, "当前可请求权限已授权", Toast.LENGTH_SHORT).show()
            refresh()
        } else requestPermissions.launch(missing.toTypedArray())
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val rows = listOf(
            "位置（精确/大致）" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            "联系人" to listOf(Manifest.permission.READ_CONTACTS),
            "日历（读取/写入）" to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            "相机" to listOf(Manifest.permission.CAMERA),
            "麦克风" to listOf(Manifest.permission.RECORD_AUDIO)
        )
        val granted = rows.count { (_, permissions) -> permissions.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }
        val running = getSharedPreferences("mcp_server", MODE_PRIVATE).getBoolean("running", false)
        status.text = "权限概况：$granted/${rows.size} 类已至少部分授权\n" + rows.joinToString("\n") { (label, permissions) ->
            val ok = permissions.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            "${if (ok) "✓" else "○"} $label"
        }
        endpointView.text = if (running) "本机地址：http://127.0.0.1:18765/mcp\n监听范围：仅本机" else "本机地址：尚未启动"
        if (running && keyShown) keyView.text = AccessKeyStore.getOrCreate(this) else if (running) keyView.text = "服务已运行；点击下方按钮查看/复制密钥" else keyView.text = "启动服务后点下方按钮查看"
    }

    private fun startMcp() {
        val i = Intent(this, McpService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            keyShown = false
            keyView.text = "服务已运行；点击下方按钮查看/复制密钥"
            refresh()
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败：${e.message ?: "请检查系统限制"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showKey() {
        if (!getSharedPreferences("mcp_server", MODE_PRIVATE).getBoolean("running", false)) {
            Toast.makeText(this, "请先启动 MCP 服务", Toast.LENGTH_SHORT).show(); return
        }
        keyShown = true
        val key = AccessKeyStore.getOrCreate(this)
        keyView.text = key
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Android MCP access key", key))
        Toast.makeText(this, "密钥已复制；不要发给不可信对象", Toast.LENGTH_LONG).show()
    }

    private fun rotateKey() {
        AlertDialog.Builder(this).setTitle("轮换访问密钥？")
            .setMessage("旧密钥会立即失效，RikkaHub 需要更新 MCP 认证配置。")
            .setNegativeButton("取消", null)
            .setPositiveButton("轮换") { _, _ ->
                val key = AccessKeyStore.rotate(this)
                keyShown = true
                keyView.text = key
                Toast.makeText(this, "已生成新密钥", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(this, "此系统未提供该设置页", Toast.LENGTH_SHORT).show() }
    }

    private fun text(value: String, size: Float, bold: Boolean, color: Int = 0xFF1D1B20.toInt()): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }
    private fun spacer(height: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun registerForActivityResultCompat(callback: () -> Unit) = object {
        fun launch(permissions: Array<String>) {
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(permissions, 301)
        }
    }

    @Deprecated("Android permission callback retained for broad API compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 301) refresh()
    }

    override fun onResume() { super.onResume(); if (::root.isInitialized) refresh() }
}
