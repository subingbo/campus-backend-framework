# 4D · 好友、共享聊天与方案详情

- 日期：2026-10-23
- 范围：`/friends`、`ChatPanel`、`GroupPlanCard`、`/conversations/:id/plans/:planId`、地图群聊/地点聊复用点
- 证据：好友 1440/390、私聊 390、方案 1440、地图群聊 390

## 改动

- `/friends` 路由启用 `uiV2 + mobileNav`；页面改为 `content-page` + `PageHeader`，搜索改 `SearchBar`，分组用 `SectionTitle`，空态用 `EmptyState`。
- 好友/搜索/申请行统一无阴影发丝行；加载、错误+重试、空态分区独立；加好友、接受/拒绝、删除按行 pending 禁用；长昵称 ellipsis，手机操作与名字同一行。
- 私聊弹窗加 `ui-dialog`、`ui-content-overlay`、`append-to-body`；弹窗内容显式包 `.ui-v2`，让 `.ui-v2 .chat-panel` 皮肤进入 teleport。
- `ChatPanel`：补中文输入法 `isComposing`/229 保护；消息列表改为 flex 列，`align-self` 右对齐生效；保留 outbox/ACK/重连、READ、切换会话清理、AI handoff。
- `GroupPlanCard`：去旧浅蓝描边/背景，改 tokens；emoji 操作图标换 AppIcon；导航/成员/冲突/展开按钮加 focus；操作区始终可换行，窄容器不再依赖 600px 视口断点。
- `/conversations/:id/plans/:planId` 启用 `uiV2 + mobileNav`；改为 content-page + PageHeader（返回群聊放 actions）；地图/隐私/图例 token 化；私密提示与真实/示意线图例保留。
- 地图群聊/地点聊仍复用 ChatPanel；未新增会话路由或聊天工作台。

## 实测

| 项目 | 结果 |
|---|---|
| 好友 1440/390 | 页头、搜索、请求、好友列表；手机同一行操作；无横向滚动 |
| 私聊 390 | 弹窗、v2 蓝/灰气泡、输入与发送、未读/时间正常 |
| 地图群聊 390 | 底部 Sheet 内 ChatPanel + GroupPlanCard；导航/继续规划/输入可见 |
| 方案详情 1440 | 地图、真实路线图例、私密边界说明、GroupPlanCard、返回群聊 |
| build / test | build 通过；50/50 |

## 截图

- `4d-friends-1440x900.png`
- `4d-friends-390x844.png`
- `4d-chat-390x844.png`
- `4d-map-chat-390x844.png`
- `4d-plan-1440x900.png`

## 未做/边界

- 未实际发送聊天消息，避免写入演示数据；outbox/ACK/重连、IME 保护做了代码审查，未做真机 IME。
- 私密站点场景、非发起人继续规划失败、好友搜索失败注入未逐个触发；沿用原权限/错误处理。
- `ChatPanel.vue` 内旧 `.chat-agent-card*` 死样式未删除，靠 content-pages 的 `.ui-v2 .chat-panel .chat-agent-card` 覆盖；阶段 6 可清理。
- 真机键盘与安全区仍待阶段 5。
