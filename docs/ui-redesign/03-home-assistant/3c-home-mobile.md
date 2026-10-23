# 3C · 首页移动（全屏地图 + 68dvh Sheet）

- 日期：2026-10-23
- 范围：`/` 首页 ≤768；桌面 3B 不回退
- 账号：demo01

## 改动

- `MapListView.vue`
  - 移动端底部触发条「我的地图 · N / 查看共享地图」。
  - `BottomSheet`：`height="68dvh"`、`teleport=false`、`animated=false`、内联 z-index 22；头部创建 `+`/关闭，SearchBar，地图行，底部 primary「进入「地图名」」+「创建地图」+「用邀请码加入」。
  - 选中行更新地图标记；primary 进入当前地图；无地图时 primary 禁用并显示 EmptyState。
  - `updateMedia` 只在**进入移动端**时关闭 Sheet，移动端内部 resize（地址栏/键盘/转屏）不再误关。
  - 头部创建、无地图 EmptyState、底部「创建地图」三个入口都打开同一创建弹窗。
- `ui-v2.css`
  - `.home-map { z-index: 0 }`：把 AMap 内部 logo/控件的 z-index 收进地图栈，Sheet 和浮层不会再被 AMap logo 穿透。
  - 底部操作：primary 44；「创建地图」「用邀请码加入」均为 44 高命中区，左右分布；footer 使用 `env(safe-area-inset-bottom)`。
  - 触发条保留 `30px + env(safe-area-inset-bottom)` 底部安全区。
  - SearchBar 清空按钮视觉仍 20px，但 `::before` 扩大到 44×44 命中区；输入框沿用阶段 2 的 40px 高度。

## 实测

| 视口 | Sheet 高度 | 顶栏 | 触发条 | 横向滚动 |
|---|---|---|---|---|
| 390×844 | 390×574（68.0dvh） | 品牌文字 + 4 图标 + 头像 | 366×52 | 无（scrollWidth=390） |
| 375×667 | 375×454（68.1dvh） | 品牌仅图标 + 4 图标 + 头像 | 351×52 | 无（scrollWidth=375） |

390 Sheet 内部：头部创建/关闭 44×44；primary 44；底部两个文字入口 44；列表 `clientHeight = scrollHeight = 304`（2 张地图，无内部溢出）；白色 `rgb(255,255,255)`，顶部圆角 22px。

## 功能实测

| 项目 | 结果 |
|---|---|
| 触发条打开 Sheet | 打开；触发条隐藏 |
| 头部关闭 | Sheet 卸载，触发条恢复 |
| 选中第二张地图 | 行选中、primary 变为「进入「校园美食地图」」、地图标记 5 |
| primary 进入 | `/maps/53` |
| 头部 `+` 创建 | Sheet 关闭；创建弹窗（名称/描述/取消/创建） |
| 底部「用邀请码加入」 | Sheet 关闭；邀请码弹窗（1 输入/取消/加入） |
| 标记深链 | 点「校车站」→ `/maps/51?place=113`，工作台详情「校车站」 |
| 空地图列表（页面级 XHR stub） | 触发条「我的地图 · 0」；Sheet 显示「还没有地图」+ 创建；primary 禁用「选择一张地图」；底部创建/邀请码仍可达 |
| 搜索无结果 | 输入 zzz →「没有匹配的地图」+「清除搜索」；无横向滚动 |
| 768/769 边界 | 768：触发条、无桌面列表；769：桌面左列表、无胶囊 |
| 375 顶栏 | 品牌图 + 4 图标 + 头像，`scrollWidth = innerWidth = 375` |
| 构建 / 测试 | build 通过；`npm run test` 50/50 |

## 截图

- `3c-home-390x844.png`：默认（地图 + 触发条）
- `3c-home-sheet-390x844.png`：Sheet
- `3c-home-empty-390x844.png`：无地图
- `3c-home-search-empty-390x844.png`：搜索无结果
- `3c-home-375x667.png`：默认
- `3c-home-sheet-375x667.png`：Sheet
- `3c-home-empty-375x667.png`：无地图
- `3c-home-search-empty-375x667.png`：搜索无结果

## 问题闭环

| 问题 | 处理 | 复核 |
|---|---|---|
| AMap logo（z-index 160）盖在 Sheet footer 上 | `.home-map` 建立 z-index 0 栈 | `elementFromPoint` 命中底部按钮；截图无 logo 穿透 |
| 任意 resize 都会把 Sheet 关掉 | 只在 `!wasMobile` 时关闭 | 390→375 后 Sheet 仍在，截图通过 |
| Sheet 内只有「用邀请码加入」，且只有 36px 高 | 增加「创建地图」；两个文字入口 44px，左右分布 | 390/375 实测与截图 |
| footer 未考虑底部安全区 | `calc(12px + env(safe-area-inset-bottom, 0))` | CSS 复核；触发条原已有安全区 |
| 搜索清空按钮 20×20 命中区过小 | `::before` 扩到 44×44，视觉不变 | CSS 复核 |
| 空地图状态无法用 demo 数据触发 | 页面级 XHR stub 返回空列表，仅用于验收；不改后端/业务代码 | 390/375 空态截图；恢复后 reload |

## 未验收 / 环境限制

- Sheet 的 CSS 入场动画在隐藏 IAB 标签页会被暂停；截图前把动画强制到终态（`animation:none; opacity:1; transform:none`），DOM 与最终可见状态一致。真实可见浏览器会正常播放。
- 空地图截图使用查询级 stub，不代表后端真实空数据；创建/加入逻辑未改。
- 3C 未改 AI 助手；地图样式仍为 `fresh`。

## 视觉判定

- **移动首页：通过**。390/375 的默认、Sheet、无地图、搜索无结果均以真实截图复核；Sheet 白底、22px 圆角、68dvh、内容不溢出、主按钮/底部入口 44px、无横向滚动；AMap logo 不再穿透。
- **桌面：不回退**，3B 证据保持有效。
