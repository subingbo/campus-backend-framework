# 4C1 · 日程页面

- 日期：2026-10-23
- 范围：日历、我的日程、收到的邀请、我创建的四个分区；活动卡与弹窗留给 4C2
- 证据：1440 月历、390 当日安排、390 忙闲共享

## 改动

- 使用 `PageHeader`：标题「日程」、说明、主操作「新建活动」、次操作「新建个人安排」；移除旧 hero。
- 四个 `el-tabs` 保留原名称/顺序；邀请数量改用 `ui-count`。
- 日历：白底 + 发丝边、token 化日期/事件；新增 `cal-cell--selected` 与独立的 `cal-cell--today`，不再把“选中日”当“今天”；活动/个人安排颜色改为 accent/surface 语义。
- 移动断点从 600px 统一到 768px：≤768 显示日期选择 + 当日列表，隐藏七列月历；不再重复显示日期。
- 当日安排、个人安排、邀请、我创建的行统一无阴影 + 发丝分隔；紧急活动用低饱和 warning 左边线。
- 忙闲共享改为轻量设置块：保留授权数、隐私说明、群开关 loading/失败恢复。
- 四个分区加独立 loading/error/EmptyState；邀请接受/拒绝加 pending 禁用。
- 保留：calendarEntries 合并/排序、跨月、+N、个人 CRUD 回调、忙闲乐观更新/回滚、活动权限/签到、`activityId`/`tab=invites` 深链、WS 清理。

## 实测

| 项目 | 结果 |
|---|---|
| 1440 月历 | 当日/选中日区分；事件条与 +N 可见；无横向滚动 |
| 390 当日 | 日期选择 + 当日行；标题/时间/地点/人数/操作不溢出 |
| 390 共享 | 2 个群授权、开关、隐私说明可见；无横向滚动 |
| Tabs | 日程/我的日程/收到的邀请/我创建的均可切换 |
| build / test | build 通过；50/50 |

## 截图

- `4c1-schedule-1440x900.png`
- `4c1-schedule-390x844.png`
- `4c1-share-390x844.png`

## 未做/边界

- `ActivityEntryCard` 仍用旧卡片样式，属于 4C2；本提交不代表日程页最终视觉完成。
- 活动/个人安排的接受、报名、签到、删除等写操作未在自动化中执行，避免改演示数据；4C2 做弹窗时一起回归。
- 失败注入只覆盖了代码路径，未逐个阻断四个接口。

## 4C2 · 活动卡与关联弹窗

### 改动

- `ActivityEntryCard`：去描边/阴影，改发丝分隔；标题允许换行；签到提示单独一行；签到码用 accent；成员区改实线；移动端断点 768。
- 工作台复用点仍由 `.ui-detail-panel .activity-card` 覆盖，未改 props/emit；工作台地点活动卡截图通过。
- `ActivityFormDialog`：保留 `activity-form-dialog` + `ui-dialog`，增加 `append-to-body`、`modal-class="ui-content-overlay"`；所有下拉/日期面板加 `ui-content-popper`；站点与地图选点改 surface-2；移动端 footer sticky、body 滚动。
- `ScheduleFormDialog`、`InviteFriendsDialog`：补 `ui-dialog`、`ui-content-overlay`、`append-to-body`；移动端 footer sticky。
- 活动详情、多地图选择 Dialog：补 `ui-dialog`、overlay、teleport。
- 移动端 `el-date-editor--datetimerange` 改为上下两行，修复 390 下日期时间被截断。

### 实测

| 项目 | 结果 |
|---|---|
| 390 新建活动 | 表单字段完整，日期/下拉、footer 可见；无横向滚动 |
| 390 个人安排 | 日期时间两行完整（2026-10-23 22:00:00 / 23:00:00），保存/取消可见 |
| 1440 活动详情 | 详情、行程时间线、活动卡、footer 正常 |
| 1440 活动卡 | 标题/时间/签到提示/按钮分层；工作台活动卡无回退 |
| build / test | build 通过；50/50 |

### 截图

- `4c2-form-390x844.png`
- `4c2-schedule-form-390x844.png`
- `4c2-detail-1440x900.png`
- `4c2-cards-1440x900.png`
- `4c2-cards-390x844.png`
- `4c2-workspace-1440x900.png`

### 未做/边界

- 报名、退出、签到、邀请、编辑保存、取消活动等写操作未实际提交；沿用原权限/签到窗口逻辑，只换视觉。
- `ElMessageBox` 取消/删除确认仍是 Element Plus 默认皮肤；未强行改，避免破坏焦点与确认语义。
- 邀请好友弹窗与地图多选弹窗未截图；已补主题类/teleport，留待阶段 5 任务链回归。
- 真机键盘与 200% 字体仍待阶段 5。

## 结论

- 日程页与活动卡/弹窗已完成本轮视觉统一；页面与弹窗均使用同一套暖灰底、发丝线、圆角、按钮与状态层级。
- 4C1 + 4C2 合计构成本轮日程改造，后续阶段 4D/4E 再处理好友/聊天/登录。
