package com.ikun.androidmcp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject

object CommOps {
    private fun has(app: Context, permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun smsList(app: Context, args: JSONObject): JSONObject {
        if (!has(app, Manifest.permission.READ_SMS)) throw SecurityException("请先授予 READ_SMS")
        val box = when (args.optString("box", "inbox").lowercase()) { "inbox" -> "inbox"; "sent" -> "sent"; "draft" -> "draft"; else -> "inbox" }
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val query = args.optString("query").trim()
        val projection = arrayOf("_id", "address", "date", "body", "type")
        val selection = if (query.isBlank()) null else "address LIKE ? OR body LIKE ?"
        val selectionArgs = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
        val rows = JSONArray()
        app.contentResolver.query(Uri.parse("content://sms/$box"), projection, selection, selectionArgs, "date DESC LIMIT $limit")?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.put(JSONObject()
                    .put("id", cursor.getLong(0))
                    .put("address", cursor.getString(1) ?: "")
                    .put("dateMs", cursor.getLong(2))
                    .put("body", (cursor.getString(3) ?: "").take(2000))
                    .put("type", cursor.getInt(4)))
            }
        }
        return JSONObject().put("box", box).put("count", rows.length()).put("messages", rows)
    }

    fun smsSend(app: Context, args: JSONObject): JSONObject {
        if (!has(app, Manifest.permission.SEND_SMS)) throw SecurityException("请先授予 SEND_SMS")
        val to = args.optString("to").trim()
        val text = args.optString("text")
        if (to.isBlank() || text.isBlank()) throw IllegalArgumentException("to 和 text 必填")
        if (text.length > 900) throw IllegalArgumentException("单条短信内容过长")
        val manager = runCatching { @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault() }.getOrNull()
            ?: throw IllegalStateException("SmsManager 不可用")
        val parts = manager.divideMessage(text)
        if (parts.size == 1) manager.sendTextMessage(to, null, text, null, null)
        else manager.sendMultipartTextMessage(to, null, parts, null, null)
        return JSONObject().put("sent", true).put("to", to).put("parts", parts.size)
    }

    fun callLogList(app: Context, args: JSONObject): JSONObject {
        if (!has(app, Manifest.permission.READ_CALL_LOG)) throw SecurityException("请先授予 READ_CALL_LOG")
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val rows = JSONArray()
        app.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC LIMIT $limit")?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.put(JSONObject()
                    .put("number", cursor.getString(0) ?: "")
                    .put("name", cursor.getString(1) ?: "")
                    .put("type", cursor.getInt(2))
                    .put("dateMs", cursor.getLong(3))
                    .put("durationSec", cursor.getLong(4)))
            }
        }
        return JSONObject().put("count", rows.length()).put("calls", rows)
    }

    fun phoneDial(app: Context, args: JSONObject): JSONObject {
        val number = args.optString("number").trim()
        if (number.isBlank()) throw IllegalArgumentException("number 必填")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }.onFailure { throw IllegalStateException("无法打开拨号界面") }
        return JSONObject().put("dialerOpened", true).put("number", number)
    }

    fun phoneCall(app: Context, args: JSONObject): JSONObject {
        if (!has(app, Manifest.permission.CALL_PHONE)) throw SecurityException("请先授予 CALL_PHONE")
        val number = args.optString("number").trim()
        if (number.isBlank()) throw IllegalArgumentException("number 必填")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }.onFailure { throw IllegalStateException("系统拒绝直接拨号") }
        return JSONObject().put("callStarted", true).put("number", number)
    }

    fun notificationsClear(app: Context, args: JSONObject): JSONObject {
        val key = args.optString("key")
        if (key.isBlank()) throw IllegalArgumentException("key 必填（来自 device.notifications 返回的 key 字段）")
        val ok = NotificationCaptureService.Cache.requestCancel(key)
        return JSONObject().put("cleared", ok)
    }
}
