# 演示账号与数据说明

> 一键造数：`node tools/seed-demo.mjs`  
> 只检查不写入：`node tools/seed-demo.mjs --verify-only`  
> 前置：MySQL/Redis 与后端 `http://127.0.0.1:8080` 已启动。  
> 脚本非破坏性：只操作 `demo01–demo20` 与 `[DEMO]` 命名空间，不删除现有数据。

## 1. 登录账号

统一密码：`demo1234`

| 账号 | 昵称 | 地图与角色 |
|---|---|---|
| demo01 | 林晓 | `[DEMO] 周末去哪儿` OWNER；`[DEMO] 校园美食地图` EDITOR |
| demo02 | 陈晨 | `[DEMO] 周末去哪儿` EDITOR；`[DEMO] 校园美食地图` EDITOR；demo01 好友 |
| demo03 | 王宁 | `[DEMO] 周末去哪儿` EDITOR；`[DEMO] 校园美食地图` EDITOR；demo01 好友 |
| demo04 | 赵可 | `[DEMO] 周末去哪儿` EDITOR；向 demo01 发了待处理好友请求 |
| demo05 | 周予 | `[DEMO] 周末去哪儿` EDITOR |
| demo06 | 刘畅 | `[DEMO] 周末去哪儿` EDITOR |
| demo07 | 孙悦 | `[DEMO] 周末去哪儿` VIEWER |
| demo08 | 何川 | `[DEMO] 社团活动策划` OWNER |
| demo09 | 郭然 | `[DEMO] 社团活动策划` EDITOR |
| demo10 | 马骁 | `[DEMO] 社团活动策划` EDITOR |
| demo11 | 高远 | `[DEMO] 社团活动策划` EDITOR |
| demo12 | 罗晴 | `[DEMO] 社团活动策划` EDITOR |
| demo13 | 唐果 | `[DEMO] 社团活动策划` EDITOR |
| demo14 | 许安 | `[DEMO] 社团活动策划` EDITOR |
| demo15 | 沈知 | `[DEMO] 校园美食地图` OWNER |
| demo16 | 方圆 | `[DEMO] 校园美食地图` EDITOR |
| demo17 | 韩笑 | `[DEMO] 校园美食地图` EDITOR |
| demo18 | 郑一 | `[DEMO] 校园美食地图` EDITOR |
| demo19 | 吴桐 | `[DEMO] 校园美食地图` EDITOR |
| demo20 | 谢雨 | `[DEMO] 校园美食地图` VIEWER |

- **主演示账号：`demo01`**（Map A + Map C、好友、私聊、活动、日程都具备）。
- Map B 用 `demo08`；Map C 用 `demo15`。
- 管理端继续使用 `admin/admin123`；20 个 demo 账号都是普通 USER。

## 2. 三张地图/群聊

本项目群聊是共享地图的 1:1 会话，所以 3 个群聊对应 3 张地图。

### Map A `[DEMO] 周末去哪儿`（MEMBERS）

- 7 名成员：demo01（OWNER）、demo02–demo07；demo07 为 VIEWER。
- 6 个地点：东区图书馆、南门咖啡、西区运动场、一饭·二楼、快递驿站、校车站。
- 12 条群消息，多人对话；每个关键地点有 1–2 条评论。
- 3 个活动：
  - 周六桌游局（未来，南门咖啡，demo02/03/04 已报名）
  - 图书馆自习打卡（进行中，东区图书馆，demo02/03 已报名）
  - 上周羽毛球（已结束，西区运动场）
- 3 条地点动态及点赞/评论（通过地点动态接口查看）。
- demo01/demo02 各有 2 条日程，并对 Map A 共享 FREE_BUSY，给 `@AI` 现场规划提供可用/占用窗口。

### Map B `[DEMO] 社团活动策划`（MEMBERS）

- 7 名成员：demo08（OWNER）、demo09–demo14。
- 5 个地点：学生活动中心、社团会议室、打印店、北区超市、体育馆。
- 10 条群消息；1 个未来活动「社团招新碰头」。
- demo08/demo09 有日程并共享给 Map B。

### Map C `[DEMO] 校园美食地图`（PUBLIC）

- 9 名成员：demo15（OWNER）、demo16–demo20、demo01–demo03；demo20 为 VIEWER。
- 5 个地点：一饭烧腊窗口、南门奶茶街、夜市小吃摊、面包房、水果店。
- 10 条群消息；1 个未来活动「南门夜宵探店」。
- 3 条地点动态及点赞/评论。

## 3. 好友与私聊

- demo01 ↔ demo02、demo01 ↔ demo03 已互为好友；
- demo04 → demo01 保留 1 条待处理好友请求（登录 demo01 可在好友页接受）；
- demo01 ↔ demo02 有 6 条私聊消息。

## 4. 推荐演示顺序

1. 用 **demo01** 登录 → 「我的地图」只显示 Map A 和 Map C（不会看到历史测试地图）。
2. 打开 Map A：展示地点、地点评论、成员角色（demo07 为 VIEWER）。
3. 打开「聊天」：展示 12 条群聊天记录。
4. 打开「活动日程」：展示未来/进行中/已结束三个活动；进行中活动可去现场/签到。
5. 打开「好友」：展示 demo02/demo03 好友、demo04 待处理请求；打开 demo02 私聊看 6 条消息。
6. 用 **demo08** 登录展示 Map B 与社团群聊；用 **demo15** 登录展示 Map C 及 PUBLIC 地图。
7. 现场 AI：在 Map A 群聊输入以 `@AI` 开头的问题；日程忙闲和地图地点已准备好。注意 `@AI` 会真实调用模型/高德，造数脚本不会自动触发。

## 4.1 忙闲共享授权

- 入口：`活动日程 → 我的日程 → 忙闲共享（供群规划使用）`。
- 每个用户只能授权**自己的**忙闲；群主/管理员不能代替成员授权。
- 当前已预置：demo01/demo02 对 Map A 授权，demo08/demo09 对 Map B 授权；Map C 尚未授权。
- 演示授权前后差异：用 `demo03` 打开 Map A 的开关，demo01 的群体忙闲会从 `UNKNOWN` 变为 `KNOWN`；关闭后恢复 `UNKNOWN`。其他成员的日程标题始终不可见。

## 5. 数据说明

- 群消息通过 WebSocket `CHAT_SEND` 写入；为可重复执行，脚本按内容去重。
- 脚本不会发送 `@AI`，不会调用 LLM/高德，不会创建活动以外的业务副作用。
- 帖子/点赞/评论数据已写入；当前前端以地图、地点评论、群聊和活动为主，帖子可通过
  `GET /api/places/{placeId}/feed` / `GET /api/places/{placeId}/posts` 或 Swagger 查看。
- 现有历史用户/地图/动态仍保留；demo 账号的「我的地图」只显示其成员地图。
- 重复执行脚本应显示 `created=0`，不会重复创建群、地点、消息、活动或帖子。
