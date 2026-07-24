package com.simely.adskip.sync

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub 规则同步（零额外依赖，使用 HttpURLConnection）。
 * - 下载：有 Token 走 API（支持私有库），无 Token 走 raw（公开文件）。
 * - 上传：Contents API PUT，base64 编码内容，需带上已有文件的 sha 才能更新。
 */
object GitHubSync {

    fun downloadRaw(owner: String, repo: String, branch: String, path: String): String {
        val url = URL("https://raw.githubusercontent.com/$owner/$repo/$branch/${encodePath(path)}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 返回 (解码后的文件内容, sha)。404 表示文件不存在 → 返回 "" 与 null。 */
    fun downloadApi(owner: String, repo: String, branch: String, path: String, token: String): Pair<String, String?> {
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
            if (conn.responseCode == 404) return "" to null
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val raw = json.optString("content", "").replace("\n", "")
            val decoded = if (raw.isNotEmpty()) String(Base64.decode(raw, Base64.DEFAULT)) else ""
            return decoded to json.optString("sha", null)
        } finally {
            conn.disconnect()
        }
    }

    fun upload(owner: String, repo: String, branch: String, path: String, token: String, content: String, sha: String?): Boolean {
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
            return conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun encodePath(s: String): String =
        s.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
}
