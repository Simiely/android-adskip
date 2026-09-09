package com.simely.adskip.util

/**
 * 广告跳过特征识别：只有看起来像"跳过广告/关闭弹窗"的按钮才被自动捕获成规则。
 * 用于把自动学习限定在广告语义内，避免把远程桌面入口、许可协议"同意"等真实功能按钮误学成规则。
 */
object AdFeature {

    private val TEXT_HINTS = listOf(
        "跳过", "关闭", "我知道了", "知道了", "了解", "忽略", "放弃",
        "skip", "close", "dismiss", "立即关闭", "取消广告", "跳过广告"
    )

    private val VIEW_ID_HINTS = listOf(
        "skip", "close", "ad", "advert", "dismiss", "banner", "logo_tip"
    )

    /**
     * @return true 表示该节点具备广告跳过/关闭按钮的特征
     */
    fun looksLikeAd(text: String?, viewId: String?, contentDescription: String?): Boolean {
        val vid = viewId?.lowercase().orEmpty()
        if (VIEW_ID_HINTS.any { vid.contains(it) }) return true
        val surface = (text.orEmpty() + "|" + contentDescription.orEmpty()).lowercase()
        return TEXT_HINTS.any { surface.contains(it) }
    }
}