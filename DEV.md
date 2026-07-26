# AdSkip 开发笔记

## 无障碍服务捕获机制

### `event.source` 返回子节点的问题

`AccessibilityEvent.TYPE_VIEW_CLICKED` 的 `event.source` 可能返回按钮内的子节点（�� Text/ImageView），而不是按钮本身。**必须调用 `resolveToNearestClickable()` 强制上溯到最近的可点击祖先**。这是李跳跳/GKD 等同类工具的标准做法。

```kotlin
// 错误：直接用 event.source，可能捕获到按钮内的文字标签
// 正确：resolveToNearestClickable(src) 上溯 8 层找可点击祖先
```

### 捕获事件类型扩展

自定义 View 和 WebView 内容不一定发射 `TYPE_VIEW_CLICKED`。建议同时监听：
- `TYPE_VIEW_CLICKED`
- `TYPE_VIEW_FOCUSED`
- `TYPE_VIEW_SELECTED`

并在 `accessibility_service_config.xml` 中声明这些事件类型。

### 高亮扫描深度与防抖

捕获模式下的红色高亮扫描需要平衡覆盖率和性能：
- 深度：5 层足够覆盖大部分 UI
- 上限：200 个节点防止扫描超时
- 防抖：200ms 间隔，只在 `CONTENT_CHANGED`/`STATE_CHANGED` 时刷新

### `accessibility_service_config.xml` 关键配置

```xml
notificationTimeout="100"  <!-- 不要设为 0，会导致事件风暴 -->
accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"  <!-- 缺一不可 -->
```

## 前台服务保活

### Android 14+ (API 34) 崩溃

`FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 类型必须配置 `<property>`：

```xml
<service android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="用于保持无障碍跳过广告服务的悬浮窗常驻运行" />
</service>
```

缺少此声明在 Android 14+ 上会直接抛 `ForegroundServiceTypeNotAllowedException`。

### 前台服务超时

`startForeground()` 必须在 `onCreate()` 中 5 秒内调用，否则系统杀进程。初始化 FloatWindowManager 等操作要放在 `startForeground()` 之后。

### BootReceiver 陷阱

如果 manifest 声明了 `BootReceiver` 但类文件不存在，系统广播触发 `ClassNotFoundException` → 进程崩溃。删除 manifest 声明时要同步删除 `RECEIVE_BOOT_COMPLETED` 权限。

## 浮窗与捕获

### 点击 vs 长按冲突

长按手势需要在 `ACTION_UP` 中检查 `longPressFired` 标记，否则长按打开主界面的同时会触发短按捕获：

```kotlin
ACTION_DOWN -> { longPressFired = false; handler.postDelayed(runnable, 500) }
// runnable 中设置 longPressFired = true 并执行长按逻辑
ACTION_UP -> { if (!moved && !longPressFired) onCapsuleTap() }
```

### 拖拽边界计算

不能用 `capsuleParams.width`（WRAP_CONTENT 时为 -2）做除法。用 `capsuleView?.width` 获取实际像素。API < 30 时 `currentWindowMetrics` 不可用，需降级用 `defaultDisplay.getRectSize()`。

## 节点回收

### 活树节点不能回收

通过 `.parent` 获取的节点是活树节点，由无障碍框架管理。调用 `recycle()` 会导致 native crash。

```kotlin
// ❌ resolvedNodes 中的父节点不能回收
resolvedNodes.forEach { if (it !in targets) it.recycle() }

// ✅ 只回收 findAccessibilityNodeInfosByText/ViewId 返回的新建节点
targets.forEach { it.recycle() }
```

### `findFocus()` 返回的节点必须回收

`root.findFocus(FOCUS_INPUT)` 和 `root.findFocus(FOCUS_ACCESSIBILITY)` 返回的节点引用也需要 `recycle()`。

### 递归子节点回收

遍历子节点时要特别小心：被添加到结果列表的子节点不能再回收，没有被添加的必须回收。

## 规则匹配

### 多级匹配优先级

```kotlin
viewId 精确匹配 → textCandidates [text, contentDesc, name] 逐个尝试
  → className 匹配（递归扫描可点击节点）
```

### className 必须纳入指纹

`Rule.fingerprint()` 必须包含 `className`，否则两个仅 className 不同的规则会被去重误删。

### 规则按包名过滤

手动捕获的规则只在捕获时的 App 中生效。在 `tryClick` 中必须在遍历规则前检查 `rule.pkg == pkg`。

## 输入法兼容

`inputType="textPassword"` 会导致多数第三方输入法（搜狗、百度等）禁用语音输入和手写功能。对本地配置密码使用 `inputType="text"` 即可。

## 屏障功能

### `isBlocked` 分隔符

用 `split("||")` 在 Kotlin 中会被当作正则（`|` 在这里是 OR 运算符），必须用 `\u0000` 或其他不可见字符做分隔符，或使用 `split("\\|\\|")`。

## 同步功能

### GitHub API 上传需要 sha

GitHub Contents API 更新现有文件时必须提供当前版本的 sha。首次创建时不需要。用 `downloadApi().second` 获取 sha 后再上传。

### 私有仓库需要 token

`downloadRaw()` 只支持公开仓库。有 token 时应该先用 `downloadApi()`。

## HyperOS 3 特殊处理

- `getInstalledApplications()` 受包可见性限制，需在 manifest 中声明 `<queries>`
- 使用 `queryIntentActivities` 替代 `getInstalledApplications`
- 省电策略 → 无限制；自启动 → 开启；多任务 → 锁定
