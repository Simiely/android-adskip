package com.simely.adskip.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.simely.adskip.ui.theme.Theme
import com.simely.adskip.util.SecurePrefs

class FilterSection(
    private val context: Context,
    private val panelBlacklist: LinearLayout,
    private val panelWhitelist: LinearLayout,
    private val secure: SecurePrefs
) {
    fun render() {
        renderList(panelBlacklist, secure.getBlacklist(), "黑名单为空")
        renderList(panelWhitelist, secure.getWhitelist(), "白名单为空")
    }

    private fun renderList(panel: LinearLayout, pkgs: Set<String>, emptyText: String) {
        panel.removeAllViews()
        if (pkgs.isEmpty()) {
            panel.addView(TextView(context).apply {
                text = emptyText; textSize = 11f; setTextColor(Theme.textSecondary(context))
                setPadding(0, Theme.dp(context, 4), 0, Theme.dp(context, 4))
            })
            return
        }
        val pm = context.packageManager
        var ci = 0
        for (pkg in pkgs) {
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(Theme.dp(context, 8), Theme.dp(context, 5), Theme.dp(context, 8), Theme.dp(context, 5))
                background = GradientDrawable().apply {
                    setColor(Theme.chipColors[ci % Theme.chipColors.size])
                    cornerRadius = Theme.dp(context, 14).toFloat()
                }
                (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, Theme.dp(context, 6), Theme.dp(context, 6))
            }
            chip.addView(TextView(context).apply {
                text = appName; textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
            })
            chip.addView(TextView(context).apply {
                text = " x"; textSize = 11f; setTextColor(0x88FFFFFF.toInt())
                setPadding(Theme.dp(context, 4), 0, 0, 0)
                setOnClickListener { secure.removeFilterPkg(pkg); render() }
            })
            panel.addView(chip)
            ci++
        }
    }

    fun showAppPicker() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) null
            else Triple(pkg, ri.loadLabel(pm).toString(), pkg in secure.getFilterList())
        }.sortedBy { it.second.lowercase() }.distinctBy { it.first }
        val names = apps.map { "${it.second} (${it.first})" }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].third }
        AlertDialog.Builder(context)
            .setTitle("选择应用 (${apps.size})")
            .setMultiChoiceItems(names, checked) { _, i, c ->
                if (c) secure.autoAddFilterPkg(apps[i].first)
                else secure.removeFilterPkg(apps[i].first)
            }
            .setPositiveButton("确定") { _, _ -> render() }
            .setNegativeButton("取消", null).show()
    }
}
