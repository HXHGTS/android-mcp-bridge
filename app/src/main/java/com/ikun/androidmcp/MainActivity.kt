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
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var endpointView: TextView
    private lateinit var lanSwitch: Switch
    private lateinit var shellSwitch: Switch
    private lateinit var writesSwitch: Switch
    private var changingLanSwitch = false
    private var changingShellSwitch = false
    private var changingWritesSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
        maybeFirstRunPrompt()
        refresh()
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SCREEN_CAPTURE -> requestScreenProjection()
            ACTION_CAPTURE_HINT -> root.postDelayed({ finish() }, 2500)
        }
    }

    @Suppress("DEPRECATION")
    private fun requestScreenProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
        } catch (e: Exception) {
            CaptureService.projectionDenied()
        }
    }

    @Deprecated("Deprecated in Android framework")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                CaptureService.projectionGranted(resultCode, data, applicationContext)
            } else {
                CaptureService.projectionDenied()
            }
            finish()
        }
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

        root.addView(text("Android MCP Bridge", 25f, true))
        root.addView(text("本机 MCP 服务，无令牌认证；权限由你在安卓系统中逐项确认。", 15f, false, 0xFF49454F.toInt()))
        root.addView(spacer(14))
        status = text("权限状态读取中…", 14f, true)
        root.addView(status)
        root.addView(button("申请常用运行时权限") { askForRuntimePermissions() })
        root.addView(button("申请后台定位权限") { requestBackgroundLocation() })

        root.addView(spacer(10))
        root.addView(text("特殊访问（需在系统设置中手动开启）", 17f, true))
        root.addView(button("通知读取设置") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
        root.addView(button("无障碍服务设置（屏幕操作）") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        root.addView(button("使用情况访问设置") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) })
        root.addView(button("所有文件访问设置") { openSettings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")) })
        root.addView(button("悬浮窗访问设置") { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")) })
        root.addView(button("修改系统设置权限") { openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")) })
        root.addView(button("忽略电池优化设置") { openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")) })
        root.addView(text("以上授权不会被静默开启；本版 MCP 目前只调用已实现并列出的工具。系统签名、设备所有者、root 等权限普通 App 无法取得。", 13f, false, 0xFF625B71.toInt()))

        root.addView(spacer(18))
        root.addView(text("MCP 服务", 18f, true))
        endpointView = text("服务尚未启动", 14f, false)
        root.addView(endpointView)
        root.addView(button("启动本机 MCP 服务") { startMcp() })
        root.addView(button("停止 MCP 服务") { stopService(Intent(this, McpService::class.java)); refresh() })
        root.addView(button("复制 IPv4 本机 MCP 地址") { copyAddress("IPv4 本机地址", IPV4_URL) })
        root.addView(button("复制 IPv6 本机 MCP 地址") { copyAddress("IPv6 本机地址", IPV6_URL) })
        root.addView(button("复制当前局域网 MCP 地址") { copyLanAddresses() })

        root.addView(spacer(14))
        root.addView(text("局域网访问", 18f, true))
        lanSwitch = Switch(this).apply {
            text = "允许同一局域网连接（默认关闭）"
            textSize = 15f
            isChecked = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LAN_ENABLED, false)
            setOnCheckedChangeListener { _, checked -> onLanSwitchChanged(checked) }
        }
        root.addView(lanSwitch)
        root.addView(text("关闭时仅本机 127.0.0.1 与 ::1 可连接。开启后仅绑定 Wi‑Fi 私有 IPv4 / IPv6 ULA 地址，不绑定蜂窝网接口或公网 IPv6；局域网内其他设备无需令牌即可访问 MCP 数据。开启前会再次确认，并自动关闭 Shell 工具。", 13f, false, 0xFF8B2E2E.toInt()))

        root.addView(spacer(12))
        root.addView(text("本机 Shell（默认关闭）", 18f, true))
        shellSwitch = Switch(this).apply {
            text = "允许 MCP 调用 app-UID Shell"
            textSize = 15f
            isChecked = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SHELL_ENABLED, false)
            setOnCheckedChangeListener { _, checked -> onShellSwitchChanged(checked) }
        }
        root.addView(shellSwitch)
        root.addView(text("这是 Android App 自身 UID 下的 sh，不是 ADB、Shizuku 或 root。启用后仅在 LAN 关闭时注册 system.shell；同一手机上的其他 App 仍可能访问无令牌 loopback MCP。启用前需确认。", 13f, false, 0xFF8B2E2E.toInt()))

        root.addView(spacer(12))
        root.addView(text("MCP 数据写入（默认关闭）", 18f, true))
        writesSwitch = Switch(this).apply {
            text = "允许联系人/日历等写工具"
            textSize = 15f
            isChecked = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_WRITES_ENABLED, false)
            setOnCheckedChangeListener { _, checked -> onWritesSwitchChanged(checked) }
        }
        root.addView(writesSwitch)
        root.addView(text("写工具只在 LAN 关闭且你确认启用时注册。单次写入需传 confirm=true；无令牌意味着同手机其他 App 也可能访问 loopback，请只连接可信 Agent。", 13f, false, 0xFF8B2E2E.toInt()))

        root.addView(spacer(14))
        root.addView(text("当前 MCP 工具", 18f, true))
        root.addView(text("• device.info / device.hardware：系统与硬件、传感器概况\n• device.apps.list：已安装应用清单\n• device.permissions.status：权限状态\n• device.battery：电量、充电、省电模式\n• device.location：系统缓存的最后位置\n• device.notifications / device.usage：通知和使用时长\n• system.shell：启用后限本机、app UID、有超时/输出上限\n• contacts.* / calendar.*：授权后读写，写操作需用户开启写开关并显式 confirm", 14f, false, 0xFF49454F.toInt()))
        root.addView(text("没有发短信、拨号、删文件等写操作工具。通知、位置、使用情况都可能包含敏感信息。", 13f, false, 0xFF625B71.toInt()))
    }

    private fun maybeFirstRunPrompt() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("lite_intro_shown", false)) return
        prefs.edit().putBoolean("lite_intro_shown", true).apply()
        root.post {
            AlertDialog.Builder(this)
                .setTitle("权限与局域网配置")
                .setMessage("可以申请常用权限，并在系统设置中开启通知读取、使用情况访问等特殊访问。局域网监听默认关闭；开启后同一 Wi‑Fi 网络中的设备无需令牌即可访问已开放的 MCP 工具。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("申请常用权限") { _, _ -> askForRuntimePermissions() }
                .show()
        }
    }

    private fun runtimePermissions(): List<String> {
        val p = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.BODY_SENSORS
        )
        if (Build.VERSION.SDK_INT >= 29) p += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_CONNECT
            p += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.POST_NOTIFICATIONS
            p += Manifest.permission.NEARBY_WIFI_DEVICES
            p += Manifest.permission.READ_MEDIA_IMAGES
            p += Manifest.permission.READ_MEDIA_VIDEO
            p += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            p += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= 28) p += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 34) p += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        return p.distinct()
    }

    private fun askForRuntimePermissions() {
        val missing = runtimePermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) Toast.makeText(this, "可请求的常用运行时权限已授权", Toast.LENGTH_SHORT).show()
        else requestPermissions(missing.toTypedArray(), REQUEST_RUNTIME)
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "当前系统没有单独的后台定位权限", Toast.LENGTH_SHORT).show(); return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授权前台定位，再申请后台定位", Toast.LENGTH_LONG).show(); return
        }
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND_LOCATION)
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val permissions = runtimePermissions()
        val granted = permissions.count { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val lines = mutableListOf("运行时权限：$granted/${permissions.size} 项已授权")
        lines += "${if (notificationAccessGranted()) "✓" else "○"} 通知读取"
        lines += "${if (usageAccessGranted()) "✓" else "○"} 使用情况访问"
        lines += "${if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) "✓" else "○"} 所有文件访问"
        lines += "${if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) "✓" else "○"} 悬浮窗"
        lines += "${if (Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this)) "✓" else "○"} 修改系统设置"
        if (Build.VERSION.SDK_INT >= 29) lines += "${if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) "✓" else "○"} 后台定位"
        lines += "${if (AccessibilityBridgeService.isEnabled(this)) "✓" else "○"} 无障碍屏幕操作"
        status.text = lines.joinToString("\n")

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val running = prefs.getBoolean("running", false)
        val lanEnabled = prefs.getBoolean(KEY_LAN_ENABLED, false)
        val shellEnabled = prefs.getBoolean(KEY_SHELL_ENABLED, false)
        val writesEnabled = prefs.getBoolean(KEY_WRITES_ENABLED, false)
        val lanHosts = prefs.getStringSet(KEY_LAN_HOSTS, emptySet()).orEmpty().sorted()
        val lanUrls = lanHosts.map { hostToUrl(it) }
        endpointView.text = buildString {
            append(if (running) "服务运行中" else "服务尚未启动")
            append("\n本机 IPv4：$IPV4_URL\n本机 IPv6：$IPV6_URL")
            append("\n局域网开关：${if (lanEnabled) "开" else "关"}；本机 Shell：${if (shellEnabled && !lanEnabled) "开" else "关"}；写工具：${if (writesEnabled && !lanEnabled) "开" else "关"}")
            if (lanEnabled) append("\n局域网地址：${if (lanUrls.isEmpty()) "未发现可绑定的 Wi‑Fi 私有地址" else lanUrls.joinToString("\n")}")
            append("\n监听状态：IPv4 ${if (prefs.getBoolean("ipv4_running", false)) "✓" else "×"} / IPv6 ${if (prefs.getBoolean("ipv6_running", false)) "✓" else "×"}")
        }
        setLanSwitchChecked(lanEnabled)
        setShellSwitchChecked(shellEnabled && !lanEnabled)
        setWritesSwitchChecked(writesEnabled && !lanEnabled)
    }

    private fun onLanSwitchChanged(checked: Boolean) {
        if (changingLanSwitch) return
        if (!checked) {
            setLanEnabled(false)
            return
        }
        setLanSwitchChecked(false)
        AlertDialog.Builder(this)
            .setTitle("开启局域网 MCP？")
            .setMessage("开启后，同一 Wi‑Fi 局域网内的设备无需令牌即可调用已暴露的 MCP 工具。本机 Shell 会自动关闭。请只在可信网络启用；不会绑定蜂窝网或公网 IPv6。")
            .setNegativeButton("保持关闭", null)
            .setPositiveButton("我了解，开启") { _, _ -> setLanEnabled(true) }
            .show()
    }

    private fun setLanEnabled(enabled: Boolean) {
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LAN_ENABLED, enabled)
        if (enabled) editor.putBoolean(KEY_SHELL_ENABLED, false).putBoolean(KEY_WRITES_ENABLED, false)
        editor.apply()
        setLanSwitchChecked(enabled)
        if (enabled) { setShellSwitchChecked(false); setWritesSwitchChecked(false) }
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("running", false)) {
            runCatching { startService(Intent(this, McpService::class.java).setAction(McpService.ACTION_RECONFIGURE)) }
        }
        root.postDelayed({ refresh() }, 350)
    }

    private fun setLanSwitchChecked(value: Boolean) {
        if (!::lanSwitch.isInitialized) return
        changingLanSwitch = true
        lanSwitch.isChecked = value
        changingLanSwitch = false
    }

    private fun onShellSwitchChanged(checked: Boolean) {
        if (changingShellSwitch) return
        if (!checked) { setShellEnabled(false); return }
        setShellSwitchChecked(false)
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LAN_ENABLED, false)) {
            Toast.makeText(this, "请先关闭局域网监听；Shell 永不通过 LAN 提供", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("启用本机 Shell 工具？")
            .setMessage("命令以本 App UID 执行，不是 ADB/root；最大运行 15 秒、输出截断。MCP 无令牌，同手机其他 App 仍可能连接 loopback。只启用你信任的 Agent。")
            .setNegativeButton("取消", null)
            .setPositiveButton("我了解，启用") { _, _ -> setShellEnabled(true) }
            .show()
    }

    private fun setShellEnabled(enabled: Boolean) {
        val lan = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LAN_ENABLED, false)
        val value = enabled && !lan
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SHELL_ENABLED, value).apply()
        setShellSwitchChecked(value)
        root.postDelayed({ refresh() }, 200)
    }

    private fun setShellSwitchChecked(value: Boolean) {
        if (!::shellSwitch.isInitialized) return
        changingShellSwitch = true
        shellSwitch.isChecked = value
        changingShellSwitch = false
    }

    private fun onWritesSwitchChanged(checked: Boolean) {
        if (changingWritesSwitch) return
        if (!checked) { setWritesEnabled(false); return }
        setWritesSwitchChecked(false)
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LAN_ENABLED, false)) {
            Toast.makeText(this, "请先关闭局域网监听；LAN 模式不开放写工具", Toast.LENGTH_LONG).show(); return
        }
        AlertDialog.Builder(this)
            .setTitle("启用 MCP 写工具？")
            .setMessage("允许已授权的 MCP 工具新增/修改联系人和日历数据。删除工具还要求 confirm=true；LAN 开启时所有写工具自动关闭。")
            .setNegativeButton("取消", null)
            .setPositiveButton("我了解，启用") { _, _ -> setWritesEnabled(true) }
            .show()
    }

    private fun setWritesEnabled(enabled: Boolean) {
        val lan = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LAN_ENABLED, false)
        val value = enabled && !lan
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WRITES_ENABLED, value).apply()
        setWritesSwitchChecked(value)
        root.postDelayed({ refresh() }, 200)
    }

    private fun setWritesSwitchChecked(value: Boolean) {
        if (!::writesSwitch.isInitialized) return
        changingWritesSwitch = true
        writesSwitch.isChecked = value
        changingWritesSwitch = false
    }

    private fun startMcp() {
        try {
            val i = Intent(this, McpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, "正在启动 MCP 服务", Toast.LENGTH_SHORT).show()
            root.postDelayed({ refresh() }, 700)
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败：${e.message ?: "请检查系统限制"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyAddress(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun copyLanAddresses() {
        val hosts = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_LAN_HOSTS, emptySet()).orEmpty()
        val urls = hosts.map { hostToUrl(it) }
        if (urls.isEmpty()) {
            Toast.makeText(this, "当前没有局域网监听地址；开启开关并连接 Wi‑Fi", Toast.LENGTH_LONG).show(); return
        }
        copyAddress("局域网 MCP 地址", urls.joinToString("\n"))
    }

    private fun hostToUrl(host: String): String = if (host.contains(":")) "http://[$host]:18765/mcp" else "http://$host:18765/mcp"

    private fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val expected = ComponentName(this, NotificationCaptureService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun usageAccessGranted(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun openSettings(action: String, uri: Uri? = null) {
        runCatching {
            val intent = Intent(action)
            if (uri != null) intent.data = uri
            startActivity(intent)
        }.onFailure { Toast.makeText(this, "系统未提供该设置页", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Android permission callback retained for broad API compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RUNTIME || requestCode == REQUEST_BACKGROUND_LOCATION) refresh()
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
        private const val PREFS = "mcp_server"
        private const val KEY_LAN_ENABLED = "lan_enabled"
        private const val KEY_LAN_HOSTS = "lan_hosts"
        private const val KEY_SHELL_ENABLED = "local_shell_enabled"
        private const val KEY_WRITES_ENABLED = "data_writes_enabled"
        private const val REQUEST_RUNTIME = 301
        private const val REQUEST_BACKGROUND_LOCATION = 302
        private const val REQUEST_PROJECTION = 501
        const val ACTION_SCREEN_CAPTURE = "com.ikun.androidmcp.SCREEN_CAPTURE"
        const val ACTION_CAPTURE_HINT = "com.ikun.androidmcp.CAPTURE_HINT"
        private const val IPV4_URL = "http://127.0.0.1:18765/mcp"
        private const val IPV6_URL = "http://[::1]:18765/mcp"
    }
}
