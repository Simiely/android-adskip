package com.simely.adskip.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 完整规则集合：关键词 + 手动规则。用于 JSON 序列化与 GitHub 同步。
 */
data class RuleSet(
    val keywords: Set<String>,
    val rules: List<Rule>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("keywords", JSONArray(keywords.toList()))
        put("rules", JSONArray(rules.map { it.toJson() }))
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(o: JSONObject): RuleSet {
            val kw = mutableSetOf<String>()
            o.optJSONArray("keywords")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let { kw.add(it) }
                }
            }
            val rs = mutableListOf<Rule>()
            o.optJSONArray("rules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    runCatching { rs.add(Rule.fromJson(arr.getJSONObject(i))) }
                }
            }
            return RuleSet(kw, rs)
        }

        fun parse(json: String): RuleSet = fromJson(JSONObject(json))
    }
}
