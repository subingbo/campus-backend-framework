# 阶段 0 · 现有 UI 审计与基线

- 日期：2026-10-23
- 账号：`demo01 / demo1234`（Map A `[DEMO] 周末去哪儿` id=51，6 个地点；Map B `[DEMO] 校园美食地图` id=53）
- 环境：后端 `http://localhost:8080`，前端 `http://localhost:5173`，Chromium IAB，DPR 1
- 截图：`docs/ui-redesign/00-baseline/screenshots/`（35 张页面矩阵 + 4 张关键状态）

## 1. 基线结果

| 项目 | 结果 |
|---|---|
| `npm run build` | 通过，11.51s；主包 1,345.19 kB（gzip 443.97 kB），有 >500 kB 警告 |
| `npm run test` | **50/50 通过**，0 fail |
| 页面矩阵 | 7 路由 × 5 视口 = 35 张，全部有文件 |
| 关键状态 | 桌面详情抽屉、桌面聊天浮层、桌面添加地点弹窗、移动详情浮窗，共 4 张 |

> 说明：IAB 截图通道偶发 `surface preparation timed out`，已用重建标签/视口微调重试补齐；个别截图重试过 2–5 次，但不影响页面事实。AMap 瓦片在部分截图里加载慢于 3s，出现灰底网格；重截后可见真实底图。

## 2. 量化事实（实测）

| 指标 | 实测值 | 结论 |
|---|---|---|
| 顶栏高度 | 56px，全宽 | 每个地图页固定损失 56px 地图高度 |
| 工作台侧栏 | 320 × 844（1440×900 下） | 固定占宽，地图从 x=320 开始 |
| 地图容器 | 1120 × 844 | 占视口面积约 **73%**；详情抽屉打开后可见地图约 740 × 844 ≈ **48%** |
| 地点项 | 高 74px，`0.667px solid rgb(229,231,235)`，圆角 8，底色 `rgb(250,250,250)` | 典型后台列表描边 |
| 主按钮 | `rgb(0,82,217)`，高 31px，圆角 8，蓝色投影 | 高饱和、尺寸偏小 |
| 移动消息行 | 高 105px，圆角 10，`rgba(31,56,88,.06)` 阴影 | 单行内容被包成厚卡 |
| 移动顶栏导航 | clientWidth 187 / scrollWidth 188 | **1px 溢出直接产生可见横向滚动条** |
| 移动页面 | `documentElement.scrollWidth = 390` | 页面本身无横向滚动；滚动条来自顶栏 nav |

## 3. 问题清单（36 条）

### P0 —— 地图不是主体

| ID | 问题 | 证据 |
|---|---|---|
| P0-1 | 移动端侧栏在上、地图在下；首屏完全看不到地图 | `screenshots/workspace-390x844.png`；`MapWorkspaceView.vue:1234-1251`（单列 + 地图 60vh） |
| P0-2 | 桌面端 320px 白色侧栏 + 硬分割，地图只占右侧 | `screenshots/workspace-1440x900.png`；`MapWorkspaceView.vue:934-947` |
| P0-3 | 首页没有地图，只是两张大卡片 | `screenshots/home-1440x900.png`；`MapListView.vue:13-25` |

### P1 —— 结构、层级与视觉语言

| ID | 问题 | 证据 |
|---|---|---|
| P1-1 | 三套卡片语言并存：阴影大卡/描边小卡/无卡平铺 | `MapListView.vue:177-188`；`ActivityEntryCard.vue:177-184`；`MessagesView.vue:389-399` |
| P1-2 | 圆角 8/10/12/16 混用，无统一比例 | `theme.css:35-36`；`MapWorkspaceView.vue:1088`；`MapListView.vue:181` |
| P1-3 | 地点列表项 1px 边框 + `#fafafa` 底，hover 变蓝 | `screenshots/workspace-1440x900.png`；`MapWorkspaceView.vue:978-1002` |
| P1-4 | 地点详情用 380px 全高 `el-drawer`，与地图无悬浮关系 | `screenshots/workspace-detail-1440x900.png`；`:236-280` |
| P1-5 | 添加地点用居中 `el-dialog`，遮罩切断地图上下文 | `screenshots/workspace-add-1440x900.png`；`:282-311` |
| P1-6 | 默认 EP 主按钮高饱和 `#0052d9`、全宽实底，视觉重量过大 | `screenshots/home-1440x900.png`；`theme.css:44-47` |
| P1-7 | 顶栏 56px 白底 + 底边线，所有页面像后台内容区 | `screenshots/messages-1440x900.png`；`AppShell.vue:126-135` |
| P1-8 | 聊天浮层 340px 直接压在地图右侧，无收起/让位逻辑 | `screenshots/workspace-chat-1440x900.png`；`:1160-1173` |
| P1-9 | AI 助手 400px 聊天列 + 1px 分割，仍是双栏后台 | `screenshots/assistant-1440x900.png`；`AssistantView.vue:1316-1331` |
| P1-10 | emoji 当图标（👥📍🧭📅⚠），与线性图标混用 | `MapListView.vue:19-20`；`ActivitiesView.vue:60`；`AssistantView.vue:128` |
| P1-11 | 消息中心约 80% 空白，页头 + Tabs + 单行卡片 | `screenshots/messages-1440x900.png`；`MessagesView.vue:1-49` |
| P1-12 | 活动日历格内文字被截断，事件芯片密集 | `screenshots/activities-1440x900.png`；`ActivitiesView.vue:18-44` |
| P1-13 | 移动顶栏 nav 1px 溢出产生横向滚动条 | `screenshots/messages-390x844.png`；`AppShell.vue:143-150` |
| P1-14 | 移动工作台侧栏与页面形成双滚动区，选点后滚动跳转突兀 | `screenshots/workspace-detail-390x844.png`；`:1234-1251`、`:706-709` |
| P1-15 | 移动详情浮窗 300px 内塞 7 个操作按钮，主次不分 | `screenshots/workspace-detail-390x844.png`；`:141-207` |
| P1-16 | 部分弹窗固定宽 460/380，小屏靠全局 `!important` 兜底 | `ScheduleFormDialog.vue:5`；`AssistantView.vue:331`；`theme.css:63-71` |
| P1-17 | 自定义按钮/芯片没有 `:focus-visible` 统一焦点环 | `AppShell.vue:185-198`；`MapWorkspaceView.vue:1110-1118` |
| P1-18 | 提示文字大量 12px `#888`/`rgba(0,0,0,.45)`，对比度临界 | `MapWorkspaceView.vue:1222-1225`；`ChatPanel.vue:390-395` |
| P1-19 | 登录页左侧大面积渐变 hero，模板感强 | `screenshots/login-1440x900.png`；`LoginView.vue:78-146` |
| P1-20 | 工作台侧栏成员权限下拉、邀请码、3 个定位按钮、引导卡挤在一起 | `screenshots/workspace-1440x900.png`；`:16-64` |
| P1-21 | 重复地点确认用单选列表，信息密度低 | `MapWorkspaceView.vue:313-325` |
| P1-22 | 活动/紧急项用 3px 左侧色条，制造硬边 | `ActivityEntryCard.vue:185-187`；`ActivitiesView.vue:806-810` |
| P1-23 | 聊天混用 `#409eff`、`#0052d9`、`#2979cc` | `ChatPanel.vue:448-538`；`theme.css:16` |
| P1-24 | 只有整页 `v-loading`，无 skeleton，加载时内容跳动 | `MapWorkspaceView.vue:2`；`PlanDetailView.vue:10-14` |
| P1-25 | 空状态散落为 `.hint.empty` 文本，无统一结构 | `MessagesView.vue:29`；`ActivitiesView.vue:47`；`MapWorkspaceView.vue:113-114` |
| P1-26 | 默认按钮有可见描边，主/次按钮重量接近 | `screenshots/workspace-1440x900.png`；`theme.css` 未覆盖默认按钮 |
| P1-27 | AMap 原生 ToolBar/Scale 与自定义 UI 风格不一致 | `screenshots/workspace-1440x900.png`；`useAmap.js:46-47` |
| P1-28 | 标记标签常显，与 AMap 默认 POI 标签竞争 | `map-markers.css:23-46`；`screenshots/workspace-1440x900.png` |

### P2 —— 一致性与细节

| ID | 问题 | 证据 |
|---|---|---|
| P2-1 | 23 处内联 style，工作台最集中 | `MapWorkspaceView.vue:22-32,39,47,58-63,68,204,225` |
| P2-2 | 页面标题 28px、面板 20px、正文 14px 无固定比例 | `MapListView.vue:162-170`；`MessagesView.vue:366-380` |
| P2-3 | 内容最大宽度 720/760/1100/1280 并存 | `FriendsView.vue:218-222`；`MapListView.vue:154-158`；`TraceCenterView.vue:841-845` |
| P2-4 | 阴影统一偏蓝 `rgba(31,56,88,…)`，与暖白目标不符 | 6 个页面重复出现 |
| P2-5 | 未读角标 `top:-6/right:-12` 与导航文字相压 | `AppShell.vue:155-168` |
| P2-6 | Tabs 下划线、RadioButton、Tag 三套选中样式 | `MessagesView.vue:11-27`；`ActivitiesView.vue:11` |
| P2-7 | 卡片 hover 位移 `translateY(-2px)`，在地图浮层上会晃 | `MapListView.vue:185-188` |
| P2-8 | AI 卡使用虚线边框 + 半透明蓝 | `ChatPanel.vue:448-452` |
| P2-9 | 群规划卡使用渐变 | `AssistantView.vue:1421-1428`；`GroupPlanCard.vue` |
| P2-10 | 引导 `el-alert` 与页面其他提示风格不统一 | `MapWorkspaceView.vue:71-76` |
| P2-11 | 移动端安全区只覆盖聊天输入，未覆盖底部浮层 | `ChatPanel.vue:632-640` |
| P2-12 | 过渡时长散落 0.15/0.2s，无 reduced-motion 统一处理 | `MapWorkspaceView.vue:992`；`MapListView.vue:183` |
| P2-13 | 坐标输入使用两个 `el-input-number`，视觉重量大 | `MapWorkspaceView.vue:294-305` |
| P2-14 | 页面滚动由 `.app-shell__main` 接管，浮层内部滚动与外层叠加 | `AppShell.vue:217-221` |
| P2-15 | 开发者页使用大量 `--app-*` token，反而与用户页不一致 | `TraceCenterView.vue:865-1087` |

## 4. 逐页现状

### 桌面
- **首页**：无地图；两个 `el-card` 撑不起 1440 宽，右侧与下方大面积空白；主按钮实底蓝，次按钮带描边。
- **工作台**：侧栏固定 320px，成员与权限控件占满首屏；地点列表被推到 y=1081，需滚动；地图 1120×844，但视觉上是“右侧画布”；详情抽屉 380px 全高；聊天 340px 浮层直接压住地图。
- **消息**：页头 + 下划线 Tabs + 一张 105px 高的行卡；页面 80% 空白；默认 RadioButton 蓝块。
- **活动**：内容宽 760px，月历占 680×500，格内事件文字被截断；下方行卡仍是描边/阴影混合。
- **好友**：720px 内容列，搜索框与好友行都是默认 EP 边框。
- **AI 助手**：400px 聊天 + 1px 分割 + 地图；聊天头部 3 个控件（会话/新对话/参考地图）挤在一行，草稿卡渐变。
- **登录**：50/50 分栏，左侧浅蓝渐变 + 大标题，右侧白底表单；整体像通用 SaaS 模板。

### 移动（390 / 375）
- **首页**：顶栏 nav 1px 溢出产生可见横向滚动条；两张卡片占满首屏，无地图。
- **工作台**：首屏只有群信息与成员列表；地图在下方 60vh；选中地点后页面滚到列表+地图交界，详情浮窗压在地图上，7 个操作按钮换行。
- **消息**：页头占两行，Tabs 与筛选行密集；行卡 105px，通知内容 3 行后仍有余量。
- **活动**：月历被替换为日期选择器 + 当日列表；行卡与标签仍然密集。
- **好友 / AI 助手**：均为单列，但顶部控件仍按桌面密度排列。

## 5. 已知限制

1. IAB 截图通道偶发超时，非页面问题；已重试补齐。
2. AMap 瓦片在部分截图里 3s 内未渲染完成，出现灰底；真实底图在后续重截中出现。
3. 本轮未覆盖 768×1024 与 1920 宽；阶段 5 的 6 视口矩阵会补 768，阶段 2 会补 1920。
4. 地点图片在当前后端不存在，审计未把图片缺失当作 UI 缺陷；详情页设计不依赖图片。
5. 未做真实网络异常/WS 离线截图；阶段 5 补。

## 6. 结论

现状的核心矛盾不是“颜色不好看”，而是**布局把地图降级为右侧内容区，且用大量描边卡片代替信息层级**。因此阶段 2 必须先改工作台结构，再谈全站统一；任何直接换色的做法都无法解决 P0-1/P0-2/P0-3。
