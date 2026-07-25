package com.simely.adskip.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

class SecurePrefs(context: Context) {

    private val securePrefs = EncryptedSharedPreferences.create(
        context, "adskip_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val plainPrefs = context.getSharedPreferences("adskip_ui", Context.MODE_PRIVATE)

    fun prefs(): SharedPreferences = plainPrefs

    fun getToken(): String = securePrefs.getString(KEY_TOKEN, "") ?: ""
    fun setToken(t: String) = securePrefs.edit().putString(KEY_TOKEN, t.trim()).apply()

    fun isPasswordSet(): Boolean = securePrefs.contains(KEY_PWD)
    fun getPasswordHash(): String = securePrefs.getString(KEY_PWD, "") ?: ""
    fun setPasswordHash(hash: String) = securePrefs.edit().putString(KEY_PWD, hash).apply()

    fun getMasterEnabled(): Boolean = securePrefs.getBoolean(KEY_MASTER, true)
    fun setMasterEnabled(v: Boolean) = securePrefs.edit().putBoolean(KEY_MASTER, v).apply()

    fun getKeywordEnabled(): Boolean = securePrefs.getBoolean(KEY_KEYWORD, true)
    fun setKeywordEnabled(v: Boolean) = securePrefs.edit().putBoolean(KEY_KEYWORD, v).apply()

    fun getDisabledRule(): String = securePrefs.getString(KEY_DISABLED, "") ?: ""
    fun setDisabledRule(rule: String) = securePrefs.edit().putString(KEY_DISABLED, rule).apply()
    fun clearDisabledRule() = securePrefs.edit().remove(KEY_DISABLED).apply()

    fun isConfigPasswordSet(): Boolean = securePrefs.contains(KEY_CFG_PWD)
    fun getConfigPasswordHash(): String = securePrefs.getString(KEY_CFG_PWD, "") ?: ""
    fun setConfigPasswordHash(hash: String) = securePrefs.edit().putString(KEY_CFG_PWD, hash).apply()

    fun getConfigToken(): String = securePrefs.getString(KEY_CFG_TOKEN, "") ?: ""
    fun setConfigToken(t: String) = securePrefs.edit().putString(KEY_CFG_TOKEN, t.trim()).apply()

    fun getRepoOwner(): String = securePrefs.getString(KEY_OWNER, "") ?: ""
    fun setRepoOwner(v: String) = securePrefs.edit().putString(KEY_OWNER, v.trim()).apply()
    fun getRepoName(): String = securePrefs.getString(KEY_REPO, "") ?: ""
    fun setRepoName(v: String) = securePrefs.edit().putString(KEY_REPO, v.trim()).apply()
    fun getRepoBranch(): String = securePrefs.getString(KEY_BRANCH, "main") ?: "main"
    fun setRepoBranch(v: String) = securePrefs.edit().putString(KEY_BRANCH, v.trim().ifEmpty { "main" }).apply()
    fun getRepoPath(): String = securePrefs.getString(KEY_PATH, "rules.json") ?: "rules.json"
    fun setRepoPath(v: String) = securePrefs.edit().putString(KEY_PATH, v.trim().ifEmpty { "rules.json" }).apply()

    companion object {
        private const val KEY_TOKEN = "gh_token"
        private const val KEY_PWD = "pwd_hash"
        private const val KEY_CFG_PWD = "cfg_pwd"
        private const val KEY_CFG_TOKEN = "cfg_token"
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
