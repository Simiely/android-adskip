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
    val className: String? = null,
    /** 祖先约束：命中节点的某一层祖先需持有该 viewId 才采纳（GKD 式"位于某容器内"的关系约束），
     *  用于根治"同类控件冒充关闭按钮"的坐标误配。null=不启用该约束。 */
    val ancestorViewId: String? = null,
    /**
     * 坐标固化匹配：屏幕上的绝对矩形 [left, top, right, bottom]。
     * 用于既无 viewId/text/描述、也无 className 可依的"纯位置按钮"（如波点开屏广告右上角X）。
     * 非空时，匹配器只接收集合矩形内可点击节点；null 表示不启用坐标匹配。
     */
    val bounds: List<Int>? = null,
    /** 已被可信路径成功点击的次数（自动捕获的确认证据） */
    val hits: Int = 0,
    /** true=转正可直接自动点击；false=候选，仅可信路径再次命中时才累计 hits 并升级 */
    val approved: Boolean = true,
    /** 建档时间（毫秒），用于候选规则的老化清理 */
    val createdAt: Long = 0L,
    /** 连续"点击后目标未消失"的次数（结果验证：广告未退场） */
    val misses: Int = 0,
    /** true=规则来自自动学习（可被结果验证降级）；false=手动/导入规则（永不自动降级） */
    val autoLearned: Boolean = false,
    /** true=被结果验证判定连错后自动停用（不再参与自动点击），需手动重建 */
    val disabled: Boolean = false
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
        put("aVid", ancestorViewId ?: JSONObject.NULL)
        put("bounds", bounds?.let { JSONObject().apply { put("l", it[0]); put("t", it[1]); put("r", it[2]); put("b", it[3]) } } ?: JSONObject.NULL)
        put("hits", hits)
        put("approved", approved)
        put("createdAt", createdAt)
        put("misses", misses)
        put("auto", autoLearned)
        put("disabled", disabled)
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

    /** 置信状态的一行说明，用于规则列表展示 */
    fun statusNote(): String =
        when {
            disabled && autoLearned -> "已停用(连错${misses}次)"
            approved && misses > 0 && autoLearned -> "已启用 · 连错${misses}次待复检"
            approved -> "已启用"
            else -> "试用($hits/${PROMOTE_THRESHOLD})"
        }

    /** 判定为"危险范式"：无任何可定位信息（仅 className/空），匹配时会泛滥命中整类控件 */
    fun isDangerousPattern(): Boolean {
        if (!bounds.isNullOrEmpty()) return false // 有坐标定位就不算危险
        return contentDescription.isNullOrBlank() && text.isNullOrBlank() && viewId.isNullOrBlank()
    }

    /**
     * 指定性分数：字段越精确、标识越强，分数越高。
     * 匹配时用它将精确规则置于通用关键词之前，降低误点。
     */
    fun specificity(): Int {
        var score = 0
        if (!viewId.isNullOrBlank()) score += 100 // viewId 是最强的稳定指纹
        if (!activity.isNullOrBlank()) score += 10
        val t = text?.trim().orEmpty()
        val c = contentDescription?.trim().orEmpty()
        if (t.isNotEmpty()) score += 20 + t.length.coerceAtMost(30)
        if (c.isNotEmpty()) score += 20 + c.length.coerceAtMost(30)
        if (!className.isNullOrBlank()) score += 8
        if (!ancestorViewId.isNullOrBlank()) score += 15 // 祖先容器约束是强的去歧义信号
        if (!bounds.isNullOrEmpty()) score += 60 // 显式坐标是强定位，仅次于 viewId
        return score
    }

    companion object {
        /** 自动捕获规则累计被可信路径命中多少次后转正 */
        const val PROMOTE_THRESHOLD = 3
        /** 自动学习规则连续"未退场"达到该次数后，自动降级停止自动点击 */
        const val MISS_LIMIT = 3

        fun fromJson(o: JSONObject): Rule = Rule(
            text = if (o.isNull("text")) null else o.optString("text").takeIf { it.isNotEmpty() },
            viewId = if (o.isNull("viewId")) null else o.optString("viewId").takeIf { it.isNotEmpty() },
            pkg = o.optString("pkg", ""),
            activity = if (o.isNull("activity")) null else o.optString("activity").takeIf { it.isNotEmpty() },
            action = o.optString("action", "click").takeIf { it.isNotEmpty() } ?: "click",
            name = if (o.isNull("name")) null else o.optString("name").takeIf { it.isNotEmpty() },
            contentDescription = if (o.isNull("cd")) null else o.optString("cd").takeIf { it.isNotEmpty() },
            className = if (o.isNull("clz")) null else o.optString("clz").takeIf { it.isNotEmpty() },
            ancestorViewId = if (o.isNull("aVid")) null else o.optString("aVid").takeIf { it.isNotEmpty() },
            bounds = if (o.isNull("bounds")) null else {
                val bo = o.getJSONObject("bounds")
                listOf(bo.optInt("l"), bo.optInt("t"), bo.optInt("r"), bo.optInt("b"))
            },
            hits = o.optInt("hits", 0),
            approved = o.optBoolean("approved", true),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            misses = o.optInt("misses", 0),
            autoLearned = o.optBoolean("auto", false),
            disabled = o.optBoolean("disabled", false)
        )
    }

    /** 去重用的指纹键（含 className 与 bounds，避免仅类名/坐标不同的规则被误删） */
    fun fingerprint(): String =
        "${pkg}|${activity ?: ""}|${viewId ?: ""}|${text ?: ""}|${contentDescription ?: ""}|${className ?: ""}|${ancestorViewId ?: ""}|${bounds ?: ""}"
}
