# AdSkip 开发手册

记录开发过程中踩过的坑和关键决策，方便以后参考。

## 构建系统

### CI 签名

CI 构建 Release APK 需要正确的签名配置：

```kotlin
// app/build.gradle.kts
val ks = project.findProperty("KEYSTORE_FILE") as? String
if (!ks.isNullOrEmpty()) {
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(ks)
            storePassword = project.findProperty("KEYSTORE_PASSWORD") as? String
            keyAlias = project.findProperty("KEY_ALIAS") as? String
            keyPassword = project.findProperty("KEYSTORE_PASSWORD") as? String
        }
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
    }
}
```

**坑**：v1.0 的 `build.gradle.kts` 没有这个配置（只用于本地 Android Studio 构建），CI 构建出来的 APK 是 debug 签名，无法覆盖安装生产签名的旧版本，会报「解析包出错」。

### 工作流 artifact 路径

```yaml
# 错误：apk 文件可能不存在这个精确路径
path: app/build/outputs/apk/release/app-release.apk

# 正确：用通配符匹配所有 APK
path: app/build/outputs/apk/release/*.apk
```

**坑**：开启 R8 优化后 APK 可能输出为 `app-release-unsigned.apk` 或 `app-release.apk`，文件名不固定。

### 构建时添加 find 步骤定位 APK

```yaml
- run: find app/build/outputs -name "*.apk" -ls
```

构建日志里能看到 APK 实际位置和名称，排查 artifact 上传失败的神器。

## R8 / ProGuard 混淆

### 核心原则

调试时**先关掉 R8 确认崩溃不是混淆导致**，再逐步加规则。

```kotlin
isMinifyEnabled = false  // 先关掉确认
```

### 必须 keep 的类

```proguard
# 所有业务代码（最安全）
-keep class com.simely.adskip.** { *; }

# ViewBinding（layout XML 反射调用）
-keep class com.simely.adskip.databinding.** { *; }

# 自定义 View（layout XML 类名字符串引用，混淆后找不到）
-keep class com.simely.adskip.ui.ChartView { *; }

# 数据模型（JSON 反序列化用字段名）
-keepclassmembers class com.simely.adskip.model.** { <fields>; }

# 无障碍服务（系统通过 intent filter 绑定）
-keep public class * extends android.accessibilityservice.AccessibilityService { *; }

# EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }
```

### R8 闪退排查流程

1. 先设 `isMinifyEnabled = false` → 能运行 = R8 问题
2. 逐条加 keep 规则，每次只加一条
3. 用 `-keep class com.simely.adskip.** { *; }` 全 keep 兜底
4. 确认可运行后再删减为精确规则

**历史教训**：加 ChartView keep 规则时误删了 ViewBinding keep 规则行，导致 R8 删了所有 ViewBinding 类，App 闪退排查了 2 小时。

## 功能添加策略

### 黄金规则：每次只改一个东西

```
❌ 一次加 StatsStore + UI + ChartView + 密码面板 + R8 → 闪退无法定位
✅ 每次只推一个文件/一个功能 → 必现或必不现
```

### 建议顺序

1. 先加数据层文件（不引用）→ 构建 → 确认可运行
2. 加引用调用 → 构建 → 确认可运行
3. 加 UI 元素 → 构建 → 确认可运行
4. 开启 R8 → 构建 → 确认可运行

**历史教训**：一次推了 4 个文件修改（MainActivity + layout + 2 个 service），闪退后花了 10+ 个构建才定位到问题。

## 闪退排查

### HyperOS 3 调试

| 方法 | 操作 |
|------|------|
| Debug APK | `isMinifyEnabled = false` → 必弹崩溃框 |
| 拨号暗码 | `*#*#284#*#*` → 生成 Bug 报告 ZIP |
| 开发者选项 | 设置 → 更多设置 → 开发者选项 → 错误报告 |

**注意**：HyperOS 3 对 Release APK 的崩溃不会弹框，静默退出。

### 当 App 闪退且无任何错误信息时

1. 先回退到最后已知可用版本
2. 逐文件对比差异（用 GitHub API diff 或 `git diff`）
3. 每次只加一个文件的改动
4. 对于 layout 变化，先加空 View（不引用）→ 再加代码引用

## APK 签名

### 签名作用

- 同一签名的 APK 才能覆盖安装
- 签名字段存在 `META-INF/CERT.RSA`

### CI 签名 vs 本地签名

| 构建环境 | 签名 | 说明 |
|----------|------|------|
| CI (GitHub Actions) | keystore.jks（加密 ZIP 存储） | `secrets.ZIP_PASSWORD` 解压 |
| 本地 Android Studio | `~/.android/debug.keystore` | 自动生成 |

### Keystore 安全

- Keystore 文件以加密 ZIP 形式存在仓库根目录
- 解压密码、Key 密码存储在 GitHub Secrets
- CI 环境变量传入 Gradle：`-PKEYSTORE_FILE=`、`-PKEY_ALIAS=`

## EncryptedSharedPreferences

### 使用注意

- 必须在 `Application.onCreate()` 之后初始化
- 可在 `Service.onCreate()` 中使用（Service context 足够）
- 密码哈希用 SHA-256，不加盐（个人工具场景足够）

## 无障碍服务

### 性能

- 事件驱动，不轮询
- `onAccessibilityEvent` 中必须 `recycle(root)`，否则内存泄漏
- 关键词匹配可关闭省电

### 冷却机制

- 同一按钮 800ms 内不重复点击
- 防死循环：5 秒内同一规则触发 3 次自动关闭总开关

## CI/CD

### 当前配置

- 触发：push main 分支 + tag
- 构建：JDK 17 + Android SDK 34
- artifact 上传到 GitHub Actions
- Release 只在 tag push 时创建

### 常见失败

| 症状 | 原因 | 解决 |
|------|------|------|
| artifact 为 0 | APK 路径不匹配 | 用 `find` + 通配符 |
| 签名失败 | keystore ZIP 密码错误 | 检查 GitHub Secrets |
| R8 编译失败 | keep 规则不足 | 先关 R8 再排查 |
| compile error | 文件未同步 | 确认 push 了正确文件 |

## 实战复盘：2026-07-25 闪退排查

### 背景

从 v1.0 可用基线开始，需要添加多个功能：统计存储、点击日志、走势图、防死循环、内联设置、配置同步。

### 时间线

| 构建 | 改动 | 结果 | 教训 |
|------|------|------|------|
| #74 | StatsStore 单文件 | ✅ 可运行 | 单文件加不崩 |
| #70-73 | StatsStore + 日志 + 防死循环 + 统计显示 + 布局 | ❌ 闪退 | 一把加太多 |
| #68 | 纯 v1.0 + 统计卡片布局 | ❌ 闪退 | 仅 layout 变化也崩 |
| #65 | 纯 v1.0 + 数据层（无 UI） | ✅ 可运行 | 数据层安全 |
| #62 | v1.0 + 签名 | ✅ 可运行 | 基线确认 |

### 关键教训

**1. 每次只改一个东西**

5 个文件一起推 → 闪退 → 又花 10 个构建才回到基线。如果每次只推 StatsStore → 测试 → 再加日志 → 测试，最多 3 个构建搞定。

**2. R8 不是万能替罪羊**

关掉 R8（`isMinifyEnabled = false`）仍然闪退，说明问题在代码逻辑，不在混淆。不要上来就改 proguard 规则。

**3. 布局变化也很危险**

即使只是加了 3 个空 TextView（代码不引用），也可能导致闪退（#68）。可能原因：ViewBinding 生成类变化、资源引用问题、或 HyperOS 特殊处理。后续加 UI 元素时，先从**不引用**开始，确认能跑再加代码。

**4. 基线回退要彻底**

`full_revert.py` 脚本对比 v1.0 tag tree 和当前 tree，发现还有 AdSkipApplication.kt、AppState.kt 等文件未回退。第二次用 `clean.py` 才全量回退（除 build.gradle.kts 外全部）。回退后务必确认文件数匹配。

**5. HyperOS 3 调试方法**

- Release APK 闪退静默无提示 → 必须用 Debug 构建或拨号暗码
- 拨号 `*#*#284#*#*` 生成 Bug 报告（官方方法）
- `isDebuggable = true` 在 Release 中也有效

### 后续加功能建议流程

```
1. 加新文件（不引用） → 构建 → 安装测试
2. 在一处引用新文件  → 构建 → 安装测试
3. 修改 layout（不加代码引用）→ 构建 → 安装测试
4. 加代码引用 layout  → 构建 → 安装测试
5. 改 service/其他组件 → 构建 → 安装测试
```

每步之间**必须测试**，否则多步叠加无法定位根因。
