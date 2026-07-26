# AdSkip 开发手册

## 架构

```
store/                          service/
├── KeywordStore.kt              ├── AdSkipAccessibilityService.kt  (编排入口)
├── RuleStore.kt                 ├── KeepAliveService.kt            (前台保活+悬浮窗)
├── BlockedRuleStore.kt          ├── guard/FilterGuard.kt           (包名过滤)
├── StatsStore.kt                ├── matcher/RuleMatcher.kt         (5层匹配引擎)
                                 ├── capturer/CaptureManager.kt     (手势捕获)
model/                            └── executor/ClickExecutor.kt      (点击+冷却+屏蔽)
├── Rule.kt
└── RuleSet.kt                  ui/
                                 ├── MainActivity.kt
float/                            ├── components/ (Keyword/Log/Rule/Sync/Filter)
├── FloatWindowManager.kt         └── theme/Theme.kt
├── HighlightOverlay.kt
└── ClickHintOverlay.kt         util/
                                 ├── SecurePrefs.kt
sync/                             ├── AccessibilityUtil.kt
└── GitHubSync.kt                 └── Logger.kt
```

## 关键问题及解决方案

### 1. 屏蔽规则完全无效（最严重）

**现象**：用户在日志里点了"屏蔽"，按钮仍然被点击。

**根因链**：
1. `BlockedRuleStore` 与 `KeywordStore`/`RuleStore` 共用同一个 `EncryptedSharedPreferences` 文件 `"adskip_rules"`
2. `ensureSystemBlocked()` 向文件写入系统屏蔽包，但 `getAll()` 在文件不存在时返回空
3. 加上加密层的写入冲突，Service 和 Activity 可能看到不同状态

**解决方案**：
- `BlockedRuleStore` 改用**独立明文** SharedPreferences 文件 `"adskip_blocked"`
- `getAll()` 每次读取时自动合并系统默认包（不再依赖 `ensureSystemBlocked` 写入）
- 文字匹配从精确 `==` 改为 `contains`（按钮文字可能变化："关闭" → "关闭广告(5s)"）

### 2. 包级通配误拦截整个 App

**现象**：京东的"限时一元甜筒"、其他无关按钮都被屏蔽。

**根因**：
- 当按钮没有文字时，`log.text.ifEmpty { log.app }` 用包名当文字存储
- `isBlocked` 中有 `parts[2] == parts[0]`（文字==包名）的包级通配规则
- 该规则本用于系统包屏蔽，但任何没文字的按钮屏蔽都会触发

**解决方案**：
- 删掉 `isBlocked` 中的包级通配（系统包已在 `FilterGuard` 硬编码拦截）
- 屏蔽时不再用包名代填空文字 `log.text.ifEmpty { log.app }` → `log.text`

### 3. Service / Activity 代码版本不同步

**现象**：安装新 APK 后代码不生效，日志显示 `tryClick` 根本没被调用。

**根因**：Android 安装 APK 只重启 Activity，`AccessibilityService` 进程不受��响，还在跑旧代码。

**解决方案**：每次安装后必须在**系统设置 → 无障碍**里先关再开 AdSkip。这是 Android 机制，无法绕过。

### 4. 自动捕获宽泛 className 规则

**现象**："分类"、"限时一元甜筒"等无关按钮被误点击。

**根因**：`capturedRule` 的 `hasIdentity` 条件包含 `!clickable.className.isNullOrEmpty()`，但 `className` 可能是 `android.widget.TextView` 这样的通用类。这导致抓到一个 App 里任何同类按钮都被匹配。

**解决方案**：`hasIdentity` 改为只检查 `text` / `viewId` / `contentDescription`，去掉 `className`。

### 5. 白名单模式不自动收集 App

**现象**：切换到白名单模式后，名单始终为空。

**根因**：`autoAddFilterPkg` 内部检查了 `isFilterEnabled()`，当过滤开关关闭（默认）时直接返回。

**解决方案**：去掉 `isFilterEnabled()` 检查——收集名单和过滤名单是两件独立的事。名单应该始终收集，开关只管是否使用。

### 6. 黑白名单数据串扰

**现象**：切换黑白名单模式时，同一个 App 同时出现在两个名单里。

**根因**：只有一个 `filter_list` 键存储，两种模式共享。

**解决方案**：拆分为 `filter_blacklist` 和 `filter_whitelist` 两个独立集合，各自管理。

### 7. 悬浮窗状态不同步

**现象**：重启服务后悬浮窗总是出现，不跟随之前的隐藏设置。

**根因**：`KeepAliveService.onCreate()` 始终调用 `showCapsule()`，没有读取存储的偏好。

**解决方案**：先读 `SecurePrefs.isCapsuleEnabled()`，再决定是否显示。

## 调试技巧

- `adb logcat -s BLOCK_DBG` 查看屏蔽检查日志（service进程内）
- `adb shell run-as com.simely.adskip cat /data/data/com.simely.adskip/shared_prefs/adskip_blocked.xml` 查看屏蔽数据
- `adb shell run-as com.simely.adskip cat /data/data/com.simely.adskip/shared_prefs/adskip_stats.xml` 查看点击记录
- `adb shell run-as com.simely.adskip cat /data/data/com.simely.adskip/shared_prefs/adskip_ui.xml` 查看过滤配置
- 文件日志 `files/click_debug.txt` 在 Service 进程内有效，Activity 不可见

## 注意事项

1. **不要同时写入同一个 EncryptedSharedPreferences 文件**：加密层有竞态，存储不同数据用不同文件
2. **不要用 `Set<String>` 作为 SharedPreferences 键**：`Set<String>` 修改需要重新赋值整个集合，无法增量修改
3. **Service 代码更新必须手动重启无障碍服务**：不能依赖 APK 安装
4. **默认密码 12345678 / 123** 是硬编码的，建议用户自行修改
