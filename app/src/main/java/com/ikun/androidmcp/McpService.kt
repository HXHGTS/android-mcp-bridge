package com.ikun.androidmcp

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore

class McpService : Service() {
    private val servers = mutableListOf<McpHttpServer>()

    override fun onCreate() {
        super.onCreate()
        removeLegacySecret()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)

        try {
            val v4 = McpHttpServer(this, "127.0.0.1")
            val v6 = McpHttpServer(this, "::1")
            v4.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            try {
                v6.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (e: Exception) {
                v4.stop()
                throw IllegalStateException("IPv6 loopback listener could not start", e)
            }
            servers += v4
            servers += v6
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("running", true).putBoolean("ipv4_running", true).putBoolean("ipv6_running", true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start dual-stack loopback MCP listeners", e)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("running", false).putBoolean("ipv4_running", false).putBoolean("ipv6_running", false).apply()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        servers.forEach { runCatching { it.stop() } }
        servers.clear()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("running", false).putBoolean("ipv4_running", false).putBoolean("ipv6_running", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            .setContentTitle("Android MCP Bridge Lite 正在运行")
            .setContentText("仅监听 127.0.0.1 与 ::1；无密钥、无公网监听")
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
                            .put("serverInfo", JSONObject().put("name", "android-mcp-bridge-lite").put("version", "0.2.0"))))
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
            return JSONObject().put("tools", tools)
        }

        private fun emptySchema() = JSONObject().put("type", "object").put("properties", JSONObject())

        private fun callTool(params: JSONObject): JSONObject {
            val name = params.optString("name")
            return try {
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val value = when (name) {
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
        private const val PREFS = "mcp_server"
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 18765
        private const val TAG = "AndroidMcpBridge"
    }
}
