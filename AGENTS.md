# AGENTS.md · 项目规则

> 📌 **文档基线**：2026-10-26 · v1.0.59 · 合并重复稿、补齐四件套
> **更新文档/代码后，请更新此行**（日期 + 版本 + 摘要），并在 CHANGELOG 追加版本

## 技术栈

- **Kotlin + Android 原生**，无第三方框架；`minSdk 24 / targetSdk 35 / compileSdk 35`
- 核心机制：**AccessibilityService** 事件驱动（无轮询、无 Root、无 ADB）
- 存储：`EncryptedSharedPreferences`（AES256-GCM + Keystore）存规则与 Token；屏蔽表用独立**明文** SP 文件
- 同步：GitHub Contents API（`sync/GitHubSync.kt`，必须带 `User-Agent`，更新需先取文件 `sha`）
- 构建：Gradle 8.9 wrapper；CI `./gradlew assembleDebug`；签名只在 CI 传入 keystore 属性时启用

## 关键坑（改代码前必看）

- **装新 APK 后无障碍服务不会换代码**：安装只重启 Activity，Service 进程还在跑旧代码 → 必须到「设置 → 无障碍」先关再开
- **检测服务状态要用完整类名**：manifest 短格式类名会永远返回「未开启」
- **`android:packageNames="@null"` 可能不监听任何应用**：包过滤交给 `FilterGuard`，不要依赖它
- **点击目标要取可点击祖先**：直接点 `TextView` 往往无效
- **冷却 key 必须带包名**：只用 `text` / `viewId` 会让不同 App 的同名按钮互锁
- **捕获模式要临时收起悬浮胶囊**：它盖在最上层会吃掉触摸，导致永远捕获不到
- **`activity` 必须用 PackageManager 校验**：`WINDOW_STATE_CHANGED` 可能传根视图类名（FrameLayout / DecorView），带 activity 限定的规则会命中 0
- **不同数据不要共用同一个 EncryptedSharedPreferences 文件**：加密层有写竞态，Service 与 Activity 会看到不同状态
- **`Set<String>` 不能当 SharedPreferences 的增量键**：改一个元素也要整体重新赋值，无法局部更新

## 约定

- **固化规则只收真机验证过的**：`uiautomator dump` 确认 viewId / text / className → 真机点一次有效 → 才写进源码
  - 点击规则进 `RuleStore.ensureBuiltInRules()`；硬屏蔽进 `BlockedRuleStore` 内置
  - 只在 `onCreate` 播种一次，`addRule` 去重幂等；`autoLearned=false`，不会被连续 miss 停用
  - 硬屏蔽在 `getAll()` 每次查询时常驻合并，清空规则库也不会丢
  - 命名用「App·作用」中文；加完同步更新 README「内置固化规则」表
- UI 文案中文；日志走 `Logger` / `DebugLog`，不散落 `println`
- Token 用 fine-grained，只授权目标仓库 `Contents: Read/Write`
- `push_rules.ps1` 是个人调试入口（广播接收器 `RECEIVER_EXPORTED`，shell UID 才能送达），不要扩大到生产暴露面

## 常用命令

```bash
./gradlew assembleDebug          # 产物 app/build/outputs/apk/debug/
adb logcat -s BLOCK_DBG          # 屏蔽检查日志（Service 进程内）
adb shell run-as com.simely.adskip cat /data/data/com.simely.adskip/shared_prefs/adskip_blocked.xml
```

## 详细规则（按需 @引用）

- @DEVELOPMENT.md（目录与分层、架构决策、30 条问题记录、已知限制、调试技巧）
- @README.md（功能说明 + 内置固化规则表 + `push_rules.ps1` 命令集）
