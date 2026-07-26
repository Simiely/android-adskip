package com.simely.adskip.ui.theme

import android.content.Context
import com.simely.adskip.R

object Theme {
    val chipColors = intArrayOf(
        0xFF007AFF.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(),
        0xFF34C759.toInt(), 0xFFAF52DE.toInt()
    )

    val Int.dp: Int get() = 1 // placeholder - actual impl needs density
    fun dp(ctx: Context, i: Int): Int = (i * ctx.resources.displayMetrics.density).toInt()

    fun textPrimary(ctx: Context) = ctx.resources.getColor(R.color.text_primary, null)
    fun textSecondary(ctx: Context) = ctx.resources.getColor(R.color.text_secondary, null)
}
