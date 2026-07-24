package com.simely.adskip.model

import org.json.JSONObject

/**
 * 一条手动捕获的按钮规则（指纹）。
 * 匹配优先级：viewId > text；pkg 用于缩小范围；activity 可选。
 */
data class Rule(
    val text: String?,
    val viewId: String?,
    val pkg: String,
    val activity: String?,
    val action: String = "click",
    val name: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text ?: JSONObject.NULL)
        put("viewId", viewId ?: JSONObject.NULL)
        put("pkg", pkg)
        put("activity", activity ?: JSONObject.NULL)
        put("action", action)
        put("name", name ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Rule = Rule(
            text = if (o.isNull("text")) null else o.optString("text").takeIf { it.isNotEmpty() },
            viewId = if (o.isNull("viewId")) null else o.optString("viewId").takeIf { it.isNotEmpty() },
            pkg = o.optString("pkg", ""),
            activity = if (o.isNull("activity")) null else o.optString("activity").takeIf { it.isNotEmpty() },
            action = o.optString("action", "click").takeIf { it.isNotEmpty() } ?: "click",
            name = if (o.isNull("name")) null else o.optString("name").takeIf { it.isNotEmpty() }
        )
    }

    /** 去重用的指纹键 */
    fun fingerprint(): String = "${pkg}|${activity ?: ""}|${viewId ?: ""}|${text ?: ""}"
}
