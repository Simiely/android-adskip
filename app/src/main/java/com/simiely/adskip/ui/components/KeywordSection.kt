package com.simely.adskip.ui.components

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.simely.adskip.store.KeywordStore
import com.simely.adskip.ui.theme.Theme

class KeywordSection(
    private val context: Context,
    private val listKeywords: LinearLayout,
    private val keywordStore: KeywordStore
) {
    fun render() {
        listKeywords.removeAllViews()
        val keywords = keywordStore.getAll()
        if (keywords.isEmpty()) {
            listKeywords.addView(TextView(context).apply {
                text = "暂无自定义关键词"
                textSize = 12f
                setTextColor(Theme.textSecondary(context))
                setPadding(Theme.dp(context, 12), Theme.dp(context, 8), Theme.dp(context, 12), Theme.dp(context, 8))
            })
            return
        }
        keywords.forEachIndexed { i, kw ->
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(Theme.dp(context, 8), Theme.dp(context, 6), Theme.dp(context, 8), Theme.dp(context, 6))
                background = GradientDrawable().apply {
                    setColor(Theme.chipColors[i % Theme.chipColors.size])
                    cornerRadius = Theme.dp(context, 6).toFloat()
                }
            }
            chip.addView(TextView(context).apply {
                text = kw; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
            })
            chip.addView(TextView(context).apply {
                text = " x"; textSize = 13f; setTextColor(0x88FFFFFF.toInt())
                setPadding(Theme.dp(context, 6), 0, 0, 0)
                setOnClickListener { keywordStore.remove(kw); render() }
            })
            (chip.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, Theme.dp(context, 4), Theme.dp(context, 4))
            listKeywords.addView(chip)
        }
    }
}
