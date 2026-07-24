# DEVELOPMENT.md —— 开发记录与关键问题

> 记录开发过程中遇到的每个关键问题、根因、解决方案，供后续维护和类似项目参考。
> **原则：一次踩坑，永久记录。**

---

## 一、架构决策

### 1.1 为什么选 AccessibilityService 而不是其他方案？

| 方案 | 优势 | 劣势 | 结论 |
|------|------|------|------|
| AccessibilityService | 事件驱动、零轮询、无 root、系统级 | 需用户手动开无障碍权限；部分 App 检测并拒绝 | ✅ 选用 |
| 定时截图 + OCR | 能"看"到任何按钮 | 持续截图 + OCR = 高 CPU / 耗电 / 慢 | ❌ 违背"低占用" |
| 坐标点击（root/Shizuku） | 无视任何 App | 需 root/Shizuku；坐标随分辨率/布局漂移 | ❌ 额外权限 |
| UIAutomator2 / ADB 脚本 | 完整 Android 测试框架 | 需 USB/ADB，不自洽 | ❌ 不自建 |

**关键认知**：AccessibilityService 的回调是系统主动推送的，不是我们轮询，这是"低占用"的根本。

### 1.2 为什么事件驱动不用协程？

最初版本在 `onAccessibilityEvent()` 里 `scope.launch { tryClick(root) }` 异步执行匹配。
但实际上 `onAccessibilityEvent` 已运行在服务主线程，回调本身是串行的，异步没有收益。

**改为同步执行**后：
- 去掉 `CoroutineScope` / `Dispatchers.Main` / `SupervisorJob`
- `tryClick(root)` 同步执行完成后 `root.recycle()` —— 节点不会因协程生命周期被延长导致泄漏
- 匹配逻辑足够快（毫秒级），不会阻塞下一个事件

### 1.3 匹配策略：关键词优先，规则兜底，关键词可关

```
关键词开关 开 → 遍历 4+ 关键词 → findAccessibilityNodeInfosByText × N
关键词开关 关 → 跳过遍历，直接到手动捕获规则
手动规则      → findAccessibilityNodeInfosByViewId（O(1)）或精确文本
```

关键词匹配的代价是每次界面变化遍历整棵节点树，关闭后**几乎零 CPU 开销**——这正是用户要求的"低占用"。


## 二、编译与资源问题

### 2.1 ❌ 颜色 `#xFFFFFFFF` 导致资源编译崩溃

**现象**：`mergeDebugResources` 报错 `Invalid <color> for given resource value`，报错位置在 appcompat 库的 values.xml，误导性强。

**根因**：`colors.xml` 中 `surface` 值的十六进制写成了 `#xFFFFFFFF`（多了个 `x`）。

**修复**：`#xFFFFFFFF` → `#FFFFFFFF`。

> 📌 **教训**：资源编译器报错如果位置在 library 里，先去检查自己的资源文件是否有格式错误。合并后的报错位置不代表根因位置。

### 2.2 ❌ `android:gap` 属性 LinearLayout 不支持

**现象**：`processDebugResources` 报 `attribute android:gap not found`。

**根因**：`android:gap` 是 API 33+ 的新属性，且 LinearLayout 在多数版本上不支持此属性（仅 ConstraintLayout 有 `layout_gap`）。

**修复**：删除 `android:gap="12dp"`，改用每个子元素的 `android:layout_marginBottom="12dp"`。

### 2.3 ❌ Kotlin `Set<String>` vs `MutableSet<String>` 编译错误

**现象**：`Unresolved reference: add` / `remove` / `addAll`。

**根因**：`getUserKeywords()` 声明返回 `Set<String>`（Kotlin 只读接口），但实际返回 `LinkedHashSet`（可变），而调用方调用了 mutator 方法。Kotlin 编译器只看声明类型。

**修复**：返回类型改为 `MutableSet<String>`。

> 📌 **教训**：即使运行时类型可变，Kotlin 编译时只看声明类型。需要 mutable 操作就声明 `MutableSet`/`MutableList`。

### 2.4 Gradle Wrapper 生成失败（`init.gradle` 语法错误）

**现象**：`gradle wrapper --gradle-version 8.9` 报 `Could not compile initialization script '/root/.gradle/init.gradle'`。

**根因**：沙箱的 `/root/.gradle/init.gradle` 有语法错误（`allprojects {` 不在正确的上下文中）。

**修复**：`mv /root/.gradle/init.gradle /root/.gradle/init.gradle.bak` 禁用，重跑 wrapper。

> 📌 生成 `gradle-wrapper.jar` 是启动 GitHub Actions 构建的前提（jar 是二进制，不能手写，只能靠 `gradle wrapper` 命令产出）。


## 三、无障碍服务

### 3.0 ❌ 检测无障碍状态用了 manifest 短格式类名 → 永远返回"未开启"

**现象**：用户已在系统设置中开启无障碍服务，但 App 一直提示"未开启"。

**根因**：`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 存储的组件名是 **完整类名**：
```
com.simely.adskip/com.simely.adskip.service.AdSkipAccessibilityService
```
但检测代码用了 manifest 的 `.` 短格式：
```kotlin
services.contains("com.simely.adskip/.service.AdSkipAccessibilityService")  // ❌ 永远匹配不上
```
`.service` 是 `AndroidManifest.xml` 中的包内缩写，只在构建时由 AAPT 展开。系统运行时存储的始终是完整限定的类名。

**修复**：改为完整类名：
```kotlin
services.contains("com.simely.adskip/com.simely.adskip.service.AdSkipAccessibilityService")
```

**影响范围**：`MainActivity.isAccessibilityEnabled()` 和 `BootReceiver.isAccessibilityEnabled()` 两处。


### 3.1 ❌ `android:packageNames="@null"` 导致服务可能不监听任何应用

**现象**：代码审计时发现。

**根因**：`@null` 是一个 null 资源引用，不是"监听所有"的意思。Android 处理方式不确定——可能解析为空字符串"不监听任何包"。

**修复**：**删除** `android:packageNames` 整行。无障碍配置中缺省此属性 = 监听所有应用。

### 3.2 ❌ 点击的是文本节点而非可点击祖先

**现象**：关键词匹配到"跳过"文字，但文字在非 clickable 的 `TextView` 上，真实点击事件绑定在父容器上，`performAction(CLICK)` 无效。

**场景**：很多 App 的"跳过"按钮是：
```xml
<FrameLayout clickable="true">          ← 真正可点击的
    <TextView text="跳过" />            ← 关键词匹配到的
</FrameLayout>
```

**修复**：新增 `resolveClickable()` 方法：
1. 检查匹配到的节点本身是否 `isClickable`
2. 不是则向上遍历父链直到找到可点击祖先
3. 对可点击祖先执行 `performAction(CLICK)`

### 3.3 `EncryptedSharedPreferences.create()` 可能抛异常 → 服务 crash

**风险**：少数设备上 Android Keystore 不可用，`EncryptedSharedPreferences.create()` 会抛异常。如果发生在无障碍服务 `onCreate` 中，服务直接 crash → 系统可能禁用无障碍。

**修复**：`ruleStore` / `secure` 改为可空类型，`onCreate` 包裹 `try/catch`，回调中做空安全检查后降级运行（不崩溃，但也不匹配——等用户重启服务）。

### 3.4 冷却 key 仅用 text/viewId → 不同 App 的同名按钮会互锁

**原实现**：`key = viewIdResourceName ?: text`。

**问题**：两个不同的 App 都有"跳过"按钮，点了其中一个后 800ms 内另一个也被跳过，可能不是用户意图。

**修复**：key 加上 `pkg` 前缀：`"$pkg|${clickable.viewIdResourceName ?: clickable.text}"`。


## 四、前台服务与 Android 版本兼容

### 4.1 ❌ `foregroundServiceType="dataSync"` 不适用于无障碍服务

**现象**：查阅 Android 官方文档发现 `dataSync` 类型定义为"数据传输操作"，与无障碍服务保活无关。

**官方要求**（Android 14+）：
| 服务类型 | 用途 | 无障碍服务适用？ |
|----------|------|------------------|
| `dataSync` | 上传/下载/备份 | ❌ 无关 |
| `mediaPlayback` | 媒体播放 | ❌ 无关 |
| **`specialUse`** | **所有上述不覆盖的有效用例** | ✅ **正确** |

**修复**：
1. Manifest: `android:foregroundServiceType="specialUse"`
2. 权限: `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
3. 添加 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="..." />`
4. `startForeground()` 在 API 34+ 传入 `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`

**Android 15 额外注意**：`dataSync` 类型从 `BOOT_COMPLETED` 启动已被限制。`specialUse` 不受此限制。

### 4.2 `startForeground` 双参数版本已弃用（API 29+）

**修复**：版本判断——API 34+ 用三参数 `startForeground(id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`，否则用双参数。

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
} else {
    startForeground(NOTIF_ID, notification)
}
```


## 五、GitHub 同步

### 5.1 ❌ API 调用缺少 `User-Agent` 头 → 403

**现象**：GitHub API 返回 403 或连接失败。

**根因**：[GitHub REST API 要求所有请求携带 `User-Agent` 头](https://docs.github.com/en/rest/using-the-rest-api/getting-started-with-the-rest-api#user-agent)。

**修复**：在 `downloadApi` 和 `upload` 中添加 `conn.setRequestProperty("User-Agent", "AdSkip-Android")`。

### 5.2 上传需获取文件 `sha` 才能更新

GitHub Contents API 更新文件必须带上当前文件的 `sha`（防止并发覆盖）。  
实现：先 `GET /repos/{owner}/{repo}/contents/{path}` 拿 `sha`，再 `PUT` 带上 `sha`。

### 5.3 下载 404 → 文件不存在 → 不应报错

`downloadApi` 对 404 返回 `"" to null`（空内容 + 无 sha），调用方 `onDownload` 通过 `json.isNotEmpty()` 判断，文件不存在时静默跳过合并。


## 六、HyperOS 3 保活要点

| 设置项 | 路径 | 说明 |
|--------|------|------|
| 省电策略 → 无限制 | 设置 → 应用管理 → AdSkip → 省电策略 | 防止系统闲置杀进程 |
| 允许自启动 | 同上 | 开机 / 更新后自动拉起 |
| 任务栏锁定 | 最近任务 → 长按 AdSkip → 锁定 | 防止手动清理时误杀 |
| 后台弹出界面 | 应用管理 → AdSkip → 权限 | 悬浮窗在后台时能弹出（HyperOS 特有） |

> **即使做了以上全部**，HyperOS 仍可能在极端内存压力下回收服务。这是厂商 ROM 的已知特性，不是 bug。


## 七、CI/CD

### 7.1 GitHub Actions 构建流程

```yaml
on:
  push:
    branches: [main]
    tags: ['v*']          # ← 必须显式加上，否则 tag push 不触发
```

每次 push main 自动构建 Debug APK，推送 tag（如 `v1.0`）时触发 release job，自动创建 Release 并附上 APK。

### 7.2 构建环境要求

- Java 17（Temurin）
- Android SDK API 34 + build-tools 34.0.0
- Gradle 8.9（通过 wrapper）

### 7.3 本地构建 vs CI 构建

本地（Windows + Android Studio）：打开目录 → Build APK。
CI（GitHub Actions）：`./gradlew assembleDebug`，产出在 `app/build/outputs/apk/debug/`。Debug APK 自动用 debug keystore 签名，可直接安装。


## 八、安全设计

| 数据 | 存储方式 | 说明 |
|------|----------|------|
| Token / 密码哈希 | `EncryptedSharedPreferences`（AES256-GCM + Keystore） | 绝不明文落盘 |
| 规则（关键词+指纹） | `EncryptedSharedPreferences` | 规则不含隐私，但加密无害 |
| 网络传输 | HTTPS only | GitHub API / raw.githubusercontent.com |
| Token 建议 | Fine-grained Token，仅授权目标仓库 `Contents: Read/Write` | 最小权限 |


## 九、已知技术限制

1. **银行/支付类 App 反无障碍**：检测到无障碍服务后拒绝运行或弹警告。
2. **WebView 广告**：WebView 内部的 DOM 节点不暴露给 Android 无障碍服务，文字匹配失效。
3. **Activity 名无法获取**：捕获规则时 `activity` 字段为 `null`（获取当前 Activity 需要 `PACKAGE_USAGE_STATS` 或 `QUERY_ALL_PACKAGES` 权限，对用户侵入性强，未加）。
4. **`notificationTimeout="0"` + `typeWindowContentChanged`**：高频 UI 变化场景下可能产生密集回调，但这是"无延迟"的必要条件。可通过关闭关键词开关大幅降低。


## 十、Git Log 摘要

| Commit | 说明 |
|--------|------|
| `v1.0: 无障碍跳过广告...` | 初始版本，全部功能 |
| `fix: colors.xml #x -> #` | 修复非法十六进制颜色资源编译失败 |
| `fix: 移除 LinearLayout android:gap` | LinearLayout 不支持 gap 属性 |
| `fix: Set→MutableSet` | Kotlin 编译类型错误 |
| `fix: workflow 添加 tags 触发` | CI 自动 Release |
