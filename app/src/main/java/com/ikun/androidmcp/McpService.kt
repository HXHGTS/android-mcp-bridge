package com.ikun.androidmcp

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.CalendarContract
import android.telephony.TelephonyManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.location.Location
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.security.KeyStore
import java.io.InputStreamReader
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class McpService : Service() {
    private val servers = linkedMapOf<String, McpHttpServer>()
    private var connectivityManager: ConnectivityManager? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        removeLegacySecret()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)

        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            registerWifiNetworkCallback()
            refreshListeners()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start loopback MCP listeners", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_RECONFIGURE -> refreshListenersSafely()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wifiCallback?.let { callback -> runCatching { connectivityManager?.unregisterNetworkCallback(callback) } }
        wifiCallback = null
        servers.values.forEach { runCatching { it.stop() } }
        servers.clear()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("running", false).putBoolean("ipv4_running", false).putBoolean("ipv6_running", false)
            .putStringSet(KEY_LAN_HOSTS, emptySet()).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerWifiNetworkCallback() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshListenersSafely()
            override fun onLost(network: Network) = refreshListenersSafely()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshListenersSafely()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshListenersSafely()
        }
        cm.registerNetworkCallback(request, callback)
        wifiCallback = callback
    }

    @Synchronized
    private fun refreshListenersSafely() {
        runCatching { refreshListeners() }.onFailure { Log.e(TAG, "Listener refresh failed", it) }
    }

    @Synchronized
    private fun refreshListeners() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val desired = linkedSetOf(IPV4_LOOPBACK, IPV6_LOOPBACK)
        if (prefs.getBoolean(KEY_LAN_ENABLED, false)) desired.addAll(wifiPrivateAddresses())

        val obsolete = servers.keys.filter { it !in desired }
        obsolete.forEach { host -> servers.remove(host)?.let { runCatching { it.stop() } } }

        for (host in desired) {
            if (servers.containsKey(host)) continue
            val server = McpHttpServer(this, host)
            try {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                servers[host] = server
            } catch (e: Exception) {
                runCatching { server.stop() }
                if (host == IPV4_LOOPBACK || host == IPV6_LOOPBACK) throw e
                Log.w(TAG, "Could not bind Wi-Fi LAN address $host", e)
            }
        }

        val ipv4 = servers.containsKey(IPV4_LOOPBACK)
        val ipv6 = servers.containsKey(IPV6_LOOPBACK)
        val lanHosts = servers.keys.filter { it != IPV4_LOOPBACK && it != IPV6_LOOPBACK }.toSet()
        prefs.edit().putBoolean("running", ipv4 && ipv6)
            .putBoolean("ipv4_running", ipv4).putBoolean("ipv6_running", ipv6)
            .putStringSet(KEY_LAN_HOSTS, lanHosts).apply()
    }

    private fun wifiPrivateAddresses(): Set<String> {
        val cm = connectivityManager ?: return emptySet()
        val result = linkedSetOf<String>()
        for (network in cm.allNetworks) {
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val links = runCatching { cm.getLinkProperties(network)?.linkAddresses }.getOrNull().orEmpty()
            for (link in links) {
                val address = link.address
                val allowed = when (address) {
                    is Inet4Address -> address.isSiteLocalAddress && !address.isLoopbackAddress
                    is Inet6Address -> {
                        val bytes = address.address
                        bytes.isNotEmpty() && (bytes[0].toInt() and 0xFE) == 0xFC
                    }
                    else -> false
                }
                if (allowed) address.hostAddress?.substringBefore('%')?.let { result.add(it) }
            }
        }
        return result
    }

    private fun removeLegacySecret() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("access_key_v1").apply()
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias("android_mcp_bridge_access_key")) ks.deleteEntry("android_mcp_bridge_access_key")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MCP 服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "用户主动启动的本机 MCP 服务运行状态"
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(this, 2, Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Android MCP Bridge 正在运行")
            .setContentText("无令牌；仅本机，或用户开启后绑定 Wi-Fi 私有地址")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    private class McpHttpServer(context: Context, host: String) : NanoHTTPD(host, PORT) {
        private val app = context.applicationContext

        override fun serve(session: IHTTPSession): Response {
            if (session.uri != "/mcp") return response(Response.Status.NOT_FOUND, "Not found")
            if (session.method != Method.POST) return response(Response.Status.METHOD_NOT_ALLOWED, "POST only")
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val request = JSONObject(files["postData"] ?: "{}")
                val method = request.optString("method")
                val id = request.opt("id")
                when (method) {
                    "initialize" -> jsonResponse(JSONObject()
                        .put("jsonrpc", "2.0").put("id", id)
                        .put("result", JSONObject()
                            .put("protocolVersion", "2025-03-26")
                            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                            .put("serverInfo", JSONObject().put("name", "android-mcp-bridge").put("version", "0.4.0"))))
                    "notifications/initialized", "notifications/cancelled" -> newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "")
                    "ping" -> jsonResponse(result(id, JSONObject()))
                    "tools/list" -> jsonResponse(result(id, toolList()))
                    "tools/call" -> jsonResponse(result(id, callTool(request.optJSONObject("params") ?: JSONObject())))
                    else -> jsonResponse(JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("error", JSONObject().put("code", -32601).put("message", "Method not found")))
                }
            } catch (e: Exception) {
                jsonResponse(JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                    .put("error", JSONObject().put("code", -32603).put("message", e.message ?: "Internal error")))
            }
        }

        private fun toolList(): JSONObject {
            val tools = JSONArray()
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lanOn = prefs.getBoolean(KEY_LAN_ENABLED, false)
            tools.put(JSONObject().put("name", "device.info")
                .put("description", "读取 Android 版本、设备型号、ABI、屏幕、内存与存储等非序列号型设备信息。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "device.hardware")
                .put("description", "读取系统硬件特性清单与传感器列表。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "device.apps.list")
                .put("description", "列出本机已安装应用的包名、标签、版本与系统应用标记。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "device.permissions.status")
                .put("description", "检查本 App 已声明权限的授权状态及关键特殊访问状态。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "device.settings.brightness.get")
                .put("description", "读取屏幕亮度；系统特殊权限开启时也可调用。")
                .put("inputSchema", emptySchema()))
            if (canWriteData() && Settings.System.canWrite(app)) {
                tools.put(JSONObject().put("name", "device.settings.brightness.set")
                    .put("description", "设置亮度0–255与亮度模式（0=手动，1=自动）；需系统 WRITE_SETTINGS 授权、写工具开关和 confirm=true。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("value", JSONObject().put("type", "integer").put("description", "亮度0–255；省略则只改模式"))
                        .put("mode", JSONObject().put("type", "integer").put("description", "0=手动亮度，1=自动亮度；可选"))
                        .put("confirm", JSONObject().put("type", "boolean")))
                        .put("required", JSONArray().put("confirm"))))
            }
            tools.put(JSONObject().put("name", "device.network")
                .put("description", "读取当前网络传输类型、接口及本机地址概况。")
                .put("inputSchema", emptySchema()))
            if (hasPermission(android.Manifest.permission.READ_PHONE_STATE)) {
                tools.put(JSONObject().put("name", "device.telephony.status")
                    .put("description", "读取电话类型、SIM/网络状态和运营商名；不会读取 IMEI/设备序列号。")
                    .put("inputSchema", emptySchema()))
            }
            tools.put(JSONObject().put("name", "device.battery")
                .put("description", "读取本机电池电量、充电状态和省电模式。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "device.location")
                .put("description", "读取系统最后一次已知位置；不会启动后台定位。需要用户授权位置权限。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "device.notifications")
                .put("description", "读取当前活动通知标题与正文，可能包含敏感信息；需用户开启通知读取权限。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "device.usage")
                .put("description", "读取最近指定小时内应用前台使用时长；需用户开启使用情况访问。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("hours", JSONObject().put("type", "integer").put("description", "查询最近1到168小时，默认24")))
                    .put("required", JSONArray())))
            if (hasPermission(android.Manifest.permission.READ_CONTACTS)) {
                tools.put(JSONObject().put("name", "contacts.search")
                    .put("description", "按姓名或电话号码搜索联系人；限制返回条数。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "string"))
                        .put("limit", JSONObject().put("type", "integer")))
                        .put("required", JSONArray())))
            }
            if (hasPermission(android.Manifest.permission.READ_CALENDAR)) {
                tools.put(JSONObject().put("name", "calendar.calendars")
                    .put("description", "读取用户可访问的日历 ID 与名称。")
                    .put("inputSchema", emptySchema()))
                tools.put(JSONObject().put("name", "calendar.list")
                    .put("description", "读取指定时间范围的日历事件。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("startMs", JSONObject().put("type", "integer"))
                        .put("endMs", JSONObject().put("type", "integer"))
                        .put("limit", JSONObject().put("type", "integer"))).put("required", JSONArray())))
            }
            if (canWriteData() && hasPermission(android.Manifest.permission.WRITE_CONTACTS)) {
                tools.put(JSONObject().put("name", "contacts.create")
                    .put("description", "新增联系人；需要全局写工具开关与参数 confirm=true。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("name", JSONObject().put("type", "string"))
                        .put("phone", JSONObject().put("type", "string"))
                        .put("email", JSONObject().put("type", "string"))
                        .put("confirm", JSONObject().put("type", "boolean")))
                        .put("required", JSONArray().put("name").put("confirm"))))
                tools.put(JSONObject().put("name", "contacts.update")
                    .put("description", "按 rawContactId 更新联系人姓名/电话/邮箱；需要 confirm=true。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("rawContactId", JSONObject().put("type", "string"))
                        .put("name", JSONObject().put("type", "string"))
                        .put("phone", JSONObject().put("type", "string"))
                        .put("email", JSONObject().put("type", "string"))
                        .put("confirm", JSONObject().put("type", "boolean")))
                        .put("required", JSONArray().put("rawContactId").put("confirm"))))
                tools.put(JSONObject().put("name", "contacts.delete")
                    .put("description", "删除一个 rawContactId；需要 confirm=true。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("rawContactId", JSONObject().put("type", "string"))
                        .put("confirm", JSONObject().put("type", "boolean")))
                        .put("required", JSONArray().put("rawContactId").put("confirm"))))
            }
            if (canWriteData() && hasPermission(android.Manifest.permission.WRITE_CALENDAR)) {
                tools.put(JSONObject().put("name", "calendar.create")
                    .put("description", "新建日历事件；需要 confirm=true。时间使用 Unix 毫秒。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("calendarId", JSONObject().put("type", "integer"))
                        .put("title", JSONObject().put("type", "string"))
                        .put("startMs", JSONObject().put("type", "integer"))
                        .put("endMs", JSONObject().put("type", "integer"))
                        .put("description", JSONObject().put("type", "string"))
                        .put("location", JSONObject().put("type", "string"))
                        .put("confirm", JSONObject().put("type", "boolean")))
                        .put("required", JSONArray().put("calendarId").put("title").put("startMs").put("endMs").put("confirm"))))
                tools.put(JSONObject().put("name", "calendar.update")
                    .put("description", "更新日历事件字段；需要 confirm=true。时间使用 Unix 毫秒。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("eventId", JSONObject().put("type", "integer"))
                        .put("title", JSONObject().put("type", "string"))
                        .put("startMs", JSONObject().put("type", "integer"))
                        .put("endMs", JSONObject().put("type", "integer"))
                        .put("description", JSONObject().put("type", "string"))
                        .put("location", JSONObject().put("type", "string"))
                        .put("confirm", JSONObject().put("type", "boolean")))
                        .put("required", JSONArray().put("eventId").put("confirm"))))
                tools.put(JSONObject().put("name", "calendar.delete")
                    .put("description", "删除日历事件；需要 confirm=true。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("eventId", JSONObject().put("type", "integer"))
                        .put("confirm", JSONObject().put("type", "boolean")))
                        .put("required", JSONArray().put("eventId").put("confirm"))))
            }
            if (!lanOn) {
                tools.put(JSONObject().put("name", "screen.status")
                    .put("description", "读取无障碍、录屏、LAN、Shell、写工具开关状态。")
                    .put("inputSchema", emptySchema()))
                if (AccessibilityBridgeService.isEnabled(app)) {
                    tools.put(JSONObject().put("name", "screen.tap")
                        .put("description", "在屏幕坐标点击；需用户启用无障碍服务。")
                        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                            .put("x", JSONObject().put("type", "integer")).put("y", JSONObject().put("type", "integer")))
                            .put("required", JSONArray().put("x").put("y"))))
                    tools.put(JSONObject().put("name", "screen.swipe")
                        .put("description", "从(x1,y1)滑动到(x2,y2)；需无障碍服务。")
                        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                            .put("x1", JSONObject().put("type", "integer")).put("y1", JSONObject().put("type", "integer"))
                            .put("x2", JSONObject().put("type", "integer")).put("y2", JSONObject().put("type", "integer"))
                            .put("durationMs", JSONObject().put("type", "integer")))
                            .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))))
                    tools.put(JSONObject().put("name", "screen.key")
                        .put("description", "执行系统导航：back/home/recents/notifications/quick_settings。")
                        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                            .put("action", JSONObject().put("type", "string")))
                            .put("required", JSONArray().put("action"))))
                    tools.put(JSONObject().put("name", "screen.text")
                        .put("description", "向当前输入焦点设置文本；需无障碍服务。")
                        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                            .put("text", JSONObject().put("type", "string")))
                            .put("required", JSONArray().put("text"))))
                }
                tools.put(JSONObject().put("name", "screen.screenshot")
                    .put("description", "截取当前屏幕；系统弹 MediaProjection 授权，120秒内需确认；返回 JPEG base64 并存文件。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("maxWidth", JSONObject().put("type", "integer").put("description", "输出最大宽度240–2160，默认1080")))
                        .put("required", JSONArray())))
                tools.put(JSONObject().put("name", "screen.record.start")
                    .put("description", "开始录屏（无声音）；系统弹授权确认；autoStopSeconds 后自动停止。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("autoStopSeconds", JSONObject().put("type", "integer").put("description", "5–600秒，默认60")))
                        .put("required", JSONArray())))
                tools.put(JSONObject().put("name", "screen.record.stop")
                    .put("description", "停止录屏并返回文件路径与大小。")
                    .put("inputSchema", emptySchema()))
                if (hasPermission(android.Manifest.permission.CAMERA)) {
                    tools.put(JSONObject().put("name", "camera.photo")
                        .put("description", "拍照（无预览静拍，尽力而为）；保存 DCIM/AndroidMcpBridge 并返回 base64。")
                        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                            .put("facing", JSONObject().put("type", "string").put("description", "back 或 front，默认back")))
                            .put("required", JSONArray())))
                }
                if (hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                    tools.put(JSONObject().put("name", "audio.record")
                        .put("description", "录音指定秒数（1–120），保存 m4a 并返回路径。")
                        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                            .put("seconds", JSONObject().put("type", "integer").put("description", "1–120秒，默认10")))
                            .put("required", JSONArray())))
                }
            }
            tools.put(JSONObject().put("name", "sensors.read")
                .put("description", "一次性采样加速度/陀螺仪/磁场/光/距离/气压等传感器。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "bluetooth.status")
                .put("description", "读取蓝牙状态与已配对设备。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "wifi.status")
                .put("description", "读取 Wi-Fi 连接与网络接口信息。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "clipboard.read")
                .put("description", "读取剪贴板文本（后台访问可能被系统限制）。")
                .put("inputSchema", emptySchema()))
            tools.put(JSONObject().put("name", "clipboard.write")
                .put("description", "写入剪贴板；需写工具开关与 confirm=true。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("text", JSONObject().put("type", "string")).put("confirm", JSONObject().put("type", "boolean")))
                    .put("required", JSONArray().put("text").put("confirm"))))
            tools.put(JSONObject().put("name", "media.list")
                .put("description", "列出媒体库 images/videos/audio 元数据。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("type", JSONObject().put("type", "string").put("description", "images/videos/audio"))
                    .put("limit", JSONObject().put("type", "integer")).put("offset", JSONObject().put("type", "integer")))
                    .put("required", JSONArray())))
            tools.put(JSONObject().put("name", "media.read")
                .put("description", "按 id 读取媒体文件，≤5MB 返回 base64。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("type", JSONObject().put("type", "string")).put("id", JSONObject().put("type", "integer")))
                    .put("required", JSONArray().put("type").put("id"))))
            tools.put(JSONObject().put("name", "media.write")
                .put("description", "写入媒体文件（≤10MB base64）；需写工具开关与 confirm=true。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("type", JSONObject().put("type", "string"))
                    .put("fileName", JSONObject().put("type", "string"))
                    .put("base64", JSONObject().put("type", "string"))
                    .put("confirm", JSONObject().put("type", "boolean")))
                    .put("required", JSONArray().put("type").put("fileName").put("base64").put("confirm"))))
            tools.put(JSONObject().put("name", "media.delete")
                .put("description", "按 id 删除媒体文件；需写工具开关与 confirm=true；删除他应用文件可能需系统授权。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("type", JSONObject().put("type", "string")).put("id", JSONObject().put("type", "integer"))
                    .put("confirm", JSONObject().put("type", "boolean")))
                    .put("required", JSONArray().put("type").put("id").put("confirm"))))
            tools.put(JSONObject().put("name", "files.list")
                .put("description", "列出 /storage/emulated/0 下目录内容（需所有文件访问授权）。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string").put("description", "相对主存储路径，空=根"))
                    .put("limit", JSONObject().put("type", "integer")))
                    .put("required", JSONArray())))
            tools.put(JSONObject().put("name", "files.read")
                .put("description", "读取文件，≤5MB 返回 base64；只允许主外部存储内。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("path"))))
            tools.put(JSONObject().put("name", "files.write")
                .put("description", "写文件（≤10MB base64）；需写工具开关与 confirm=true。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string")).put("base64", JSONObject().put("type", "string"))
                    .put("confirm", JSONObject().put("type", "boolean")))
                    .put("required", JSONArray().put("path").put("base64").put("confirm"))))
            tools.put(JSONObject().put("name", "files.delete")
                .put("description", "删除文件/目录；需写工具开关与 confirm=true；非空目录需 recursive=true。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string"))
                    .put("recursive", JSONObject().put("type", "boolean"))
                    .put("confirm", JSONObject().put("type", "boolean")))
                    .put("required", JSONArray().put("path").put("confirm"))))
            if (hasPermission(android.Manifest.permission.READ_SMS)) {
                tools.put(JSONObject().put("name", "sms.list")
                    .put("description", "读取短信收件箱/已发/草稿（最新在前）。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("box", JSONObject().put("type", "string")).put("query", JSONObject().put("type", "string"))
                        .put("limit", JSONObject().put("type", "integer")))
                        .put("required", JSONArray())))
            }
            if (canWriteData() && hasPermission(android.Manifest.permission.SEND_SMS)) {
                tools.put(JSONObject().put("name", "sms.send")
                    .put("description", "发送短信；需写工具开关与 confirm=true。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("to", JSONObject().put("type", "string")).put("text", JSONObject().put("type", "string"))
                        .put("confirm", JSONObject().put("type", "boolean")))
                        .put("required", JSONArray().put("to").put("text").put("confirm"))))
            }
            if (hasPermission(android.Manifest.permission.READ_CALL_LOG)) {
                tools.put(JSONObject().put("name", "calllog.list")
                    .put("description", "读取最近通话记录。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("limit", JSONObject().put("type", "integer")))
                        .put("required", JSONArray())))
            }
            tools.put(JSONObject().put("name", "phone.dial")
                .put("description", "打开系统拨号界面并预填号码；不会自动拨出。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("number", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("number"))))
            if (canWriteData() && hasPermission(android.Manifest.permission.CALL_PHONE)) {
                tools.put(JSONObject().put("name", "phone.call")
                    .put("description", "直接拨出电话；需写工具开关与 confirm=true。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("number", JSONObject().put("type", "string")).put("confirm", JSONObject().put("type", "boolean")))
                        .put("required", JSONArray().put("number").put("confirm"))))
            }
            if (canWriteData() && notificationAccessGranted()) {
                tools.put(JSONObject().put("name", "notifications.clear")
                    .put("description", "按 key 清除一条活动通知；需写工具开关与 confirm=true。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("key", JSONObject().put("type", "string")).put("confirm", JSONObject().put("type", "boolean")))
                        .put("required", JSONArray().put("key").put("confirm"))))
            }
            if (prefs.getBoolean(KEY_SHELL_ENABLED, false) && !lanOn) {
                tools.put(JSONObject().put("name", "system.shell")
                    .put("description", "执行一条 Android app-UID 下的本机 sh 命令；不是 ADB/root。仅loopback且LAN关闭时列出；命令≤4096字符、输出≤32KiB，时间不设上限（可用 timeoutMs 可选限制）。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("command", JSONObject().put("type", "string").put("description", "在本 App UID 权限范围内执行的 shell 命令，最多4096字符"))
                        .put("timeoutMs", JSONObject().put("type", "integer").put("description", "可选超时毫秒；0或省略表示不限时等待")))
                        .put("required", JSONArray().put("command"))))
            }
            return JSONObject().put("tools", tools)
        }

        private fun emptySchema() = JSONObject().put("type", "object").put("properties", JSONObject())

        private fun callTool(params: JSONObject): JSONObject {
            val name = params.optString("name")
            return try {
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val value = when (name) {
                    "device.info" -> deviceInfo()
                    "device.hardware" -> hardwareInfo()
                    "device.apps.list" -> installedApps()
                    "device.permissions.status" -> permissionStatus()
                    "device.network" -> networkInfo()
                    "device.telephony.status" -> telephonyStatus()
                    "device.settings.brightness.get" -> brightnessGet()
                    "device.settings.brightness.set" -> brightnessSet(args)
                    "contacts.search" -> contactsSearch(args)
                    "contacts.create" -> contactsCreate(args)
                    "contacts.update" -> contactsUpdate(args)
                    "contacts.delete" -> contactsDelete(args)
                    "calendar.calendars" -> calendars()
                    "calendar.list" -> calendarList(args)
                    "calendar.create" -> calendarCreate(args)
                    "calendar.update" -> calendarUpdate(args)
                    "calendar.delete" -> calendarDelete(args)
                    "system.shell" -> runLocalShell(args)
                    "sensors.read" -> DeviceProbe.sensorsRead(app, args)
                    "bluetooth.status" -> DeviceProbe.bluetoothStatus(app)
                    "wifi.status" -> DeviceProbe.wifiStatus(app)
                    "clipboard.read" -> DeviceProbe.clipboardRead(app)
                    "clipboard.write" -> { requireWriteConsent(args); DeviceProbe.clipboardWrite(app, args) }
                    "media.list" -> MediaStoreKit.list(app, args)
                    "media.read" -> MediaStoreKit.read(app, args)
                    "media.write" -> { requireWriteConsent(args); MediaStoreKit.write(app, args) }
                    "media.delete" -> { requireWriteConsent(args); MediaStoreKit.delete(app, args) }
                    "files.list" -> MediaStoreKit.filesList(app, args)
                    "files.read" -> MediaStoreKit.filesRead(app, args)
                    "files.write" -> { requireWriteConsent(args); MediaStoreKit.filesWrite(app, args) }
                    "files.delete" -> { requireWriteConsent(args); MediaStoreKit.filesDelete(app, args) }
                    "sms.list" -> CommOps.smsList(app, args)
                    "sms.send" -> { requireWriteConsent(args); CommOps.smsSend(app, args) }
                    "calllog.list" -> CommOps.callLogList(app, args)
                    "phone.dial" -> CommOps.phoneDial(app, args)
                    "phone.call" -> { requireWriteConsent(args); CommOps.phoneCall(app, args) }
                    "notifications.clear" -> { requireWriteConsent(args); CommOps.notificationsClear(app, args) }
                    "screen.status" -> ScreenBridge.status(app)
                    "screen.tap" -> ScreenBridge.tap(app, args)
                    "screen.swipe" -> ScreenBridge.swipe(app, args)
                    "screen.key" -> ScreenBridge.key(app, args)
                    "screen.text" -> ScreenBridge.text(app, args)
                    "screen.screenshot" -> ScreenBridge.screenshot(app, args)
                    "screen.record.start" -> ScreenBridge.recordStart(app, args)
                    "screen.record.stop" -> ScreenBridge.recordStop(app)
                    "camera.photo" -> ScreenBridge.photo(app, args)
                    "audio.record" -> ScreenBridge.audioRecord(app, args)
                    "device.battery" -> batteryInfo()
                    "device.location" -> lastKnownLocation()
                    "device.notifications" -> notifications()
                    "device.usage" -> usageSummary(args.optInt("hours", 24))
                    else -> throw IllegalArgumentException("Unknown tool: $name")
                }
                toolResult(value)
            } catch (e: Exception) {
                JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject()
                    .put("type", "text").put("text", e.message ?: "Tool failed")))
            }
        }

        private fun toolResult(value: JSONObject) = JSONObject().put("content", JSONArray().put(JSONObject()
            .put("type", "text").put("text", value.toString())))

        private fun hasPermission(permission: String): Boolean = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        private fun canWriteData(): Boolean {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_WRITES_ENABLED, false) && !prefs.getBoolean(KEY_LAN_ENABLED, false)
        }

        private fun requireWriteConsent(args: JSONObject) {
            if (!canWriteData()) throw SecurityException("请在 App 中启用 MCP 写工具；LAN 开启时写工具强制关闭")
            if (!args.optBoolean("confirm", false)) throw IllegalArgumentException("写操作必须显式传入 confirm=true")
        }

        private fun networkInfo(): JSONObject {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork ?: return JSONObject().put("connected", false)
            val caps = cm.getNetworkCapabilities(network)
            val links = cm.getLinkProperties(network)
            val transports = JSONArray()
            if (caps != null) {
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) transports.put("wifi")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) transports.put("cellular")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) transports.put("ethernet")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) transports.put("vpn")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.put("bluetooth")
            }
            val addresses = JSONArray()
            links?.linkAddresses?.forEach { link -> addresses.put(link.address.hostAddress ?: "") }
            return JSONObject().put("connected", caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                .put("transports", transports).put("interface", links?.interfaceName ?: JSONObject.NULL)
                .put("addresses", addresses).put("dnsServers", JSONArray().apply { links?.dnsServers?.forEach { put(it.hostAddress ?: "") } })
        }

        @Suppress("MissingPermission")
        private fun telephonyStatus(): JSONObject {
            if (!hasPermission(android.Manifest.permission.READ_PHONE_STATE)) throw SecurityException("请先授予 READ_PHONE_STATE")
            val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val out = JSONObject().put("phoneType", tm.phoneType).put("simState", tm.simState)
                .put("networkType", tm.dataNetworkType).put("networkOperatorName", tm.networkOperatorName ?: "")
                .put("dataState", tm.dataState).put("roaming", tm.isNetworkRoaming)
            if (hasPermission(android.Manifest.permission.READ_PHONE_NUMBERS)) {
                out.put("line1Number", runCatching { tm.line1Number }.getOrNull() ?: JSONObject.NULL)
            }
            return out
        }

        private fun brightnessGet(): JSONObject {
            val resolver = app.contentResolver
            return JSONObject()
                .put("mode", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL))
                .put("value", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1))
        }

        private fun brightnessSet(args: JSONObject): JSONObject {
            if (!Settings.System.canWrite(app)) throw SecurityException("请先在系统设置中允许修改系统设置")
            requireWriteConsent(args)
            if (args.has("mode")) {
                val mode = args.optInt("mode")
                if (mode !in 0..1) throw IllegalArgumentException("mode 必须是0(手动)或1(自动)")
                Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            }
            if (args.has("value")) {
                val value = args.optInt("value")
                if (value !in 0..255) throw IllegalArgumentException("value 必须在0到255")
                Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            }
            if (!args.has("mode") && !args.has("value")) throw IllegalArgumentException("至少提供 value 或 mode")
            return brightnessGet().put("updated", true)
        }

        private fun contactsSearch(args: JSONObject): JSONObject {
            if (!hasPermission(android.Manifest.permission.READ_CONTACTS)) throw SecurityException("请授予 READ_CONTACTS")
            val query = args.optString("query").trim()
            val limit = args.optInt("limit", 30).coerceIn(1, 100)
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )
            val selection = if (query.isBlank()) null else
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val selectionArgs = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
            val rows = JSONArray()
            app.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, selection, selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC")?.use { cursor ->
                val contactCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val rawCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                var n = 0
                while (cursor.moveToNext() && n < limit) {
                    rows.put(JSONObject().put("contactId", cursor.getLong(contactCol))
                        .put("rawContactId", cursor.getLong(rawCol)).put("name", cursor.getString(nameCol) ?: "")
                        .put("phone", cursor.getString(numberCol) ?: "").put("phoneType", cursor.getInt(typeCol)))
                    n++
                }
            }
            return JSONObject().put("count", rows.length()).put("contacts", rows)
        }

        private fun contactsCreate(args: JSONObject): JSONObject {
            if (!hasPermission(android.Manifest.permission.WRITE_CONTACTS)) throw SecurityException("请授予 WRITE_CONTACTS")
            requireWriteConsent(args)
            val name = args.optString("name").trim()
            if (name.isBlank()) throw IllegalArgumentException("name 不能为空")
            val rawValues = ContentValues().apply { putNull(ContactsContract.RawContacts.ACCOUNT_NAME); putNull(ContactsContract.RawContacts.ACCOUNT_TYPE) }
            val rawUri = app.contentResolver.insert(ContactsContract.RawContacts.CONTENT_URI, rawValues)
                ?: throw IllegalStateException("创建联系人记录失败")
            val rawId = ContentUris.parseId(rawUri)
            val nameValues = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            }
            app.contentResolver.insert(ContactsContract.Data.CONTENT_URI, nameValues)
            args.optString("phone").takeIf { it.isNotBlank() }?.let { phone ->
                val values = ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                }
                app.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
            }
            args.optString("email").takeIf { it.isNotBlank() }?.let { email ->
                val values = ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                }
                app.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
            }
            return JSONObject().put("created", true).put("rawContactId", rawId)
        }

        private fun contactsUpdate(args: JSONObject): JSONObject {
            if (!hasPermission(android.Manifest.permission.WRITE_CONTACTS)) throw SecurityException("请授予 WRITE_CONTACTS")
            requireWriteConsent(args)
            val rawId = args.optLong("rawContactId", -1L)
            if (rawId <= 0L) throw IllegalArgumentException("rawContactId 无效")
            var changes = 0
            if (args.has("name")) changes += upsertContactData(rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, args.optString("name"))
            if (args.has("phone")) changes += upsertContactData(rawId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.NUMBER, args.optString("phone"))
            if (args.has("email")) changes += upsertContactData(rawId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.ADDRESS, args.optString("email"))
            if (changes == 0) throw IllegalArgumentException("至少提供 name、phone 或 email")
            return JSONObject().put("updated", true).put("rawContactId", rawId).put("fieldsChanged", changes)
        }

        private fun upsertContactData(rawId: Long, mime: String, column: String, value: String): Int {
            val resolver = app.contentResolver
            val projection = arrayOf(ContactsContract.Data._ID)
            val where = "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?"
            val args = arrayOf(rawId.toString(), mime)
            val rowId = resolver.query(ContactsContract.Data.CONTENT_URI, projection, where, args, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
            if (rowId != null) {
                val values = ContentValues().apply { put(column, value) }
                return resolver.update(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, rowId), values, null, null)
            }
            val values = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, mime)
                put(column, value)
                if (mime == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                if (mime == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
            }
            return if (resolver.insert(ContactsContract.Data.CONTENT_URI, values) != null) 1 else 0
        }

        private fun contactsDelete(args: JSONObject): JSONObject {
            if (!hasPermission(android.Manifest.permission.WRITE_CONTACTS)) throw SecurityException("请授予 WRITE_CONTACTS")
            requireWriteConsent(args)
            val rawId = args.optLong("rawContactId", -1L)
            if (rawId <= 0L) throw IllegalArgumentException("rawContactId 无效")
            val deleted = app.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID}=?", arrayOf(rawId.toString()))
            return JSONObject().put("deleted", deleted > 0).put("rows", deleted).put("rawContactId", rawId)
        }

        private fun calendars(): JSONObject {
            if (!hasPermission(android.Manifest.permission.READ_CALENDAR)) throw SecurityException("请授予 READ_CALENDAR")
            val rows = JSONArray()
            val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
                while (c.moveToNext() && rows.length() < 100) {
                    rows.put(JSONObject().put("calendarId", c.getLong(0)).put("name", c.getString(1) ?: "")
                        .put("account", c.getString(2) ?: "").put("accessLevel", c.getInt(3)))
                }
            }
            return JSONObject().put("count", rows.length()).put("calendars", rows)
        }

        private fun calendarList(args: JSONObject): JSONObject {
            if (!hasPermission(android.Manifest.permission.READ_CALENDAR)) throw SecurityException("请授予 READ_CALENDAR")
            val now = System.currentTimeMillis()
            val start = args.optLong("startMs", now - 7L * 86400000L)
            val end = args.optLong("endMs", now + 30L * 86400000L)
            if (end <= start) throw IllegalArgumentException("endMs 必须大于 startMs")
            val limit = args.optInt("limit", 100).coerceIn(1, 500)
            val rows = JSONArray()
            val projection = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION)
            app.contentResolver.query(CalendarContract.Events.CONTENT_URI, projection,
                "${CalendarContract.Events.DTSTART}>=? AND ${CalendarContract.Events.DTSTART}<=?",
                arrayOf(start.toString(), end.toString()), "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
                while (c.moveToNext() && rows.length() < limit) {
                    rows.put(JSONObject().put("eventId", c.getLong(0)).put("calendarId", c.getLong(1))
                        .put("title", c.getString(2) ?: "").put("startMs", c.getLong(3)).put("endMs", c.getLong(4))
                        .put("location", c.getString(5) ?: "").put("description", c.getString(6) ?: ""))
                }
            }
            return JSONObject().put("count", rows.length()).put("events", rows).put("startMs", start).put("endMs", end)
        }

        private fun calendarCreate(args: JSONObject): JSONObject {
            if (!hasPermission(android.Manifest.permission.WRITE_CALENDAR)) throw SecurityException("请授予 WRITE_CALENDAR")
            requireWriteConsent(args)
            val calendarId = args.optLong("calendarId", -1L)
            val title = args.optString("title").trim()
            val start = args.optLong("startMs", -1L)
            val end = args.optLong("endMs", -1L)
            if (calendarId <= 0L || title.isBlank() || start < 0L || end <= start) throw IllegalArgumentException("calendarId/title/startMs/endMs 参数无效")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId); put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (args.has("description")) put(CalendarContract.Events.DESCRIPTION, args.optString("description"))
                if (args.has("location")) put(CalendarContract.Events.EVENT_LOCATION, args.optString("location"))
            }
            val uri = app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: throw IllegalStateException("新建日历事件失败")
            return JSONObject().put("created", true).put("eventId", ContentUris.parseId(uri)).put("calendarId", calendarId)
        }

        private fun calendarUpdate(args: JSONObject): JSONObject {
            if (!hasPermission(android.Manifest.permission.WRITE_CALENDAR)) throw SecurityException("请授予 WRITE_CALENDAR")
            requireWriteConsent(args)
            val eventId = args.optLong("eventId", -1L)
            if (eventId <= 0L) throw IllegalArgumentException("eventId 无效")
            val values = ContentValues()
            if (args.has("title")) values.put(CalendarContract.Events.TITLE, args.optString("title"))
            if (args.has("startMs")) values.put(CalendarContract.Events.DTSTART, args.optLong("startMs"))
            if (args.has("endMs")) values.put(CalendarContract.Events.DTEND, args.optLong("endMs"))
            if (args.has("description")) values.put(CalendarContract.Events.DESCRIPTION, args.optString("description"))
            if (args.has("location")) values.put(CalendarContract.Events.EVENT_LOCATION, args.optString("location"))
            if (values.size() == 0) throw IllegalArgumentException("未提供可更新字段")
            val updated = app.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), values, null, null)
            return JSONObject().put("updated", updated > 0).put("rows", updated).put("eventId", eventId)
        }

        private fun calendarDelete(args: JSONObject): JSONObject {
            if (!hasPermission(android.Manifest.permission.WRITE_CALENDAR)) throw SecurityException("请授予 WRITE_CALENDAR")
            requireWriteConsent(args)
            val eventId = args.optLong("eventId", -1L)
            if (eventId <= 0L) throw IllegalArgumentException("eventId 无效")
            val deleted = app.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
            return JSONObject().put("deleted", deleted > 0).put("rows", deleted).put("eventId", eventId)
        }

        private fun deviceInfo(): JSONObject {
            val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val dm = app.resources.displayMetrics
            val abis = JSONArray().apply { Build.SUPPORTED_ABIS.forEach { put(it) } }
            val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
            return JSONObject()
                .put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND)
                .put("model", Build.MODEL).put("device", Build.DEVICE).put("product", Build.PRODUCT)
                .put("board", Build.BOARD).put("hardware", Build.HARDWARE)
                .put("buildFingerprint", Build.FINGERPRINT)
                .put("androidRelease", Build.VERSION.RELEASE).put("sdkInt", Build.VERSION.SDK_INT)
                .put("securityPatch", Build.VERSION.SECURITY_PATCH).put("supportedAbis", abis)
                .put("appVersion", version ?: "unknown")
                .put("screen", JSONObject().put("widthPx", dm.widthPixels).put("heightPx", dm.heightPixels)
                    .put("densityDpi", dm.densityDpi).put("density", dm.density.toDouble()))
                .put("memory", JSONObject().put("totalBytes", mem.totalMem).put("availableBytes", mem.availMem)
                    .put("lowMemory", mem.lowMemory))
                .put("dataStorage", JSONObject().put("totalBytes", stat.totalBytes).put("availableBytes", stat.availableBytes))
                .put("locale", java.util.Locale.getDefault().toLanguageTag()).put("timeZone", TimeZone.getDefault().id)
                .put("processUid", Process.myUid()).put("processId", Process.myPid())
        }

        private fun hardwareInfo(): JSONObject {
            val features = JSONArray()
            app.packageManager.systemAvailableFeatures.orEmpty().mapNotNull { it.name }.take(200).forEach { features.put(it) }
            val sensors = JSONArray()
            val manager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            manager.getSensorList(Sensor.TYPE_ALL).take(100).forEach { sensor ->
                sensors.put(JSONObject().put("name", sensor.name).put("vendor", sensor.vendor)
                    .put("type", sensor.type).put("version", sensor.version).put("wakeUp", sensor.isWakeUpSensor)
                    .put("maxRange", sensor.maximumRange.toDouble()).put("resolution", sensor.resolution.toDouble())
                    .put("powerMa", sensor.power.toDouble()))
            }
            return JSONObject().put("features", features).put("sensors", sensors)
                .put("sensorCount", sensors.length()).put("hardware", Build.HARDWARE)
                .put("supportedAbis", JSONArray().apply { Build.SUPPORTED_ABIS.forEach { put(it) } })
        }

        @Suppress("QueryAllPackagesPermission")
        private fun installedApps(): JSONObject {
            val rows = JSONArray()
            val apps = app.packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
                .sortedBy { it.packageName }.take(2000)
            for (info in apps) {
                val label = runCatching { app.packageManager.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                val version = runCatching { app.packageManager.getPackageInfo(info.packageName, 0).versionName }.getOrNull()
                rows.put(JSONObject().put("packageName", info.packageName).put("label", label)
                    .put("versionName", version ?: JSONObject.NULL)
                    .put("systemApp", (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0))
            }
            return JSONObject().put("count", rows.length()).put("apps", rows)
        }

        private fun permissionStatus(): JSONObject {
            val packageInfo = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            val requested = JSONArray()
            packageInfo.requestedPermissions.orEmpty().forEach { permission ->
                requested.put(JSONObject().put("permission", permission)
                    .put("granted", app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED))
            }
            val special = JSONObject()
                .put("notificationListener", notificationAccessGranted())
                .put("usageStats", usageAccessGranted())
                .put("allFiles", Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())
                .put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app))
                .put("writeSettings", Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(app))
                .put("lanEnabled", app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LAN_ENABLED, false))
                .put("localShellEnabled", app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHELL_ENABLED, false))
                .put("dataWritesEnabled", app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WRITES_ENABLED, false))
            return JSONObject().put("requested", requested).put("special", special)
        }

        private fun runLocalShell(args: JSONObject): JSONObject {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_SHELL_ENABLED, false)) throw SecurityException("请先在 App 中启用本机 Shell")
            if (prefs.getBoolean(KEY_LAN_ENABLED, false)) throw SecurityException("局域网监听启用时，本机 Shell 接口强制关闭")
            val command = args.optString("command")
            if (command.isBlank() || command.length > MAX_SHELL_COMMAND_CHARS) throw IllegalArgumentException("command 必须为1到4096字符")
            val timeoutMs = args.optInt("timeoutMs", 0)
            val output = StringBuilder()
            val truncated = AtomicBoolean(false)
            val process = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
            val reader = Thread {
                runCatching {
                    InputStreamReader(process.inputStream, Charsets.UTF_8).use { stream ->
                        val buffer = CharArray(2048)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            synchronized(output) {
                                val remaining = MAX_SHELL_OUTPUT_CHARS - output.length
                                if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
                                if (count > remaining) truncated.set(true)
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; name = "mcp-shell-output"; start() }
            val completed = if (timeoutMs > 0) {
                process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } else {
                process.waitFor(); true
            }
            if (!completed) {
                process.destroy()
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                process.waitFor()
            }
            reader.join(1000)
            val text = synchronized(output) { output.toString() }
            return JSONObject().put("uid", Process.myUid()).put("elevated", false)
                .put("exitCode", if (completed) process.exitValue() else JSONObject.NULL)
                .put("timedOut", !completed).put("truncated", truncated.get()).put("output", text)
        }

        private fun batteryInfo(): JSONObject {
            val manager = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val powerSave = (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
            val intent = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return JSONObject().put("percent", pct).put("charging", charging).put("powerSaveMode", powerSave)
        }

        @Suppress("MissingPermission")
        private fun lastKnownLocation(): JSONObject {
            val fine = app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) throw SecurityException("请先授予位置权限")
            val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location: Location? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .asSequence().filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (location == null) return JSONObject().put("available", false).put("message", "系统暂无缓存位置；没有启动主动定位")
            return JSONObject().put("available", true).put("latitude", location.latitude).put("longitude", location.longitude)
                .put("accuracyMeters", location.accuracy.toDouble()).put("timestampMs", location.time)
        }

        private fun notifications(): JSONObject {
            if (!notificationAccessGranted()) throw SecurityException("请先在系统设置中开启通知读取权限")
            if (!NotificationCaptureService.Cache.isConnected()) throw IllegalStateException("通知读取服务尚未连接，请稍候再试")
            val rows = JSONArray()
            NotificationCaptureService.Cache.snapshot().takeLast(30).forEach { rows.put(it) }
            return JSONObject().put("count", rows.length()).put("activeNotifications", rows)
        }

        private fun notificationAccessGranted(): Boolean {
            val enabled = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners").orEmpty()
            val expected = android.content.ComponentName(app, NotificationCaptureService::class.java)
            return enabled.split(':').any { android.content.ComponentName.unflattenFromString(it) == expected }
        }

        private fun usageSummary(requestedHours: Int): JSONObject {
            if (!usageAccessGranted()) throw SecurityException("请先在系统设置中开启使用情况访问")
            val hours = requestedHours.coerceIn(1, 168)
            val now = System.currentTimeMillis()
            val stats = (app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - hours * 60L * 60L * 1000L, now)
                .orEmpty()
                .filter { it.totalTimeInForeground > 0L }
                .groupBy { it.packageName }
                .map { (pkg, entries) -> pkg to entries.sumOf { it.totalTimeInForeground } }
                .sortedByDescending { it.second }
                .take(30)
            val rows = JSONArray()
            stats.forEach { (pkg, millis) ->
                val label = runCatching {
                    val info = app.packageManager.getApplicationInfo(pkg, 0)
                    app.packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(pkg)
                rows.put(JSONObject().put("packageName", pkg).put("appName", label).put("foregroundMs", millis))
            }
            return JSONObject().put("periodHours", hours).put("apps", rows)
        }

        private fun usageAccessGranted(): Boolean {
            val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName) == AppOpsManager.MODE_ALLOWED
        }

        private fun result(id: Any?, value: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", value)
        private fun jsonResponse(body: JSONObject): Response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString()).apply {
            addHeader("Mcp-Protocol-Version", "2025-03-26")
            addHeader("Cache-Control", "no-store")
        }
        private fun response(status: Response.Status, text: String) = newFixedLengthResponse(status, MIME_PLAINTEXT, text)

        companion object { private const val PORT = 18765 }
    }

    companion object {
        const val ACTION_STOP = "com.ikun.androidmcp.STOP"
        const val ACTION_RECONFIGURE = "com.ikun.androidmcp.RECONFIGURE"
        private const val PREFS = "mcp_server"
        private const val KEY_LAN_ENABLED = "lan_enabled"
        private const val KEY_LAN_HOSTS = "lan_hosts"
        private const val KEY_SHELL_ENABLED = "local_shell_enabled"
        private const val KEY_WRITES_ENABLED = "data_writes_enabled"
        private const val MAX_SHELL_COMMAND_CHARS = 4096
        private const val MAX_SHELL_OUTPUT_CHARS = 32768
        private const val IPV4_LOOPBACK = "127.0.0.1"
        private const val IPV6_LOOPBACK = "::1"
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 18765
        private const val TAG = "AndroidMcpBridge"
    }
}
