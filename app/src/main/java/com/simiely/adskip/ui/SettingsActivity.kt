package com.simely.adskip.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.simely.adskip.R
import com.simely.adskip.databinding.ActivitySettingsBinding
import com.simely.adskip.model.RuleSet
import com.simely.adskip.store.RuleStore
import com.simely.adskip.sync.GitHubSync
import com.simely.adskip.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var ruleStore: RuleStore
    private lateinit var secure: SecurePrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ruleStore = RuleStore(this)
        secure = SecurePrefs(this)

        binding.btnAddKeyword.setOnClickListener {
            val kw = binding.etKeyword.text.toString().trim()
            if (kw.isNotEmpty()) {
                ruleStore.addKeyword(kw)
                binding.etKeyword.text.clear()
                renderKeywords()
            }
        }

        // 全局总开关
        binding.switchMaster.isChecked = secure.getMasterEnabled()
        binding.switchMaster.setOnCheckedChangeListener { _, checked ->
            secure.setMasterEnabled(checked)
        }

        // 关键词开关
        binding.switchKeyword.isChecked = secure.getKeywordEnabled()
        binding.switchKeyword.setOnCheckedChangeListener { _, checked ->
            secure.setKeywordEnabled(checked)
        }

        // 首次进入：设置密码；之后：校验密码解锁隐藏同步菜单
        if (!secure.isPasswordSet()) {
            binding.etPassword.setHint(R.string.hint_set_password)
            binding.btnUnlock.setText("设置")
        }
        binding.btnUnlock.setOnClickListener { handleUnlock() }

        binding.btnDownload.setOnClickListener { onDownload() }
        binding.btnUpload.setOnClickListener { onUpload() }

        renderKeywords()
        renderRules()
    }

    override fun onResume() {
        super.onResume()
        renderKeywords()
        renderRules()
    }

    private fun handleUnlock() {
        val pwd = binding.etPassword.text.toString()
        if (pwd.isEmpty()) return toast("请输入密码")

        if (!secure.isPasswordSet()) {
            secure.setPasswordHash(SecurePrefs.hash(pwd))
            revealSyncPanel()
            binding.etPassword.text.clear()
            toast("已设置管理密码")
        } else if (SecurePrefs.hash(pwd) == secure.getPasswordHash()) {
            revealSyncPanel()
            binding.etPassword.text.clear()
            toast(R.string.toast_unlocked)
        } else {
            toast(R.string.toast_wrong_password)
        }
    }

    private fun revealSyncPanel() {
        binding.syncPanel.visibility = View.VISIBLE
        binding.etRepoOwner.setText(secure.getRepoOwner())
        binding.etRepoName.setText(secure.getRepoName())
        binding.etRepoBranch.setText(secure.getRepoBranch())
        binding.etRepoPath.setText(secure.getRepoPath())
        binding.etToken.setText(secure.getToken())
    }

    private fun readRepo(): RepoConfig {
        val owner = binding.etRepoOwner.text.toString().trim()
        val repo = binding.etRepoName.text.toString().trim()
        val branch = binding.etRepoBranch.text.toString().trim().ifEmpty { "main" }
        val path = binding.etRepoPath.text.toString().trim().ifEmpty { "rules.json" }
        return RepoConfig(owner, repo, branch, path)
    }

    private fun persistRepo(cfg: RepoConfig) {
        secure.setRepoOwner(cfg.owner)
        secure.setRepoName(cfg.repo)
        secure.setRepoBranch(cfg.branch)
        secure.setRepoPath(cfg.path)
    }

    private fun onDownload() {
        val cfg = readRepo()
        if (cfg.owner.isEmpty() || cfg.repo.isEmpty()) return toast("请填写仓库 owner 与 repo")
        persistRepo(cfg)
        secure.setToken(binding.etToken.text.toString().trim())

        startSync()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = secure.getToken()
                val json = if (token.isNotEmpty()) {
                    GitHubSync.downloadApi(cfg.owner, cfg.repo, cfg.branch, cfg.path, token).first
                } else {
                    GitHubSync.downloadRaw(cfg.owner, cfg.repo, cfg.branch, cfg.path)
                }
                if (json.isNotEmpty()) ruleStore.mergeRemote(RuleSet.parse(json))
                withContext(Dispatchers.Main) {
                    stopSync()
                    toast(R.string.toast_download_ok)
                    renderRules()
                    renderKeywords()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    stopSync()
                    toast(getString(R.string.toast_sync_fail, e.message ?: ""))
                }
            }
        }
    }

    private fun onUpload() {
        val token = binding.etToken.text.toString().trim()
        if (token.isEmpty()) return toast(R.string.hint_no_token_upload)
        val cfg = readRepo()
        if (cfg.owner.isEmpty() || cfg.repo.isEmpty()) return toast("请填写仓库 owner 与 repo")
        persistRepo(cfg)
        secure.setToken(token)

        startSync()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = ruleStore.exportSet().toJsonString()
                val (_, sha) = GitHubSync.downloadApi(cfg.owner, cfg.repo, cfg.branch, cfg.path, token)
                val ok = GitHubSync.upload(cfg.owner, cfg.repo, cfg.branch, cfg.path, token, content, sha)
                withContext(Dispatchers.Main) {
                    stopSync()
                    toast(if (ok) getString(R.string.toast_upload_ok) else getString(R.string.toast_sync_fail, "HTTP 失败"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    stopSync()
                    toast(getString(R.string.toast_sync_fail, e.message ?: ""))
                }
            }
        }
    }

    private fun startSync() {
        binding.pbSync.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = false
        binding.btnUpload.isEnabled = false
        binding.tvSyncStatus.text = "同步中…"
    }

    private fun stopSync() {
        binding.pbSync.visibility = View.GONE
        binding.btnDownload.isEnabled = true
        binding.btnUpload.isEnabled = true
        binding.tvSyncStatus.text = ""
    }

    private fun renderKeywords() {
        binding.listKeywords.removeAllViews()
        for (kw in ruleStore.getUserKeywords()) {
            binding.listKeywords.addView(
                makeRow(kw, null) { ruleStore.removeKeyword(kw); renderKeywords() }
            )
        }
    }

    private fun renderRules() {
        binding.listRules.removeAllViews()
        val rules = ruleStore.getRules()
        binding.tvEmptyRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        for (rule in rules) {
            val primary = rule.name ?: rule.text ?: rule.viewId ?: "(无文字)"
            val secondary = "${rule.pkg}  ·  ${rule.viewId ?: "无ID"}"
            binding.listRules.addView(
                makeRow(primary, secondary) { ruleStore.removeRule(rule.fingerprint()); renderRules() }
            )
        }
    }

    private fun makeRow(primary: String, secondary: String?, onDelete: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val tv = TextView(this).apply {
            text = if (secondary != null) "$primary\n$secondary" else primary
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val del = Button(this).apply {
            text = "删除"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onDelete() }
        }
        row.addView(tv)
        row.addView(del)
        return row
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private data class RepoConfig(
        val owner: String, val repo: String, val branch: String, val path: String
    )
}
