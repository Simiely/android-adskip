package com.simely.adskip.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 关键词存储：仅管理用户关键词 + 默认关键词。
 * 从 RuleStore 拆分，单一职责。
 */
class KeywordStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context, "adskip_rules",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    val defaultKeywords: Set<String> get() = DEFAULT_KEYWORDS

    fun getAll(): Set<String> {
        val user = prefs.getStringSet(KEY_USER_KEYWORDS, emptySet()) ?: emptySet()
        return DEFAULT_KEYWORDS + user
    }

    fun getUser(): MutableSet<String> {
        return LinkedHashSet(prefs.getStringSet(KEY_USER_KEYWORDS, emptySet()) ?: emptySet())
    }

    fun add(kw: String) {
        val set = getUser().apply { add(kw.trim()) }
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, set).apply()
    }

    fun remove(kw: String) {
        val set = getUser().apply { remove(kw) }
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, set).apply()
    }

    fun saveUser(keywords: Set<String>) {
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, keywords).apply()
    }

    companion object {
        private const val KEY_USER_KEYWORDS = "user_keywords"
        private val DEFAULT_KEYWORDS = setOf("跳过", "跳过广告", "跳过视频广告", "关闭广告")
    }
}
