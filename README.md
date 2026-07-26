# AdSkip — Android 智能广告跳过工具

基于 Android 无障碍服务（AccessibilityService）的广告跳过工具，支持关键词自动匹配和手动捕获规则，无需 Root 权限。

## 核心功能

- **关键词匹配**：内置"跳过/跳过广告/关闭广告"等默认词，自动搜索并点击
- **手动捕获**：悬浮球一键进入捕获模式，点击 App 内按钮即生成规则
- **按 App 隔离**：手动捕获的规则只对当前 App 生效，互不干扰
- **黑/白名单**：独立过滤开关，可选择黑名单或白名单模式，捕获规则自动加入名单
- **屏蔽按钮**：历史记录中可屏蔽特定按钮，被屏蔽的按钮永不点击（优先级最高）
- **悬浮窗**：可拖动胶囊，点击进入捕获模式，长按打��主界面
- **统计面板**：今日/总计点击次数、运行天数、最近 100 条点击记录
- **规则同步**：支持 GitHub 私有/公开仓库上传下载规则
- **深色模式**：自适应系统深色/浅色主题

## 使用方法

### 1. 开启权限
- 无障碍服务：设置 → 无障碍 → AdSkip → 开启
- 悬浮窗权限：设置 → 应用 → AdSkip → 显示在其他应用上层 → 允许
- 电池优化：设置 → 应用 → AdSkip → 电池 → 无限制
- HyperOS 用户：还需开启"自启动"并锁住多任务

### 2. 关键词自动跳过
打开"关键词匹配"开关，内置关键词会自动搜索并点击匹配的按钮。

### 3. 手动捕获规则
1. 点击悬浮球进入捕获模式（屏幕出现红色半透明遮罩，可点击按钮标红）
2. 在目标 App 中点击你想跳过的按钮
3. 规则自动保存，只对该 App 生效

### 4. 应用过滤
- 开启"应用过滤"总开关
- 选择"黑名单"（名单内的不跳过）或"白名单"（只跳过名单内的）
- 手动捕获规则时自动将 App 加入名单

## 架构

```
app/src/main/java/com/simely/adskip/
├── service/           # 无障碍服务 + 前台保活服务
├── float/             # 悬浮窗管理 + 捕获高亮覆盖层
├── ui/                # MainActivity + 设置页
├── store/             # 规则存储 + 统计存储 + 安全配置
├── model/             # Rule / RuleSet 数据类
├── sync/              # GitHub API 同步
└── util/              # 安全配置 + 日志
```

## 技术要点

- `AccessibilityNodeInfo.findAccessibilityNodeInfosByText/ViewId` 匹配目标
- `resolveToNearestClickable()` 从子节点上溯到可点击祖先
- 规则指纹 `pkg|activity|viewId|text|contentDesc|className` 去重
- `EncryptedSharedPreferences` 存储规则和 Token
- 前台服务 `specialUse` + `<property>` 声明支持 Android 14+

## License

MIT
