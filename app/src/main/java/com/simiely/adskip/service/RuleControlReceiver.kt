package com.simely.adskip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.simely.adskip.model.RuleSet
import com.simely.adskip.store.RuleStore
import com.simely.adskip.util.Logger

/**
 * 运行时规则热更新接口（调试用，无需重新构建安装）。
 * 通过 adb 向本接收器发送广播即可增删改查规则库：
 *   adb shell am broadcast -a com.simely.adskip.action.SET_RULES      --es rules <Base64(JSON)>
 *   adb shell am broadcast -a com.simely.adskip.action.CLEAR_RULES
 *   adb shell am broadcast -a com.simely.adskip.action.CLEAR_RULES_PKG --es pkg <pkg>
 *   adb shell am broadcast -a com.simely.adskip.action.DUMP_RULES
 * (开发便利工具，规则 JSON 以 Base64 传递，规避命令行引号转义问题)
 */
class RuleControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val rs = RuleStore(context)
        try {
            when (action) {
                ACTION_SET -> {
                    val b64 = intent.getStringExtra("rules") ?: return
                    val json = String(Base64.decode(b64, Base64.DEFAULT))
                    val parsed = RuleSet.parse(json)
                    rs.replaceAll(parsed.rules)
                    Logger.d("[规则控制] 热更新为 ${parsed.rules.size} 条")
                }
                ACTION_CLEAR_ALL -> { rs.clear(); Logger.d("[规则控制] 已清空全部规则") }
                ACTION_CLEAR_PKG -> {
                    val pkg = intent.getStringExtra("pkg") ?: return
                    rs.clearByPkg(pkg); Logger.d("[规则控制] 已清空 [$pkg]")
                }
                ACTION_DUMP -> {
                    val all = rs.allRules()
                    Logger.d("[规则控制] ---- 当前规则库(共 ${all.size} 条) ----")
                    all.forEach { r ->
                        Logger.d("[规则控制][${r.pkg}] name=${r.name} text=${r.text} viewId=${r.viewId} " +
                            "desc=${r.contentDescription} class=${r.className} bounds=${r.bounds} " +
                            "activity=${r.activity} approved=${r.approved} disabled=${r.disabled}")
                    }
                }
                // ===== 以下为服务级调试命令：转发给当前运行的无障碍服务实例 =====
                ACTION_SCAN, ACTION_PAUSE, ACTION_RESUME, ACTION_DUMP_STATE, ACTION_DUMP_TREE,
                ACTION_TRACE, ACTION_DUMP_HISTORY, ACTION_DUMP_FIRED, ACTION_RESET_FIRED,
                ACTION_PROBE, ACTION_DUMP_EVENTS -> {
                    val svc = AdSkipAccessibilityService.instance
                    if (svc == null) {
                        Logger.d("[规则控制] 服务未运行，无法执行 $action")
                    } else when (action) {
                        ACTION_SCAN -> svc.debugScanNow()
                        ACTION_PAUSE -> svc.debugSetPaused(true)
                        ACTION_RESUME -> svc.debugSetPaused(false)
                        ACTION_DUMP_STATE -> svc.debugDumpState()
                        ACTION_DUMP_TREE -> svc.debugDumpTree()
                        ACTION_TRACE -> svc.debugSetTrace(intent.getBooleanExtra("on", true))
                        ACTION_DUMP_HISTORY -> svc.debugDumpHistory()
                        ACTION_DUMP_FIRED -> svc.debugDumpFired()
                        ACTION_RESET_FIRED -> svc.debugResetFired()
                        ACTION_PROBE -> {
                            val b64 = intent.getStringExtra("json") ?: return
                            svc.debugProbe(String(Base64.decode(b64, Base64.DEFAULT)))
                        }
                        ACTION_DUMP_EVENTS -> svc.debugDumpEvents()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("[规则控制] 处理失败", e)
        }
    }

    companion object {
        const val ACTION_SET = "com.simely.adskip.action.SET_RULES"
        const val ACTION_CLEAR_ALL = "com.simely.adskip.action.CLEAR_RULES"
        const val ACTION_CLEAR_PKG = "com.simely.adskip.action.CLEAR_RULES_PKG"
        const val ACTION_DUMP = "com.simely.adskip.action.DUMP_RULES"
        // 服务级调试命令
        const val ACTION_SCAN = "com.simely.adskip.action.SCAN_NOW"
        const val ACTION_PAUSE = "com.simely.adskip.action.PAUSE"
        const val ACTION_RESUME = "com.simely.adskip.action.RESUME"
        const val ACTION_DUMP_STATE = "com.simely.adskip.action.DUMP_STATE"
        const val ACTION_DUMP_TREE = "com.simely.adskip.action.DUMP_TREE"
        // 新增调试接口：匹配追踪 / 动作历史 / 已执行集合查看与重置
        const val ACTION_TRACE = "com.simely.adskip.action.TRACE"
        const val ACTION_DUMP_HISTORY = "com.simely.adskip.action.DUMP_HISTORY"
        const val ACTION_DUMP_FIRED = "com.simely.adskip.action.DUMP_FIRED"
        const val ACTION_RESET_FIRED = "com.simely.adskip.action.RESET_FIRED"
        // 调试探针 / 增量事件流
        const val ACTION_PROBE = "com.simely.adskip.action.PROBE"
        const val ACTION_DUMP_EVENTS = "com.simely.adskip.action.DUMP_EVENTS"
    }
}