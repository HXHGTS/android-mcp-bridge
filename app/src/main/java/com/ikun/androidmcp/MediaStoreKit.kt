package com.ikun.androidmcp

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object MediaStoreKit {
    private const val MAX_INLINE_BYTES = 5L * 1024 * 1024
    private const val MAX_WRITE_BYTES = 10L * 1024 * 1024

    private fun collection(app: Context, type: String): Uri {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        return when (type) {
            "images" -> MediaStore.Images.Media.getContentUri(volume)
            "videos" -> MediaStore.Video.Media.getContentUri(volume)
            "audio" -> MediaStore.Audio.Media.getContentUri(volume)
            else -> throw IllegalArgumentException("type 必须是 images/videos/audio")
        }
    }

    private fun relativeDir(type: String): String = when (type) {
        "images" -> "${Environment.DIRECTORY_PICTURES}/AndroidMcpBridge"
        "videos" -> "${Environment.DIRECTORY_MOVIES}/AndroidMcpBridge"
        else -> "${Environment.DIRECTORY_MUSIC}/AndroidMcpBridge"
    }

    fun list(app: Context, args: JSONObject): JSONObject {
        val type = args.optString("type", "images")
        val uri = collection(app, type)
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val offset = args.optInt("offset", 0).coerceIn(0, 1_000_000)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val rows = JSONArray()
        app.contentResolver.query(uri, projection, null, null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT $limit OFFSET $offset")?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.put(JSONObject()
                    .put("id", cursor.getLong(0))
                    .put("name", cursor.getString(1) ?: "")
                    .put("sizeBytes", cursor.getLong(2))
                    .put("mime", cursor.getString(3) ?: "")
                    .put("modifiedSec", cursor.getLong(4))
                    .put("relativePath", cursor.getString(5) ?: ""))
            }
        }
        return JSONObject().put("type", type).put("count", rows.length()).put("items", rows)
    }

    fun read(app: Context, args: JSONObject): JSONObject {
        val type = args.optString("type", "images")
        val id = args.optLong("id", -1L)
        if (id <= 0) throw IllegalArgumentException("id 无效")
        val uri = ContentUris.withAppendedId(collection(app, type), id)
        app.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            val out = JSONObject().put("id", id).put("sizeBytes", bytes.size)
            if (bytes.size > MAX_INLINE_BYTES) {
                return out.put("truncated", true).put("message", "文件超过5MB不内嵌返回；可降低分辨率或用 files.read 处理小文件")
            }
            return out.put("mime", app.contentResolver.getType(uri) ?: "application/octet-stream")
                .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } ?: throw IOException("无法打开媒体文件")
    }

    fun write(app: Context, args: JSONObject): JSONObject {
        val type = args.optString("type", "images")
        val fileName = args.optString("fileName").trim()
        val base64 = args.optString("base64")
        if (fileName.isBlank() || fileName.contains("..") || fileName.contains('/')) throw IllegalArgumentException("fileName 无效")
        if (base64.isBlank()) throw IllegalArgumentException("base64 必填")
        val bytes = try { Base64.decode(base64, Base64.NO_WRAP) } catch (e: Exception) { throw IllegalArgumentException("base64 解码失败") }
        if (bytes.size > MAX_WRITE_BYTES) throw IllegalArgumentException("内容超过10MB上限")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(fileName))
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir(type))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = app.contentResolver.insert(collection(app, type), values) ?: throw IOException("媒体插入失败")
        try {
            app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("媒体写入失败")
            if (Build.VERSION.SDK_INT >= 29) {
                val update = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                app.contentResolver.update(uri, update, null, null)
            }
        } catch (e: Exception) {
            runCatching { app.contentResolver.delete(uri, null, null) }
            throw e
        }
        return JSONObject().put("written", true).put("uri", uri.toString()).put("id", ContentUris.parseId(uri))
    }

    fun delete(app: Context, args: JSONObject): JSONObject {
        val type = args.optString("type", "images")
        val id = args.optLong("id", -1L)
        if (id <= 0) throw IllegalArgumentException("id 无效")
        val uri = ContentUris.withAppendedId(collection(app, type), id)
        val deleted = try {
            app.contentResolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            throw SecurityException("系统不允许删除该媒体（可能属于其他应用，需逐文件授权）")
        }
        return JSONObject().put("deleted", deleted > 0).put("rows", deleted)
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "txt" -> "text/plain"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    @Suppress("DEPRECATION")
    private fun storageRoot(): File = Environment.getExternalStorageDirectory()

    private fun resolveSafe(path: String): File {
        val root = runCatching { storageRoot().canonicalFile }.getOrDefault(storageRoot())
        val file = if (path.isBlank()) root else File(root, path.trimStart('/'))
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        if (!canonical.absolutePath.startsWith(root.absolutePath)) throw SecurityException("只允许访问主外部存储 /storage/emulated/0")
        return canonical
    }

    fun filesList(app: Context, args: JSONObject): JSONObject {
        val dir = resolveSafe(args.optString("path", ""))
        if (!dir.exists()) throw IllegalArgumentException("路径不存在")
        if (!dir.isDirectory) throw IllegalArgumentException("路径不是目录")
        val limit = args.optInt("limit", 200).coerceIn(1, 2000)
        val rows = JSONArray()
        val names = dir.list()?.sorted() ?: emptyArray()
        for (name in names) {
            if (rows.length() >= limit) break
            val entry = File(dir, name)
            runCatching {
                rows.put(JSONObject().put("name", name).put("directory", entry.isDirectory)
                    .put("sizeBytes", if (entry.isFile) entry.length() else 0L)
                    .put("modifiedMs", entry.lastModified()))
            }
        }
        return JSONObject().put("path", dir.absolutePath).put("count", rows.length()).put("entries", rows)
    }

    fun filesRead(app: Context, args: JSONObject): JSONObject {
        val file = resolveSafe(args.optString("path"))
        if (!file.isFile) throw IllegalArgumentException("不是文件")
        val size = file.length()
        val out = JSONObject().put("path", file.absolutePath).put("sizeBytes", size)
        if (size > MAX_INLINE_BYTES) return out.put("truncated", true).put("message", "超过5MB不内嵌返回")
        val bytes = file.readBytes()
        return out.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    fun filesWrite(app: Context, args: JSONObject): JSONObject {
        val path = args.optString("path")
        val base64 = args.optString("base64")
        if (path.isBlank() || base64.isBlank()) throw IllegalArgumentException("path 和 base64 必填")
        val file = resolveSafe(path)
        val parent = file.parentFile ?: storageRoot()
        if (!parent.exists()) parent.mkdirs()
        if (!parent.canWrite()) throw IOException("目标目录不可写")
        val bytes = try { Base64.decode(base64, Base64.NO_WRAP) } catch (e: Exception) { throw IllegalArgumentException("base64 解码失败") }
        if (bytes.size > MAX_WRITE_BYTES) throw IllegalArgumentException("内容超过10MB上限")
        FileOutputStream(file, false).use { it.write(bytes) }
        return JSONObject().put("written", true).put("path", file.absolutePath).put("sizeBytes", bytes.size)
    }

    fun filesDelete(app: Context, args: JSONObject): JSONObject {
        val file = resolveSafe(args.optString("path"))
        if (!file.exists()) throw IllegalArgumentException("路径不存在")
        if (file.isDirectory && (file.list()?.size ?: 0) > 0 && !args.optBoolean("recursive", false)) {
            throw IllegalArgumentException("目录非空；需要 recursive=true 才递归删除")
        }
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        return JSONObject().put("deleted", ok).put("path", file.absolutePath)
    }
}
