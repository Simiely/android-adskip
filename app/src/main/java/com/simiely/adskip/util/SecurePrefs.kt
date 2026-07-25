package com.simely.adskip.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

class SecurePrefs(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context, "adskip_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""
    fun setToken(t: String) = prefs.edit().putString(KEY_TOKEN, t.trim()).apply()

    fun isPasswordSet(): Boolean = prefs.contains(KEY_PWD_HASH)
    fun getPasswordHash(): String = prefs.getString(KEY_PWD_HASH, "") ?: ""
    fun setPasswordHash(hash: String) = prefs.edit().putString(KEY_PWD_HASH, hash).apply()

    fun getMasterEnabled(): Boolean = prefs.getBoolean(KEY_MASTER, true)
    fun setMasterEnabled(v: Boolean) = prefs.edit().putBoolean(KEY_MASTER, v).apply()

    fun getKeywordEnabled(): Boolean = prefs.getBoolean(KEY_KEYWORD, true)
    fun setKeywordEnabled(v: Boolean) = prefs.edit().putBoolean(KEY_KEYWORD, v).apply()

    fun getDisabledRule(): String = prefs.getString(KEY_DISABLED, "") ?: ""
    fun setDisabledRule(rule: String) = prefs.edit().putString(KEY_DISABLED, rule).apply()
    fun clearDisabledRule() = prefs.edit().remove(KEY_DISABLED).apply()

    fun getRepoOwner(): String = prefs.getString(KEY_OWNER, "") ?: ""
    fun setRepoOwner(v: String) = prefs.edit().putString(KEY_OWNER, v.trim()).apply()
    fun getRepoName(): String = prefs.getString(KEY_REPO, "") ?: ""
    fun setRepoName(v: String) = prefs.edit().putString(KEY_REPO, v.trim()).apply()
    fun getRepoBranch(): String = prefs.getString(KEY_BRANCH, "main") ?: "main"
    fun setRepoBranch(v: String) = prefs.edit().putString(KEY_BRANCH, v.trim().ifEmpty { "main" }).apply()
    fun getRepoPath(): String = prefs.getString(KEY_PATH, "rules.json") ?: "rules.json"
    fun setRepoPath(v: String) = prefs.edit().putString(KEY_PATH, v.trim().ifEmpty { "rules.json" }).apply()

    companion object {
        private const val KEY_TOKEN = "gh_token"
        private const val KEY_PWD_HASH = "pwd_hash"
        private const val KEY_MASTER = "master_enabled"
        private const val KEY_KEYWORD = "keyword_enabled"
        private const val KEY_DISABLED = "disabled_rule"
        private const val KEY_OWNER = "repo_owner"
        private const val KEY_REPO = "repo_name"
        private const val KEY_BRANCH = "repo_branch"
        private const val KEY_PATH = "repo_path"

        fun hash(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
