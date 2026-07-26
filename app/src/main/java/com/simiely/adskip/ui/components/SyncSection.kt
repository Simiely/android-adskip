package com.simely.adskip.ui.components

import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.simely.adskip.R
import com.simely.adskip.model.RuleSet
import com.simely.adskip.store.KeywordStore
import com.simely.adskip.store.RuleStore
import com.simely.adskip.sync.GitHubSync
import com.simely.adskip.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GitHub 规则同步面板：解锁、下载、上传。
 */
class SyncSection(
    private val activity: androidx.appcompat.app.AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val etPassword: EditText,
    private val btnUnlock: View,
    private val syncPanel: View,
    private val etRepoOwner: EditText,
    private val etRepoName: EditText,
    private val etRepoBranch: EditText,
    private val etRepoPath: EditText,
    private val etToken: EditText,
    private val btnDownload: View,
    private val btnUpload: View,
    private val pbSync: ProgressBar,
    private val tvSyncStatus: TextView,
    private val cfgPanel: View,
    private val etCfgToken: EditText,
    private val btnCfgDownload: View,
    private val btnCfgUpload: View,
    private val pbCfgSync: ProgressBar,
    private val tvCfgStatus: TextView,
    private val ruleStore: RuleStore,
    private val keywordStore: KeywordStore,
    private val secure: SecurePrefs
) {
    private val context = activity

    fun setup() {
        // 如果之前解锁过，直接显示面板
        val unlockedSync = secure.prefs().getBoolean("unlocked_sync", false)
        val unlockedCfg = secure.prefs().getBoolean("unlocked_cfg", false)
        if (unlockedSync) {
            etPassword.visibility = View.GONE
            btnUnlock.visibility = View.GONE
            syncPanel.visibility = View.VISIBLE
            etRepoOwner.setText(secure.getRepoOwner())
            etRepoName.setText(secure.getRepoName())
            etRepoBranch.setText(secure.getRepoBranch())
            etRepoPath.setText(secure.getRepoPath())
            etToken.setText(secure.getToken())
        }
        if (unlockedCfg) {
            etPassword.visibility = View.GONE
            btnUnlock.visibility = View.GONE
            cfgPanel.visibility = View.VISIBLE
            etCfgToken.setText(secure.getConfigToken())
        }
    }

    fun handleUnlock() {
        val pw = etPassword.text.toString()
        if (SecurePrefs.hash(pw) == secure.getConfigPasswordHash()) {
            cfgPanel.visibility = View.VISIBLE
            syncPanel.visibility = View.GONE
            etCfgToken.setText(secure.getConfigToken())
            etPassword.visibility = View.GONE
            btnUnlock.visibility = View.GONE
            secure.prefs().edit().putBoolean("unlocked_cfg", true).apply()
            toast("配置同步已解锁")
        } else if (SecurePrefs.hash(pw) == secure.getPasswordHash()) {
            syncPanel.visibility = View.VISIBLE
            cfgPanel.visibility = View.GONE
            etRepoOwner.setText(secure.getRepoOwner())
            etRepoName.setText(secure.getRepoName())
            etRepoBranch.setText(secure.getRepoBranch())
            etRepoPath.setText(secure.getRepoPath())
            etToken.setText(secure.getToken())
            etPassword.visibility = View.GONE
            btnUnlock.visibility = View.GONE
            secure.prefs().edit().putBoolean("unlocked_sync", true).apply()
            toast("规则同步已解锁")
        } else {
            toast("密码错误")
        }
    }

    fun syncOp(download: Boolean) {
        val o = etRepoOwner.text.toString().trim()
        val r = etRepoName.text.toString().trim()
        val br = etRepoBranch.text.toString().trim().ifEmpty { "main" }
        val p = etRepoPath.text.toString().trim().ifEmpty { "rules.json" }
        val t = etToken.text.toString().trim()

        if (o.isEmpty() || r.isEmpty()) {
            toast("请填写仓库 owner 与 repo")
            return
        }

        persistRepo(o, r, br, p)
        if (t.isNotEmpty()) secure.setToken(t)

        pbSync.visibility = View.VISIBLE
        tvSyncStatus.text = ""
        scope.launch(Dispatchers.IO) {
            try {
                if (download) {
                    val (json, _) = if (t.isNotEmpty())
                        GitHubSync.downloadApi(o, r, br, p, t)
                    else
                        GitHubSync.downloadRaw(o, r, br, p) to null
                    if (json.isNotEmpty()) {
                        RuleSet.parse(json)?.let { ruleStore.mergeRemote(it, keywordStore) }
                    }
                    withContext(Dispatchers.Main) { tvSyncStatus.text = "下载完成" }
                } else {
                    // 上传前先验证 Token
                    if (t.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            tvSyncStatus.text = "需要 GitHub Token"
                        }
                        return@launch
                    }
                    if (!GitHubSync.validateToken(t)) {
                        withContext(Dispatchers.Main) {
                            tvSyncStatus.text = "Token 无效 (401)，请检查：\n1. Token 是否过期\n2. 是否授权 Contents 读写"
                        }
                        return@launch
                    }

                    val rs = RuleSet(keywordStore.getAll(), ruleStore.getRules())
                    val sha = GitHubSync.downloadApi(o, r, br, p, t).second

                    // 如果有 token 但获取不到 sha，说明文件可能不存在或网络异常
                    val success = GitHubSync.upload(o, r, br, p, t, rs.toJsonString(), sha)
                    if (success) {
                        // 上传成功后立即再取一次 sha 作为缓存，下次快速更新
                        val (_, newSha) = GitHubSync.downloadApi(o, r, br, p, t)
                        withContext(Dispatchers.Main) { tvSyncStatus.text = "上传完成" }
                    } else {
                        withContext(Dispatchers.Main) { tvSyncStatus.text = "上传失败" }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvSyncStatus.text = "失败: ${e.message}" }
            }
            withContext(Dispatchers.Main) { pbSync.visibility = View.GONE }
        }
    }

    fun cfgSync(download: Boolean) {
        val t = etCfgToken.text.toString().trim()
        if (t.isNotEmpty()) secure.setConfigToken(t)
        pbCfgSync.visibility = View.VISIBLE
        tvCfgStatus.text = ""
        scope.launch(Dispatchers.IO) {
            try {
                if (download) {
                    val j = GitHubSync.downloadRaw("Simiely", "android-adskip", "main", "configs/rules.json")
                    val rs = RuleSet.parse(j)
                    if (rs != null) { ruleStore.mergeRemote(rs, keywordStore) }
                    withContext(Dispatchers.Main) { tvCfgStatus.text = "配置下载完成" }
                } else {
                    val rs = RuleSet(keywordStore.getAll(), ruleStore.getRules())
                    val sha = if (t.isNotEmpty())
                        GitHubSync.downloadApi("Simiely", "android-adskip", "main", "configs/rules.json", t).second
                    else null
                    val success = GitHubSync.upload("Simiely", "android-adskip", "main", "configs/rules.json", t, rs.toJsonString(), sha)
                    withContext(Dispatchers.Main) {
                        tvCfgStatus.text = if (success) "配置上传完成" else "配置上传失败"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvCfgStatus.text = "失败: ${e.message}" }
            }
            withContext(Dispatchers.Main) { pbCfgSync.visibility = View.GONE }
        }
    }

    private fun persistRepo(owner: String, repo: String, branch: String, path: String) {
        secure.setRepoOwner(owner)
        secure.setRepoName(repo)
        secure.setRepoBranch(branch)
        secure.setRepoPath(path)
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
