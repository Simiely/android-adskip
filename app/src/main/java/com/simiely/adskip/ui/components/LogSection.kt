package com.simely.adskip.ui.components

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.simely.adskip.model.Rule
import com.simely.adskip.store.BlockedRuleStore
import com.simely.adskip.store.RuleStore
import com.simely.adskip.store.StatsStore
import com.simely.adskip.ui.theme.Theme
import com.simely.adskip.util.SecurePrefs

class LogSection(
    private val context: Context,
    private val listLogs: LinearLayout,
    private val ruleStore: RuleStore,
    private val blockedStore: BlockedRuleStore,
    private val stats: StatsStore,
    private val secure: SecurePrefs
) {
    private val tp get() = Theme.textPrimary(context)
    private val ts get() = Theme.textSecondary(context)
    private var showAllLogs = false
    var onRulesChanged: (() -> Unit)? = null

    fun render() {
        listLogs.removeAllViews()
        val logs = stats.getRecentLogs(if (showAllLogs) 100 else 10).sortedByDescending { log ->
            val textKey = log.text.ifEmpty { log.app }
            if (blockedStore.isBlocked(log.app, log.text.ifEmpty { null }, log.viewId.ifEmpty { null })) 1
            else if (ruleStore.getRules().any { it.pkg == log.app && it.text == textKey }) 1 else 0
        }
        if (logs.isEmpty()) {
            listLogs.addView(TextView(context).apply {
                text = "暂无记录"; textSize = 12f; setTextColor(ts)
                setPadding(Theme.dp(context, 12), Theme.dp(context, 8), Theme.dp(context, 12), Theme.dp(context, 8))
            })
            return
        }
        var ci = 0
        for (log in logs) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Theme.dp(context, 6), 0, Theme.dp(context, 6))
            }
            row.addView(TextView(context).apply {
                text = log.app.take(1).uppercase(); textSize = 11f; setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Theme.chipColors[ci % Theme.chipColors.size])
                    cornerRadius = Theme.dp(context, 14).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(Theme.dp(context, 28), Theme.dp(context, 28))
            })
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Theme.dp(context, 10)
                }
                addView(TextView(context).apply { text = log.app; textSize = 12f; setTextColor(tp) })
                addView(TextView(context).apply {
                    val detail = buildString {
                        append("点击: ${log.text.ifEmpty { "-" }}")
                        if (log.viewId.isNotEmpty()) append("  ID:${log.viewId}")
                    }
                    text = "$detail  ${log.formattedTime()}"; textSize = 10f; setTextColor(ts)
                })
            })

            val blocked = blockedStore.isBlocked(log.app, log.text.ifEmpty { null }, log.viewId.ifEmpty { null })
            val textKey = log.text.ifEmpty { log.app }
            val exists = ruleStore.getRules().any { it.pkg == log.app && it.text == textKey }

            // 已添加标签
            if (exists) row.addView(TextView(context).apply {
                text = "已添加"; textSize = 9f; setTextColor(0xFF007AFF.toInt()); gravity = Gravity.CENTER
                setPadding(Theme.dp(context, 6), Theme.dp(context, 2), Theme.dp(context, 6), Theme.dp(context, 2))
                background = GradientDrawable().apply { setColor(0x18007AFF.toInt()); cornerRadius = Theme.dp(context, 4).toFloat() }
            })

            // 操作按钮
            row.addView(TextView(context).apply {
                text = if (exists) "移除" else "添加"; textSize = 11f; gravity = Gravity.CENTER
                setPadding(Theme.dp(context, 10), Theme.dp(context, 4), Theme.dp(context, 10), Theme.dp(context, 4))
                if (exists) {
                    setTextColor(0xFFFF3B30.toInt())
                    background = GradientDrawable().apply { setColor(0x18FF3B30.toInt()); cornerRadius = Theme.dp(context, 6).toFloat() }
                    setOnClickListener {
                        val r = ruleStore.getRules().find { it.pkg == log.app && it.text == textKey }
                        if (r != null) { ruleStore.removeRule(r.fingerprint()); render(); onRulesChanged?.invoke() }
                    }
                } else if (blocked) {
                    visibility = android.view.View.GONE
                } else {
                    setTextColor(0xFFFFFFFF.toInt())
                    background = GradientDrawable().apply { setColor(0xFF007AFF.toInt()); cornerRadius = Theme.dp(context, 6).toFloat() }
                    setOnClickListener {
                        ruleStore.addRule(Rule(text = textKey, pkg = log.app, action = "click", viewId = log.viewId.ifEmpty { null }, activity = null, name = textKey))
                        secure.autoAddFilterPkg(log.app); render(); onRulesChanged?.invoke()
                    }
                }
            })

            // 屏蔽按钮
            row.addView(TextView(context).apply {
                text = if (blocked) "已屏蔽" else "屏蔽"; textSize = 11f; gravity = Gravity.CENTER
                setPadding(Theme.dp(context, 8), Theme.dp(context, 4), Theme.dp(context, 8), Theme.dp(context, 4))
                if (blocked) {
                    setTextColor(0xFFFF4444.toInt())
                    background = GradientDrawable().apply { setColor(0x18FF4444.toInt()); cornerRadius = Theme.dp(context, 6).toFloat() }
                    setOnClickListener { blockedStore.removeByFields(log.app, log.viewId, log.text.ifEmpty { log.app }); render(); onRulesChanged?.invoke() }
                } else {
                    setTextColor(0xFFFF4444.toInt())
                    setOnClickListener { blockedStore.add(log.app, log.text, log.viewId); render(); onRulesChanged?.invoke() }
                }
            })
            listLogs.addView(row)
            ci++
        }
        // 底部按钮
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Theme.dp(context, 4), 0, 0) }
        btnRow.addView(TextView(context).apply {
            text = if (showAllLogs) "收起" else "显示更多"
            textSize = 11f; setTextColor(ts)
            setPadding(0, Theme.dp(context, 4), Theme.dp(context, 12), Theme.dp(context, 4))
            setOnClickListener { showAllLogs = !showAllLogs; render() }
        })
        btnRow.addView(TextView(context).apply {
            text = "清空记录"; textSize = 11f; setTextColor(0xFFFF3B30.toInt())
            setPadding(Theme.dp(context, 8), Theme.dp(context, 4), 0, Theme.dp(context, 4))
            setOnClickListener { stats.clearLogs(); render() }
        })
        listLogs.addView(btnRow)
    }
}
