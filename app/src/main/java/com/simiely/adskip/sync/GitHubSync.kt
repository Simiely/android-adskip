package com.simely.adskip.sync

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.pow

/**
 * GitHub 规则同步（零额外依赖，使用 HttpURLConnection）。
 * - 下载：有 Token 走 API（支持私有库），无 Token 走 raw（公开文件）。
 * - 上传：Contents API PUT，base64 编码内容，需带上已有文件的 sha 才能更新。
 * - 所有网络操作自带重试（最多 3 次，指数退避），仅对 5xx / 连接超时重试。
 */
object GitHubSync {

    private const val TAG = "GitHubSync"
    private const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 1000L

    fun downloadRaw(owner: String, repo: String, branch: String, path: String): String {
        return withRetry("downloadRaw") {
            val url = URL("https://raw.githubusercontent.com/$owner/$repo/$branch/${encodePath(path)}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
            }
            try {
                if (conn.responseCode !in 200..299) throw SyncException("HTTP ${conn.responseCode}", conn.responseCode)
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 返回 (解码后的文件内容, sha)。404 表示文件不存在 → 返回 "" 与 null。 */
    fun downloadApi(owner: String, repo: String, branch: String, path: String, token: String): Pair<String, String?> {
        return withRetry("downloadApi") {
            val url = URL("https://api.github.com/repos/$owner/$repo/contents/${encodePath(path)}?ref=${encode(branch)}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AdSkip-Android")
                connectTimeout = 15000
                readTimeout = 15000
            }
            try {
                if (conn.responseCode == 404) return@withRetry "" to null
                if (conn.responseCode !in 200..299) throw SyncException("HTTP ${conn.responseCode}", conn.responseCode)
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val raw = json.optString("content", "").replace("\n", "")
                val decoded = if (raw.isNotEmpty()) String(Base64.decode(raw, Base64.DEFAULT)) else ""
                decoded to json.optString("sha", null)
            } finally {
                conn.disconnect()
            }
        }
    }

    fun upload(owner: String, repo: String, branch: String, path: String, token: String, content: String, sha: String?): Boolean {
        return withRetry("upload") {
            val url = URL("https://api.github.com/repos/$owner/$repo/contents/${encodePath(path)}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "AdSkip-Android")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }
            val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("message", "AdSkip rules sync")
                put("content", b64)
                put("branch", branch)
                if (!sha.isNullOrEmpty()) put("sha", sha)
            }.toString()
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) throw SyncException("HTTP ${conn.responseCode}", conn.responseCode)
                true
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 列出仓库目录内容，返回文件名列表。
     */
    fun listFolder(owner: String, repo: String, branch: String, path: String, token: String): List<Pair<String, String>> {
        return withRetry("listFolder") {
            val cleanPath = path.trimEnd('/')
            val url = URL("https://api.github.com/repos/$owner/$repo/contents/${encodePath(cleanPath)}?ref=${encode(branch)}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AdSkip-Android")
                connectTimeout = 15000
                readTimeout = 15000
            }
            try {
                if (conn.responseCode == 404) return@withRetry emptyList()
                if (conn.responseCode !in 200..299) throw SyncException("HTTP ${conn.responseCode}", conn.responseCode)
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
                val result = mutableListOf<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    if (item.optString("type") == "file") {
                        result.add(item.optString("name") to item.optString("download_url", ""))
                    }
                }
                result
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 验证 Token 是否有效（尝试获取用户信息） */
    fun validateToken(token: String): Boolean {
        return try {
            val url = URL("https://api.github.com/user")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AdSkip-Android")
                connectTimeout = 10000
                readTimeout = 10000
            }
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 下载原始内容（不走 API，用于公开文件），返回文件内容 */
    fun downloadRawContent(url: String): String {
        return withRetry("downloadRawContent") {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
            }
            try {
                if (conn.responseCode !in 200..299) throw SyncException("HTTP ${conn.responseCode}", conn.responseCode)
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 重试包装：仅对可重试的错误重试（5xx、连接超时等）。
     * 4xx（客户端错误如 401/403/404）不重试，直接抛出。
     */
    private inline fun <T> withRetry(tag: String, block: () -> T): T {
        var lastEx: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                return block()
            } catch (e: SyncException) {
                if (!e.isRetryable()) throw e
                lastEx = e
                Log.w(TAG, "$tag attempt ${attempt + 1} failed: ${e.message}")
            } catch (e: Exception) {
                lastEx = e
                Log.w(TAG, "$tag attempt ${attempt + 1} failed: ${e.message}")
            }
            if (attempt < MAX_RETRIES) {
                Thread.sleep(BASE_DELAY_MS * 2.0.pow(attempt).toLong())
            }
        }
        throw lastEx ?: Exception("$tag: unknown error after retries")
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun encodePath(s: String): String =
        s.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
}

/** 同步专用异常，携带 HTTP 状态码用于判断是否可重试 */
class SyncException(message: String, val statusCode: Int) : Exception(message) {
    /** 只有 5xx 服务端错误和 429 限流才重试，4xx 客户端错误不重试 */
    fun isRetryable(): Boolean = statusCode in 500..599 || statusCode == 429
}
