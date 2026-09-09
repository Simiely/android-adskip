# AdSkip — Android 智能广告跳过工具

基于 Android 无障碍服务（AccessibilityService），自动识别并点击广告跳过按钮。无需 Root，无需 ADB。

## 核心功能

- **关键词匹配** — 默认匹配 "跳过"、"关闭广告" 等按钮，可自定义
- **自动规则捕获** — 首次点击成功后自动记录 App + 按钮特征，下次秒过
- **屏蔽规则** — 不想点的按钮可屏蔽，文字包含匹配
- **黑白名单过滤** — 黑名单/白名单独立管理，控制哪些 App 执行跳过
- **悬浮胶囊** — 可拖拽的浮球，显示/隐藏/取消操作
- **点击统计** — 按天/月/年统计点击量，最近记录带规则标记
- **GitHub 同步** — 规则和关键词可备份到 GitHub 仓库，多设备共享

## 快速开始

1. **安装 APK** — 从 [Releases](https://github.com/Simiely/android-adskip/releases) 下载
2. **开启无障碍** — 设置 → 无障碍 → 已安装的应用 → AdSkip → 开启
3. **开启悬浮窗权限** — 设置 → 权限 → 悬浮窗 → 允许
4. 打开任意有广告的 App，自动开始工作

## 使用说明

| 操作 | 方法 |
|------|------|
| 自定义关键词 | 首页 "关键词" 面板 → 输入 + 添加 |
| 手动捕获规则 | 点击悬浮球 → 捕获模式 → 点想捕获的按钮 |
| 屏蔽按钮 | 最近记录 → 点 "屏蔽" |
| 管理黑白名单 | "应用过滤" 面板 → 展开黑名单/白名单 |
| GitHub 同步 | "规则同步" 面板 → 解锁 → 设置仓库 → 同步 |

## 权限需求

- 无障碍服务：核心功能，检测并点击按钮
- 悬浮窗：显示浮动胶囊和点击反馈
- 通知权限：前台保活服务
- 开机自启（可选）：重启后自动恢复

## 系统要求

- Android 8.0+
- Xiaomi/OPPO/Vivo 等需手动开启"自启动"和"省电无限制"

## 开发者：调试与规则热更新

无需重新构建安装，通过 adb 广播即可动态更新规则、下发调试命令（需手机开启 USB/Wi-Fi 调试，且已开启 AdSkip 无障碍服务）。仓库提供一键脚本 `push_rules.ps1`：

```powershell
.\push_rules.ps1 set -File rules.json     # 用 JSON 文件整体替换规则
.\push_rules.ps1 set -Rules '<json>'      # 直接传 JSON 字符串
.\push_rules.ps1 clear                    # 清空全部规则
.\push_rules.ps1 clearPkg -Pkg <包名>      # 清空某包规则
.\push_rules.ps1 dump                     # 转储规则库
.\push_rules.ps1 state                    # 服务状态（前台/规则数/轮询/已执行数）
.\push_rules.ps1 tree                     # 转储前台节点树（定位广告元素）
.\push_rules.ps1 scan                     # 立即触发一次前台扫描
.\push_rules.ps1 trace [-Off]             # 匹配级追踪（tf开启 / -Off 关闭），定位误触
.\push_rules.ps1 hist                     # 动作历史（扫描/命中/屏蔽/达上限）
.\push_rules.ps1 fired                    # 查看本轮已执行规则
.\push_rules.ps1 firedReset               # 清空已执行集合，重新盯守
.\push_rules.ps1 pause / resume           # 紧急暂停 / 恢复整个服务
```

> 广播经由运行中的无障碍服务进程内动态注册的接收器送达（`RuleControlReceiver`），规避澎湃OS对 Manifest 静态接收器的后台执行限制。接收器注册为 `RECEIVER_EXPORTED`——因为 `adb shell am broadcast` 以 shell UID 发送，非导出接收器会拦截送达导致命令失效；这是个人调试接口，可接受此暴露面。

## 内置固化规则

部分规则已固化进程序（源码写死），随无障碍服务启动自动播种、去重幂等，**清理/清空规则库后仍会自动恢复**。它们属于"手动规则"，不会被自动学习机制停用。

> 说明：固化规则由实际抓取节点树确认、并经真机验证有效，因此写死进程序而非依赖自动捕获，保证"开箱即点"。

### 点击规则（`RuleStore.ensureBuiltInRules()`）

| 应用 | 场景 | 匹配方式 | 备注 |
|------|------|----------|------|
| 向日葵 | 顶部卡片广告 | `viewId = com.oray.sunlogin:id/close` | 右上角"折叠/关闭"X，点击后整张广告卡收起 |
| 向日葵 | 底部横幅广告 | `viewId = com.oray.sunlogin:id/fl_close_advertise` | 横幅自带"×"，自身可点，点击后横幅消失 |
| 向日葵 | 横幅"不喜欢" | `viewId = com.oray.sunlogin:id/iv_dislike` | 右上角"不喜欢"图标，本身非关闭，会弹出确认菜单 |
| 向日葵 | 不喜欢确认 | `text = 不感兴趣` | 在"不喜欢广告"菜单点第一项，真正关掉该广告；无 viewId，只能按文字匹配 |

### 硬屏蔽（`BlockedRuleStore` 内置）

| 应用 | 屏蔽目标 | 备注 |
|------|----------|------|
| 向日葵 | `viewId = com.oray.sunlogin:id/fl_screen_projection_status` | "投屏给他人"**功能卡**（非广告），点击会弹授权/功能窗；因与横幅广告同框且可点，被误判为可关闭目标。**常驻屏蔽、清空也无法移除**。不影响同包其他关闭规则 |
