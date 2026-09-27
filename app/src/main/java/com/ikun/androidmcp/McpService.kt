package com.ikun.androidmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

class McpService : Service() {
    private var server: McpHttpServer? = null
    private var accessKey: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        accessKey = AccessKeyStore.getOrCreate(this)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        try {
            server = McpHttpServer(this).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            getSharedPreferences("mcp_server", MODE_PRIVATE).edit().putBoolean("running", true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start loopback MCP listener", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        getSharedPreferences("mcp_server", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "MCP 服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "用户主动启动的本机 MCP 服务运行状态"
            })
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(this, 2, Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Android MCP Bridge 正在运行")
            .setContentText("仅监听本机 127.0.0.1；公网/局域网监听尚未启用")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    private class McpHttpServer(context: Context) : NanoHTTPD("127.0.0.1", PORT) {
        private val app = context.applicationContext

        override fun serve(session: IHTTPSession): Response {
            if (session.uri != "/mcp") return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            if (session.method != Method.POST) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "POST only")
            val auth = session.headers["authorization"].orEmpty()
            val supplied = auth.removePrefix("Bearer ").takeIf { auth.startsWith("Bearer ") }
            if (supplied == null || !constantTimeEquals(supplied, AccessKeyStore.getOrCreate(app))) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"unauthorized\"}")
            }
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val raw = files["postData"] ?: "{}"
                val request = JSONObject(raw)
                val method = request.optString("method")
                val id = request.opt("id")
                when (method) {
                    "initialize" -> jsonResponse(JSONObject()
                        .put("jsonrpc", "2.0").put("id", id)
                        .put("result", JSONObject()
                            .put("protocolVersion", "2025-03-26")
                            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                            .put("serverInfo", JSONObject().put("name", "android-mcp-bridge").put("version", "0.1.0"))))
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
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())))
            tools.put(JSONObject().put("name", "device.location")
                .put("description", "读取系统最后一次已知位置；不会在后台持续跟踪或主动启动定位。需要用户授权位置权限。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())))
            return JSONObject().put("tools", tools)
        }

        private fun callTool(params: JSONObject): JSONObject {
            val name = params.optString("name")
            val value = when (name) {
                "device.battery" -> batteryInfo()
                "device.location" -> lastKnownLocation()
                else -> return JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject()
                    .put("type", "text").put("text", "Unknown tool: $name")))
            }
            return JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value.toString())))
        }

        private fun batteryInfo(): JSONObject {
            val manager = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val powerSave = (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
            val intent = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return JSONObject().put("percent", pct).put("charging", charging).put("powerSaveMode", powerSave)
        }

        @Suppress("MissingPermission")
        private fun lastKnownLocation(): JSONObject {
            val fine = app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) throw SecurityException("Location permission has not been granted")
            val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location: Location? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .asSequence().filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (location == null) return JSONObject().put("available", false).put("message", "No cached location; no active tracking was started")
            return JSONObject().put("available", true).put("latitude", location.latitude).put("longitude", location.longitude)
                .put("accuracyMeters", location.accuracy.toDouble()).put("timestampMs", location.time)
        }

        private fun result(id: Any?, value: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", value)
        private fun jsonResponse(body: JSONObject): Response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString()).apply {
            addHeader("Mcp-Protocol-Version", "2025-03-26")
            addHeader("Cache-Control", "no-store")
        }
        private fun constantTimeEquals(a: String, b: String): Boolean {
            val aa = a.toByteArray(Charsets.UTF_8); val bb = b.toByteArray(Charsets.UTF_8)
            if (aa.size != bb.size) return false
            var diff = 0
            for (i in aa.indices) diff = diff or (aa[i].toInt() xor bb[i].toInt())
            return diff == 0
        }

        companion object { private const val PORT = 18765 }
    }

    companion object {
        const val ACTION_STOP = "com.ikun.androidmcp.STOP"
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 18765
        private const val TAG = "AndroidMcpBridge"
    }
}
