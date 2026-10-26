# CHANGELOG

版本号为 `versionName`，`versionCode` 单调递增（当前 59）。
逐条问题记录见 [`DEVELOPMENT.md`](DEVELOPMENT.md)，更早版本以 `git log` 为准。

## 仓库整理 - 2026-10-26

- 文档：补齐根目录四件套（AGENTS / DEVELOPMENT / CHANGELOG）；`DEV_README.md` 合并进 DEVELOPMENT + AGENTS 后删除；`DEV.md` 保留为实现细节笔记
- 仓库卫生：移除 10 个历史构建 zip（`apk.zip` / `v60.zip` / `v.zip` 等），克隆体积下降；`adskip-keystore.zip` 暂留，待确认是否为可丢弃的调试密钥

## [1.0.59] - 2026-09-11

- 修复 activity 参数机制：用 `PackageManager` 校验 `event.className` 是否为真实 Activity，
  根除 `WINDOW_STATE_CHANGED` 传根视图类名（FrameLayout / DecorView）导致带 activity 限定的规则命中 0
- `activity` 改按包名记录（`activityFor`），消除跨包脏值；微博开屏 `SplashActivity` 限定从此稳定生效

## [1.0.58] - 2026-09-11

- 新增微博开屏广告跳过规则并固化（`SplashActivity` 作用域）
- 清理未验证的波点会员特惠横幅规则

## [1.0.55] - 2026-09-10

- 新增百度网盘悬浮栏关闭 X 规则（`float_btn_close`）

## 更早版本

- v1.0 起的功能与修复见 [`DEVELOPMENT.md` §十 Git Log 摘要](DEVELOPMENT.md) 与仓库 `git log`
- 该阶段主线：无障碍事件驱动 + 5 层匹配 + 自动学习 + 黑白名单 + 悬浮胶囊 + 统计 + GitHub 规则同步

## 相关文档

- [README](README.md) ｜ [DEVELOPMENT](DEVELOPMENT.md) ｜ [AGENTS](AGENTS.md)
