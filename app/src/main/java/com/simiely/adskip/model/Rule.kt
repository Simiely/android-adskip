package com.simely.adskip.model

import org.json.JSONObject

/**
 * 一条手动捕获的按钮规则（指纹）。
 *
 * 匹配优先级：viewId > text > contentDescription > className
 * pkg 用于缩小范围；activity 可选。
 */
data class Rule(
    val text: String?,
    val viewId: String?,
    val pkg: String,
    val activity: String?,
    val action: String = "click",
    val name: String?,
    val contentDescription: String? = null,
    val className: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text ?: JSONObject.NULL)
        put("viewId", viewId ?: JSONObject.NULL)
        put("pkg", pkg)
        put("activity", activity ?: JSONObject.NULL)
        put("action", action)
        put("name", name ?: JSONObject.NULL)
        put("cd", contentDescription ?: JSONObject.NULL)
        put("clz", className ?: JSONObject.NULL)
    }

    /** 匹配时可用的全部文本候选（按优先级排序） */
    fun textCandidates(): List<String> = listOfNotNull(
        text?.takeIf { it.isNotBlank() },
        contentDescription?.takeIf { it.isNotBlank() },
        name?.takeIf { it.isNotBlank() && it != text }
    )

    /** 简约可读的一行描述 */
    fun shortDescription(): String = buildString {
        if (!text.isNullOrBlank()) append("文字=$text")
        if (!viewId.isNullOrBlank()) { if (isNotEmpty()) append(" | "); append("ID=$viewId") }
        if (!contentDescription.isNullOrBlank()) { if (isNotEmpty()) append(" | "); append("描述=$contentDescription") }
        if (!className.isNullOrBlank()) { if (isNotEmpty()) append(" | "); append("类=$className") }
        if (isEmpty()) append("(空规则)")
    }

    companion object {
        fun fromJson(o: JSONObject): Rule = Rule(
            text = if (o.isNull("text")) null else o.optString("text").takeIf { it.isNotEmpty() },
            viewId = if (o.isNull("viewId")) null else o.optString("viewId").takeIf { it.isNotEmpty() },
            pkg = o.optString("pkg", ""),
            activity = if (o.isNull("activity")) null else o.optString("activity").takeIf { it.isNotEmpty() },
            action = o.optString("action", "click").takeIf { it.isNotEmpty() } ?: "click",
            name = if (o.isNull("name")) null else o.optString("name").takeIf { it.isNotEmpty() },
            contentDescription = if (o.isNull("cd")) null else o.optString("cd").takeIf { it.isNotEmpty() },
            className = if (o.isNull("clz")) null else o.optString("clz").takeIf { it.isNotEmpty() }
        )
    }

    /** 去重用的指纹键（含 className，避免仅类名不同的规则被误删） */
    fun fingerprint(): String = "${pkg}|${activity ?: ""}|${viewId ?: ""}|${text ?: ""}|${contentDescription ?: ""}|${className ?: ""}"
}
