# AdSkip —— 澎湃 OS 3 跳过广告工具

基于 **无障碍服务（AccessibilityService）+ 悬浮窗手动捕获 + 规则 GitHub 云同步**。

> 无需 root、无需 ADB。事件驱动、零轮询。关键词可关闭以省电。

## 快速下载

📦 **[v1.0 APK 下载](https://github.com/Simiely/android-adskip/releases/download/v1.0/app-debug.apk)**

或到 [Releases](https://github.com/Simiely/android-adskip/releases) 页面取最新版本。

## 功能速览

| 功能 | 说明 |
|------|------|
| **混合匹配** | 内置保守关键词（跳过 / 跳过广告 / 跳过视频广告 / 关闭广告）开箱即用；长尾 App 用悬浮胶囊手动捕获 |
| **关键词开关** | 开启覆盖更广，关闭仅用手动规则——最省电 |
| **无延迟点击** | 界面变化即匹配，命中 `performAction(CLICK)` |
| **全局总开关** | 一键暂停/恢复，无需撤销无障碍权限 |
| **悬浮胶囊** | 常驻小圆点，点一下进捕获模式 → 点真实「跳过」按钮 → 自动收录 |
| **规则云同步** | 设置页密码门 → GitHub 仓库上传/下载（合并去重） |
| **前台保活** | 前台服务 + 常驻通知 + 开机自启 |


## 首次使用（重要）

### 1. 安装 APK
手机打开 [Release 页面](https://github.com/Simiely/android-adskip/releases) 下载 `app-debug.apk` 安装。  
如提示"未知来源"，到系统设置里允许「安装未知应用」。

### 2. 开启三项权限
| 步骤 | 操作 | 如何开 |
|------|------|--------|
| ① 无障碍 | 打开 App → 点「开启无障碍服务」→ 在系统无障碍列表启用 **AdSkip** | App 内一键跳转 |
| ② 悬浮窗 | 点「开启悬浮窗权限」→ 允许「显示在其他应用上层」 | App 内一键跳转 |
| ③ 电池白名单 | 点「电池无限制 / 自启动白名单」→ 省电策略选**无限制** + 允许**自启动** + 任务栏**锁定** | App 内一键跳转 |

> **HyperOS 3 额外步骤**：在应用管理里还要开「后台弹出界面」权限，否则悬浮胶囊在后台界面点不出来。

### 3. 确认运行
回到 App 看到「运行中：监听界面并自动跳过」，悬浮胶囊出现，即完成。

## 日常使用

| 操作 | 怎么做 |
|------|--------|
| 自动跳过广告 | 打开有广告的 App，自动识别并点击「跳过」类按钮 |
| 手动捕获按钮 | 点悬浮胶囊 → 点真实「跳过」按钮 → 自动收录，下次自动点 |
| 暂停/恢复 | 进「规则与同步设置」→ 顶部总开关 |
| 切省电模式 | 同上 → 关掉关键词开关，只用手动规则 |
| 管理规则 | 同上 → 查看/删除关键词与捕获规则 |
| 规则云同步 | 同上 → 输密码解锁 → 填 GitHub 仓库 → 下载/上传 |


## 规则云同步（隐藏菜单）

进入「规则与同步设置」，底部「规则云同步」：

1. **首次**输一个密码（自动加密存储，绝不存明文）
2. **之后**输入同一密码才展开同步面板
3. 填仓库 `owner / repo / branch / path`（如 `Simiely/adskip-rules/main/rules.json`）
4. **下载**：不填 Token 也能用（公开仓库 raw 链接）→ 自动合并去重
5. **上传**：需填写 [GitHub Fine-grained Token](https://github.com/settings/tokens)（仅授权目标仓库 `Contents: Read/Write`）

> 🔐 Token 经 Android Keystore 加密存储，仅上传时经 HTTPS 发送。


## 技术栈

| 层 | 技术 |
|----|------|
| 语言 | Kotlin 1.9 |
| 构建 | Gradle 8.9 + AGP 8.5 |
| UI | Material 3 + ViewBinding |
| 存储 | EncryptedSharedPreferences（Android Keystore 加密） |
| 网络 | HttpURLConnection + org.json（零额外依赖） |
| 保活 | 前台服务 `specialUse`（Android 14+ 合规） |
| CI/CD | GitHub Actions 自动构建 APK + Release |


## 架构

```
MainActivity (权限引导)
    │
    ├─→ SettingsActivity (规则管理 / 密码门 / 云同步)
    │
    └─→ KeepAliveService (前台保活) ─→ FloatWindowManager (悬浮胶囊)
            │
            └─→ AdSkipAccessibilityService (事件驱动核心)
                  ├─ onAccessibilityEvent() → 关键词/规则匹配 → performAction(CLICK)
                  └─ 捕获模式 → TYPE_VIEW_CLICKED → 记录节点指纹
```


## 已知限制

- 银行/支付类 App 可能检测并拒绝无障碍
- WebView 内的广告文字可能不暴露给无障碍服务
- HyperOS 后台管控极严，务必完成电池白名单 + 任务栏锁定

## License

GPL-3.0
