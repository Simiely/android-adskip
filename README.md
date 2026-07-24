# AdSkip —— 澎湃 OS 3 跳过广告工具（自建）

基于 **无障碍服务（AccessibilityService）+ 悬浮窗 + 规则云同步** 的本地广告跳过工具。
无需 root、无需 ADB，事件驱动、零轮询，性能占用极低。

## 功能

- **混合匹配（方案 A）**：内置保守关键词（跳过/跳过广告/跳过视频广告/关闭广告）开箱即用；长尾 App 用悬浮胶囊手动捕获按钮指纹兜底。
- **无延迟点击**：界面变化即触发匹配并 `performAction(CLICK)`，命中即点。
- **悬浮胶囊**：常驻小圆点，点一下进入「捕获模式」，再去点真实「跳过」按钮即可收录；再点胶囊取消。
- **前台保活**：前台服务 + 常驻通知，配合系统白名单尽量不被杀。
- **全局总开关**：设置页一键暂停/恢复自动跳过，无需撤销无障碍权限。
- **规则云同步（隐藏菜单）**：设置页输入管理密码解锁 → 配置 GitHub 仓库 → 有 Token 可上传、无 Token 只能下载；下载合并去重。

## 目录结构

```
AdSkipApp/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/simiely/adskip/
        │   ├── AdSkipApplication.kt
        │   ├── AppState.kt
        │   ├── BootReceiver.kt
        │   ├── model/Rule.kt, RuleSet.kt
        │   ├── store/RuleStore.kt
        │   ├── util/SecurePrefs.kt
        │   ├── service/AdSkipAccessibilityService.kt, KeepAliveService.kt
        │   ├── float/FloatWindowManager.kt
        │   ├── sync/GitHubSync.kt
        │   └── ui/MainActivity.kt, SettingsActivity.kt
        └── res/ ...
```

## 构建（在 Windows + Android Studio）

1. 用 Android Studio 打开 `AdSkipApp` 目录（Gradle 会自动下载 AGP 8.5 / Kotlin 1.9 / 依赖）。
2. 连接手机（澎湃 OS 3，已开启 USB 调试），`Run` 或 `Build → Build Bundle(s)/APK → Build APK`。
3. 安装到手机。

> 沙箱环境无 Android SDK，源码在此处写好，编译需你在本地完成。

## 首次使用设置

1. 打开 App → 「开启无障碍服务」→ 在系统无障碍列表里启用 **AdSkip**。
2. 「开启悬浮窗权限」→ 授予「显示在其他应用上层」（HyperOS 还需额外开「后台弹出界面」）。
3. 「电池无限制 / 自启动白名单」→ 在应用管理里把 AdSkip 设为：
   - 省电策略 → **无限制**
   - 允许**自启动**
   - 在最近任务里**锁定**本应用
4. 回到 App，状态显示「运行中」，悬浮胶囊出现。

## 使用

- 遇到开屏/弹窗广告，若按钮文字命中关键词，自动跳过。
- 命不中的小众 App：点悬浮胶囊 → 进捕获模式 → 点真实「跳过」按钮 → 自动收录。下次同界面自动点。
- 管理规则：App 内「规则与同步设置」可查看/删除关键词与捕获规则。
- 全局开关：同一页顶部「总开关」可随时暂停/恢复自动跳过。

## 规则云同步（隐藏菜单）

1. 打开「规则与同步设置」→ 底部「规则云同步」框输入密码：
   - **首次**：直接设置管理密码（SHA-256 哈希后加密存储，绝不存明文）。
   - **之后**：输入正确密码才展开同步面板。
2. 填写仓库：`owner / repo / branch / path`（如 `你的名/adskip-rules/main/rules.json`）。
3. **下载**：无论是否有 Token 都可用（无 Token 走公开 raw 链接）。下载后关键词取并集、规则按指纹去重合并。
4. **上传**：需填写 GitHub Token；上传当前全部关键词 + 捕获规则（单文件 `rules.json`，base64 + sha 更新）。

### GitHub Token 配置（安全建议）

- 用 **Fine-grained Token**，仅授权目标仓库的 `Contents: Read/Write`。
- Token 经 Android Keystore（`EncryptedSharedPreferences`）加密存储，只在上传时经 HTTPS 发送。
- 仓库建议**私有**，避免规则泄露（规则本身不含账号/隐私）。

## 已知限制

- 银行/支付类 App 会检测无障碍并拒绝运行。
- WebView 内的广告文字可能不暴露给无障碍，点不到。
- 部分 App 会弹「检测到无障碍」警告。
- HyperOS 后台管控极严，未做白名单时仍可能被回收（务必按上文设置）。

## 技术要点（性能与稳定）

- **事件驱动**：只监听 `TYPE_WINDOW_STATE_CHANGED | TYPE_WINDOW_CONTENT_CHANGED`，回调内做轻量文本匹配，零轮询、零截图。
- **防止重复点击**：同一节点 800ms 内不重复点，避免误触。
- **零额外网络依赖**：GitHub 同步用 `HttpURLConnection` + `org.json`，不引入 OkHttp/Retrofit。
- **加密存储**：规则与 Token/密码均经 Keystore 加密。
