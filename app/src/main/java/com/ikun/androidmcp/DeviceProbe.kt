package com.ikun.androidmcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

object DeviceProbe {
    fun sensorsRead(app: Context, args: JSONObject): JSONObject {
        val manager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val wanted = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_GYROSCOPE to "gyroscope",
            Sensor.TYPE_MAGNETIC_FIELD to "magneticField",
            Sensor.TYPE_LIGHT to "light",
            Sensor.TYPE_PROXIMITY to "proximity",
            Sensor.TYPE_PRESSURE to "pressure",
            Sensor.TYPE_RELATIVE_HUMIDITY to "humidity",
            Sensor.TYPE_AMBIENT_TEMPERATURE to "ambientTemperature"
        )
        val out = JSONObject()
        for ((type, label) in wanted) {
            val sensor = runCatching { manager.getDefaultSensor(type) }.getOrNull()
            if (sensor == null) { out.put(label, JSONObject().put("available", false)); continue }
            val row = JSONObject().put("available", true).put("name", sensor.name).put("vendor", sensor.vendor)
            var captured: FloatArray? = null
            val latch = CountDownLatch(1)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) { captured = event.values.clone(); latch.countDown() }
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }
            runCatching { manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) }
            try { latch.await(1200, TimeUnit.MILLISECONDS) } finally { runCatching { manager.unregisterListener(listener) } }
            val values = captured
            if (values != null) {
                val arr = JSONArray(); values.forEach { arr.put(it.toDouble()) }
                row.put("values", arr)
                if (label == "accelerometer" && values.size >= 3) {
                    row.put("magnitude", sqrt((values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble()))
                }
            } else {
                row.put("values", JSONObject.NULL).put("note", "采样窗口内无数据")
            }
            out.put(label, row)
        }
        return out
    }

    fun bluetoothStatus(app: Context): JSONObject {
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = runCatching { manager.adapter }.getOrNull() ?: return JSONObject().put("available", false)
        val state = runCatching { adapter.state }.getOrDefault(android.bluetooth.BluetoothAdapter.STATE_OFF)
        val out = JSONObject().put("available", true).put("state", state)
            .put("stateLabel", when (state) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> "on"
                android.bluetooth.BluetoothAdapter.STATE_OFF -> "off"
                android.bluetooth.BluetoothAdapter.STATE_TURNING_ON -> "turningOn"
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> "turningOff"
                else -> "unknown"
            })
        if (app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            out.put("name", runCatching { adapter.name }.getOrDefault(""))
            val bonded = JSONArray()
            runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet()).forEach { device ->
                bonded.put(JSONObject()
                    .put("name", runCatching { device.name }.getOrDefault(""))
                    .put("address", device.address)
                    .put("bondState", device.bondState)
                    .put("type", runCatching { device.type }.getOrDefault(0)))
            }
            out.put("bondedCount", bonded.length()).put("bondedDevices", bonded)
        }
        return out
    }

    fun wifiStatus(app: Context): JSONObject {
        val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val out = JSONObject().put("wifiEnabled", runCatching { wifi.isWifiEnabled }.getOrDefault(false))
        runCatching {
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo
            if (info != null && !info.bssid.isNullOrEmpty()) {
                out.put("ssid", info.ssid.removePrefix("\"").removeSuffix("\""))
                    .put("rssi", info.rssi)
                    .put("linkSpeedMbps", info.linkSpeed)
                    .put("frequencyMHz", info.frequency)
            }
        }
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        runCatching {
            val network = cm.activeNetwork
            val props = network?.let { cm.getLinkProperties(it) }
            if (props != null) {
                val addresses = JSONArray()
                props.linkAddresses.forEach { link -> link.address.hostAddress?.let { addresses.put(it) } }
                out.put("interface", props.interfaceName).put("addresses", addresses)
            }
        }
        return out
    }

    fun clipboardRead(app: Context): JSONObject {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!manager.hasPrimaryClip()) return JSONObject().put("available", false)
        val clip = manager.primaryClip ?: return JSONObject().put("available", false)
        val text = (0 until clip.itemCount).mapNotNull { i ->
            runCatching { clip.getItemAt(i)?.coerceToText(app)?.toString() }.getOrNull()
        }.joinToString("\n")
        return JSONObject().put("available", text.isNotBlank()).put("text", text.take(8000))
    }

    fun clipboardWrite(app: Context, args: JSONObject): JSONObject {
        val text = args.optString("text")
        if (text.isBlank()) throw IllegalArgumentException("text 必填")
        if (text.length > 60000) throw IllegalArgumentException("text 超过60000字符上限")
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("android-mcp-bridge", text))
        return JSONObject().put("written", true).put("length", text.length)
    }
}
