package com.simely.adskip.ui.components

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.simely.adskip.R
import com.simely.adskip.ui.theme.Theme
import com.simely.adskip.model.Rule
import com.simely.adskip.store.BlockedRuleStore
import com.simely.adskip.store.RuleStore

/**
 * 规则管理面板：按包名分组展示捕获规则、编辑、删除。
 */
class RuleSection(
    private val context: Context,
    private val ruleGroups: LinearLayout,
    private val ruleStore: RuleStore,
    private val blockedStore: BlockedRuleStore
) {
    private val tp get() = Theme.textPrimary(context)
    private val ts get() = Theme.textSecondary(context)

    fun render() {
        ruleGroups.removeAllViews()
        val groups = ruleStore.getRules().groupBy { it.pkg }
        var ci = 0
        for ((pkg, pkgRules) in groups) {
            ruleGroups.addView(makeGroup(pkg, pkgRules, Theme.chipColors[ci % Theme.chipColors.size]))
            ci++
        }

        // 屏蔽记录
        val blockedRules = blockedStore.getBlockedRules()
        if (blockedRules.isNotEmpty()) {
            ruleGroups.addView(makeBlockedSection(blockedRules))
        }
    }

    private fun makeGroup(pkg: String, rules: List<Rule>, color: Int): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Theme.dp(context, 4), 0, Theme.dp(context, 4))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Theme.dp(context, 4), Theme.dp(context, 6), Theme.dp(context, 4), Theme.dp(context, 6))
        }
        header.addView(TextView(context).apply {
            text = pkg.take(1).uppercase()
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(color); cornerRadius = Theme.dp(context, 12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(Theme.dp(context, 24), Theme.dp(context, 24))
        })
        header.addView(TextView(context).apply {
            text = pkg
            textSize = 12f
            setTextColor(tp)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Theme.dp(context, 8) }
        })
        header.addView(TextView(context).apply {
            text = "${rules.size} 条"
            textSize = 10f
            setTextColor(ts)
        })
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        rules.forEach { r -> panel.addView(makeRuleRow(r)) }
        container.addView(header)
        container.addView(panel)
        header.setOnClickListener {
            panel.visibility = if (panel.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        return container
    }

    private fun makeRuleRow(rule: Rule): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Theme.dp(context, 8), 0, Theme.dp(context, 8))
        }
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mainText = rule.text ?: rule.contentDescription ?: rule.viewId ?: "(空)"
        infoCol.addView(TextView(context).apply { text = mainText; textSize = 14f; setTextColor(tp) })
        val details = buildString {
            if (!rule.viewId.isNullOrEmpty()) append("ID: ${rule.viewId}")
            if (!rule.contentDescription.isNullOrEmpty() && rule.contentDescription != rule.text) {
                if (isNotEmpty()) append("\n"); append("描述: ${rule.contentDescription}")
            }
            if (!rule.className.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n"); append("类: ${rule.className}")
            }
            if (!rule.activity.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n"); append("Activity: ${rule.activity}")
            }
        }
        if (details.isNotEmpty()) {
            infoCol.addView(TextView(context).apply {
                text = details; textSize = 10f; setTextColor(ts); maxLines = 3
            })
        }
        row.addView(infoCol)

        // 编辑按钮
        row.addView(TextView(context).apply {
            text = "编辑"
            textSize = 13f
            setTextColor(0xFF007AFF.toInt())
            setPadding(Theme.dp(context, 12), Theme.dp(context, 8), Theme.dp(context, 16), Theme.dp(context, 8))
            setOnClickListener { showEditDialog(rule) }
        })

        // 删除按钮
        row.addView(TextView(context).apply {
            text = " 删除 "
            textSize = 12f
            setTextColor(0xFFFF3B30.toInt())
            setPadding(Theme.dp(context, 12), Theme.dp(context, 8), Theme.dp(context, 8), Theme.dp(context, 8))
            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("删除规则")
                    .setMessage("确定删除「${rule.text ?: rule.viewId ?: "-"}」吗？")
                    .setPositiveButton("删除") { _, _ ->
                        ruleStore.removeRule(rule.fingerprint())
                        render()
                    }
                    .setNegativeButton("取消", null).show()
            }
        })
        return row
    }

    private fun showEditDialog(rule: Rule) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(context, 24), Theme.dp(context, 16), Theme.dp(context, 24), Theme.dp(context, 16))
        }

        fun addField(label: String, value: String?) {
            container.addView(TextView(context).apply {
                text = label; textSize = 11f; setTextColor(ts); setPadding(0, Theme.dp(context, 8), 0, Theme.dp(context, 2))
            })
            container.addView(EditText(context).apply {
                setText(value ?: ""); textSize = 14f; setTextColor(tp)
            })
        }
        addField("文字 (text)", rule.text)
        addField("控件ID (viewId)", rule.viewId)
        addField("内容描述 (contentDesc)", rule.contentDescription)
        addField("类名 (className)", rule.className)
        addField("Activity", rule.activity)

        AlertDialog.Builder(context)
            .setTitle("编辑规则 — ${rule.pkg}")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val ets = (0 until container.childCount / 2).mapNotNull { i ->
                    (container.getChildAt(i * 2 + 1) as? EditText)?.text?.toString()?.trim()
                }
                val updated = rule.copy(
                    text = ets.getOrNull(0)?.takeIf { it.isNotEmpty() },
                    viewId = ets.getOrNull(1)?.takeIf { it.isNotEmpty() },
                    contentDescription = ets.getOrNull(2)?.takeIf { it.isNotEmpty() },
                    className = ets.getOrNull(3)?.takeIf { it.isNotEmpty() },
                    activity = ets.getOrNull(4)?.takeIf { it.isNotEmpty() },
                    name = ets.getOrNull(0)?.takeIf { it.isNotEmpty() }
                )
                if (updated.fingerprint() != rule.fingerprint()) {
                    ruleStore.removeRule(rule.fingerprint())
                    ruleStore.addRule(updated)
                }
                render()
            }
            .setNegativeButton("取消", null).show()
    }

    /**
     * 屏蔽规则展示区域：按包名分组，每项可取消屏蔽。
     */
    private fun makeBlockedSection(blockedRules: List<BlockedRuleStore.BlockedRule>): View {
        val blockedColor = 0xFFFF4444.toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Theme.dp(context, 8), 0, Theme.dp(context, 4))
        }
        // 标题
        container.addView(TextView(context).apply {
            text = "屏蔽规则 (${blockedRules.size})"
            textSize = 12f
            setTextColor(blockedColor)
            setPadding(Theme.dp(context, 4), Theme.dp(context, 4), Theme.dp(context, 4), Theme.dp(context, 6))
        })

        val blockedGroups = blockedRules.groupBy { it.pkg }
        for ((pkg, items) in blockedGroups) {
            val group = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Theme.dp(context, 4), Theme.dp(context, 4), Theme.dp(context, 4), Theme.dp(context, 4))
            }
            // 包名头
            group.addView(TextView(context).apply {
                text = pkg
                textSize = 12f
                setTextColor(tp)
                setPadding(0, Theme.dp(context, 4), 0, Theme.dp(context, 2))
            })
            // 每条屏蔽项
            for (item in items) {
                val label = listOfNotNull(
                    item.text.takeIf { it.isNotEmpty() && it != pkg },
                    item.viewId.takeIf { it.isNotEmpty() }
                ).joinToString(" | ")
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Theme.dp(context, 8), Theme.dp(context, 4), 0, Theme.dp(context, 4))
                }
                row.addView(TextView(context).apply {
                    text = label.ifEmpty { "(无标识)" }
                    textSize = 11f
                    setTextColor(ts)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(context).apply {
                    text = "取消屏蔽"
                    textSize = 11f
                    setTextColor(blockedColor)
                    setPadding(Theme.dp(context, 12), Theme.dp(context, 4), Theme.dp(context, 8), Theme.dp(context, 4))
                    setOnClickListener {
                        blockedStore.removeByFields(item.pkg, item.viewId, item.text)
                        render()
                    }
                })
                group.addView(row)
            }
            container.addView(group)
        }
        return container
    }
}
