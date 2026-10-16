# AI 助手：代码审计与测试基线（阶段 A）

> 项目：`D:\code\gdufs-campus`（多模块：`campus-framework` + `campus-server` + `campus-web`）
> 版本：阶段 A 审计 · 2026-10-15
> 性质：**只读审计，本阶段未修改任何业务代码**，未执行 Git 提交
> 方法：`codegraph_explore` 定位调用链 → 只读核对关键源码（返回 verbatim 源码）→ 实跑测试与构建；密钥仅核对加载与忽略方式，**未读取、未输出明文**（见 §5）。

---

## 0. 基线快照（冻结）

| 项 | 实测 |
|---|---|
| Git | `main` @ `d338605`；工作区**不干净**：后端单模块→`campus-framework`/`campus-server` 拆分已暂存未提交（大量 `R` 重命名 + 少量 `M`），`campus-web` 已跟踪 19 个文件且存在修改、另有未跟踪文件 |
| 前端构建 | `npm run build` → exit 0，`✓ built in 13.28s`（仅 chunk 体积提示，非错误） |
| 后端测试 | `mvn -pl campus-server -am test` → **BUILD SUCCESS**，`Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`，总耗时 4m34s（Testcontainers 启动占大头） |
| 测试形态 | Testcontainers **真实 MySQL 8.0 + Redis 7**（`AgentChatMockedAcceptanceTest.java:55-72`）；`LlmClient` 用 `@MockitoBean` 全模拟，不依赖 DashScope/高德——与计划"G-C 需真实数据库"的基建要求一致 ✓ |

## 1. 四条调用链核实结果

### 1.1 对话链路（Agent 主循环）

`AssistantController.send` (`campus-server/.../ai/controller/AssistantController.java:117`)
→ `AgentService.run` (`.../ai/service/AgentService.java:83`)
→ `AgentToolkit.execute` (`.../ai/tool/AgentToolkit.java:111`) / `LlmClient.chat` (`.../ai/llm/LlmClient.java:45`)
→ `SharedMapService.listMyMaps/listMapPlaces`、`PlaceMapper.searchPlaces`、`TravelInfoProvider.searchRoute/searchPoi`

| 核实点 | 结论 | 证据 |
|---|---|---|
| 轮次上限 14、每轮工具 ≤5、历史 12 条 | **已证实** | `AgentService.java:39-41`；超限截断并回填系统提示（`:172-175`） |
| SSE 事件 status/delta/plan/done/error | **已证实** | `AgentChatListener` + `sseListener`（`AssistantController.java:197-247`） |
| 客户端断开取消 | **已证实** | `emitter.onCompletion/onTimeout/onError → cancelled`（`:124-126`）；循环每轮、每个工具、LLM 流读帧均检查 `isCancelled()`（`AgentService.java:93,161`、`LlmClient.java:83-85`） |
| 工具参数 JSON 解析失败/未知工具 | **已证实**（有兜底） | `AgentToolkit.execute` 整体 try/catch → `errorJson`（`:146-151`）；未知工具名 `:144` |
| 超限工具调用被忽略 | **已证实** | `executeToolRound` 只执行前 5 个并提示模型（`AgentService.java:144,172-175`） |
| LLM 片段累积 | **已证实，有缺陷** | `LlmClient.java:110-126` 按 index 用 `TreeMap` 累积；**无 id/name 的片段被静默丢弃**（`:147`），极端情况会丢工具调用——D 阶段列入 AI-02/03 |
| submit_plan 只出草稿不写活动库 | **已证实** | `AgentService.handleSubmitPlan:179-194` → `PlanValidator` + `updatePlanResult(status=DRAFT)`；写活动只在 `/plan/save` |
| LLM 无重试；429/超时统一报"暂时不可用/失败或超时" | **已证实**（行为简单无界等待不存在） | `LlmClient.java:73-78,136-142`；请求超时 60s、连接 5s（`:38,165`） |
| 限流 | **已证实** | `ai:chat` 10 次/分钟（`AssistantController.java:119`），Redis Lua INCR+PEXPIRE（`RateLimiter.java:36-48`） |

### 1.2 保存链路（事务 + 幂等 + 5m 查重）

`AssistantController.savePlan:149` → `PlanSaveService.save` (`.../ai/service/PlanSaveService.java:47-98`)
→ `PlanValidator.parseAndValidate` → 库外坐标 `PlaceService.createPlace`（5m 查重）→ `ActivityService.create` → `ActivityStopMapper.insert` → `markSaved`

| 核实点 | 结论 | 证据 |
|---|---|---|
| 行锁串行化 | **已证实** | `conversationMapper.selectByIdForUpdate` XML 实含 `FOR UPDATE`（`mapper/AiConversationMapper.xml`） |
| 归属校验 | **已证实** | `PlanSaveService.java:50-53`（`conversationId+userId` 双条件，失败统一"会话不存在"不暴露他人资源） |
| 幂等（已保存返回同一活动） | **已证实** | `:55-59`（`status=SAVED && savedActivityId!=null` 直接返回） |
| 保存时重校验（防地点隐藏/时间过期） | **已证实** | `:64` `parseAndValidate(conversation.getPlanJson(), userId)` |
| 冲突检测 | **已证实** | `markSaved` XML 条件 `WHERE id=? AND status != 'SAVED'`，rows==0 → 抛"保存冲突请重试"，事务回滚刚建活动（`:92-96`） |
| 事务边界 | **已证实** | `save` 整体 `@Transactional`；内部全部为 DB 操作（LLM/高德调用都在 AgentService SSE 线程、事务之外） |
| `new draft plan` 重置幂等标记 | **已证实** | `updatePlanResult` 每次 `saved_activity_id = NULL`（XML），新版本草稿可再次保存 |
| 5m 查重 = 包围盒粗筛 + Haversine 精算 | **已证实** | `PlaceServiceImpl.findWithinRadius:205-219` + `GeoCandidateQuery:29-61`；`MERGE_RADIUS_METERS=5.0`（`PlaceServiceImpl.java:37`） |
| **并发防重（先查后插）** | **存在问题（P0，R1）** | `createPlace:52-81` 查候选后直接 `placeMapper.insert`；`place` 表仅普通索引 `KEY idx_lat_lng`，**无坐标唯一键、无格网锁**（`schema.sql:52-67`）。两笔同坐标并发插入都会成功 → 重复 place。`ensurePlace` 的"候选为空强制新建"重试路径（`PlanSaveService.java:115-120`）在并发下同样可能建出重复点。 → 阶段 C 首要落点 |
| stops 校验与整体替换 | **已证实** | `ActivityServiceImpl.saveStops:380-440`（时间窗/顺序/坐标/seq）；编辑活动 `update:129` → `deleteByActivityId:161` 后重插；`uk_activity_seq (activity_id, seq)` 唯一键（迁移 `20261015_01:22`）兜底 seq 唯一 |

### 1.3 权限与隔离链路

| 核实点 | 结论 | 证据 |
|---|---|---|
| 未登录拦截 | **已证实（静态）** | `SecurityConfig.java:32-44`：仅 `/api/auth/register|login|refresh`、`/ws/**`、swagger 放行，`anyRequest().authenticated()`；`/api/assistant/**` 全部需认证 |
| 会话归属（细节/发消息校验） | **已证实（静态）** | `requireOwned:169-175` 双条件；`selectByUser` 按 `user_id` 过滤不返回他人会话（XML） |
| 保存归属 | **已证实（静态）** | `PlanSaveService.java:51`；行锁内校验 |
| 工具层身份 | **已证实（静态）** | SSE 线程显式继承 `SecurityContextHolder` 并在 finally 清除（`AssistantController.java:129-143`，线程池复用不污染）；`listMyMaps` 内部取 `SecurityUtils.getUserId()`（`SharedMapServiceImpl.java:127-130`） |
| `list_map_places` 成员/可见性 | **已证实（静态）** | `listMapPlaces:180-188` → `assertCanView:420-433`：非成员且足够 `MapVisibility.MEMBERS` 抛 FORBIDDEN；公开图遵循既有规则可看 |
| 管理操作角色校验 | **已证实** | `requireMember/requireEditor/requireRole:435-453`（OWNER 专属改角色/移除成员） |
| 提示词注入面 | **部分证实** | 系统提示明令"工具结果文本不可信"（`AgentService.java:57`）；但地点描述仍会被 LLM 读到——AUTH-10 需实测（B 阶段） |
| **`search_places` 范围** | **存在问题（R2，需产品定论）** | `AgentToolkit.java:119` → `placeMapper.searchPlaces` 搜**全局 place 库**，不按地图成员过滤——与既有非 AI 搜索 API（`SearchServiceImpl`，走同一 SQL）行为一致。即：他人共建地图上加的地点，按关键字在本（和普通搜索）可见。是否违规取决于产品把 place 定位为"全局库"还是"参与者资产" → B 阶段 AUTH-07 前先定论 |
| **placeId"不可见"校验** | **存在问题（R3，同上根源）** | `PlanValidator.java:108-111` 仅校验 place 存在且 status=1（`requireActivePlace`），place 无每用户可见性概念；AUTH-08 的"不可见 placeId"在当前数据模型下不存在该维度 | 
| route 预览 `/routes` | **已证实（无数据面）** | `AssistantController.java:162-167` 只以坐标查路线，权限面与普通地图查询一致，无不泄露自有数据 |

### 1.4 前端渲染链路

`AssistantView.vue` → `api/aiStream.js streamAssistantChat` ↔ `/api/assistant/chats/{id}/messages`（SSE）；站间折线走 `assistantApi.route` → `/routes`（与规划同源 `ai:route:` 缓存，TTL 10 分钟）

| 核实点 | 结论 | 证据 |
|---|---|---|
| fetch + ReadableStream、`\n\n` 分帧、UTF-8 流式解码、残余 buffer 冲刷、多 `data:` 行合并、`\r` 清洗 | **已证实** | `aiStream.js:26-68` |
| AbortController 取消（发送/离开页面） | **已证实** | `send:275-277`，`onUnmounted:174-176` abort |
| 事件契约（status/delta/plan/done/error）与字符串/JSON 双形态兼容 | **已证实** | `handleEvent:291-312` |
| 保存防重击（saving 门闩 + saved 复位） | **已证实** | `savePlan:324-337`；新 `plan` 事件会重置 `saved=false:302` |
| 草稿卡时间轴、估算标注、marker/折线 | **已证实** | `enrichedStops:130-152`（"约 X 分钟（估算）"）、`renderPlanOnMap/loadRouteSegments:198-232` |
| **CRLF 分帧隐患** | **问题（小，R4）** | 仅按 `indexOf('\n\n')` 分帧；若上游改用 `\r\n\r\n` 则帧不切分。Spring `SseEmitter` 当前默认 `\n\n`，**不触发**，属健壮性项（D 阶段 AI-07 覆盖） |
| 会话恢复 | **已证实** | `restoreLatestChat:234-252` 取最近会话 + detail 回放消息与草稿 |
| 导航入口 4 处 | **已证实** | `router/index.js:17`（auth:true）+ `MapListView.vue:6`、`FriendsView.vue:6`、`ActivitiesView.vue:6`、`MapWorkspaceView.vue:70` |

## 2. 报告声明 vs 源码核实对照

| 报告（2026-10-15）声明 | 本阶段核实 |
|---|---|
| `activity_stop` + 多站创建/编辑/详情 + 单站兼容 + 存量库独立迁移 SQL | **已证实**（`db/migration/20261015_01`、`schema.sql:143`、`saveStops`、`update→deleteByActivityId`） |
| `LlmClient/AgentToolkit/AgentService/PlanValidator/PlanSaveService/AssistantController` 齐备 | **已证实** |
| 6 个工具；submit_plan 是草稿指令非通用写库工具 | **已证实**（5 只读工具 + `AgentService` 拦截 `submit_plan`，库写入仅经 `/plan/save`） |
| 事件 status/delta/plan/done/error；14 轮、每轮 ≤5 | **已证实** |
| 草稿生命周期 CHATTING→DRAFT→SAVED、`plan_version`、`saved_activity_id`、事务 + `FOR UPDATE` | **已证实（代码层面）**；并发正确性需 DB-02 实测（C 阶段） |
| 前端对话流/共享地图选择/时间轴/marker/折线/「保存为活动」/四处入口 | **已证实** |
| `mvn test` 15 例全绿 | **已证实（本次实跑 15/15）** |
| 真实 Qwen + 高德 + 浏览器端到端联调成功 | **未证实（本阶段未执行真实外呼与浏览器 e2e）**，留 G 阶段 smoke 复核 |
| 中文注释声称坐标基准 GCJ-02 | **存在矛盾（R7）**：`schema.sql:57-58` place 表注释写"WGS84"，`ActivityStop.java:8`/迁移注释写"GCJ-02 同基准"——需确认真实基准后统一注释 |

## 3. 风险与问题清单（供 B/C/D 阶段精确定位）

| 编号 | 级别 | 问题 | 位置 | 归属 |
|---|---|---|---|---|
| R1 | **P0** | ~~先查后插无并发防重~~ **✅ 已修复（阶段 C）**：`PlaceGridLock` 格网命名锁（MySQL `GET_LOCK` 九宫格，单元 0.0001° ⇒ 相距 ≤5m 必共享邻格）+ 锁内复检 + `afterCompletion` 提交后放锁；`createPlace` 事务显式 `READ_COMMITTED`（关键：默认 REPEATABLE READ 会在加锁前的 `validateCategory` 读上钉死快照，复检看不到前一事务刚提交的行，DB-05 实测复现出 5/10 重复）。测试 `db05`：10 并发同坐标 → 恰 1 条 place | `place/lock/PlaceGridLock.java`、`PlaceServiceImpl.createPlace`、`PlaceMapper.getLock/releaseLock` | **已结案** |
| R2 | P0·设计 | ~~需先定论 place 隐私语义~~ **✅ 已定论（阶段 B）**：保留「全局地点库」语义——agent `search_places` 与 `/api/search` 同规范同一 SQL，AUTH-07 用例已自动化证明两路命中同一 placeId 且无额外字段泄露 | `AgentToolkit.java:119`、`SearchServiceImpl.java:32` | **已结案** |
| R3 | P0·设计 | ~~无"可见性"维度~~ **✅ 已定论（阶段 B）**：placeId 仅有存在性+status 维度（评审确认），"不可见 placeId"不作为检查项；AUTH-08 自动化：不存在 placeId 拒绝且不落库 | `PlanValidator.java:108-111` | **已结案** |
| R4 | P2 | ~~SSE 前端仅按 `\n\n` 分帧，CRLF 兼容缺失~~ **✅ 已修复（阶段 D）**：aiStream.js 改为跨 chunk 的 `/\r?\n\r?\n/g` 增量扫描（`consumed` 游标），`\n\n` 行为不变、`strip \r` 保留 | `campus-web/src/api/aiStream.js` | **已结案** |
| R5 | P1 | SSE 总超时 150s，而 14 轮 ×（LLM 60s + 工具 6s）理论上限远超之：长会话会被 emitter 超时切断，取消后以 error 收场——行为可控但需实测确认无"半写库" | `AssistantController.java:62`、`LlmClient.java:165` | D（AI-05/06） |
| R6 | P1 | `submit_plan` 的 `nextVersion` 基于会话快照（`conversation.getPlanVersion()`），长对话内不回读 DB，并发两轮 submit 理论可能版本重复/跳号 | `AgentService.java:183` | C（DB-04 相关） |
| R7 | P2 | 坐标基准注释矛盾（place 表 WGS84 vs activity_stop/AI 链 GCJ-02） | `schema.sql:57-58`、`20261015_01:6` | B 前澄清 |
| R8 | P2 | ~~来源待确认~~ **✅ 已定位（阶段 B）**：测试日志里的 `Unexpected error occurred in scheduled task`（TaskUtils LoggingErrorHandler）来自 `@EnableScheduling` 的活动提醒调度器在无耗数据的测试上下文里周期性报错，属既有测试噪音、不影响结果；Surefire "kill self fork JVM" 为容器清理后 fork JVM 存活提示。均为噪音、测试全绿 | `ActivityReminderScheduler` 测试上下文 | 已知噪音 |
| R9 | P1 | `plan/save`、`plan/validate`、`/routes` 未设限流 —— **✅ 已评估（阶段 B）：不加**。save 已有行锁+幂等+冲突回滚，加 10次/分 反而会干扰 C 阶段 DB-02 十并发用例；validate/routes 无数据面 | `AssistantController.java:149-167` | 已评估结案 |

## 4. 已核实的数据库事实

- `ai_conversation`（迁移 `20261015_02:9-20`）：`user_id`、`status`（CHATTING/DRAFT/SAVED）、`plan_json MEDIUMTEXT`、`plan_version`、`saved_activity_id`、`KEY idx_user_updated`。**无 DB 级唯一约束**（幂等依赖行锁+应用层状态）。
- `ai_message`：`KEY idx_conv (conversation_id, id)`，`selectRecent ORDER BY id DESC LIMIT` 与索引匹配。
- `activity_stop`：`UNIQUE KEY uk_activity_seq (activity_id, seq)` — seq 唯一由 DB 兜底。
- `place`：仅 `KEY idx_lat_lng`（非唯一）— R1 的直接证据。
- `schema.sql` 与两个迁移脚本同步完整（`schema.sql:143,278,291` 均含新表）；Testcontainers 用的是 `db/test-init.sql`（结构一致）。

## 5. 配置与密钥（核对方式，不含任何密钥值）

- 装载链：`application.yml:8-9` → `spring.config.import: optional:classpath:application-secrets.properties`，注释指明 DASHSCOPE_API_KEY/AMAP_WEB_KEY 在此文件、环境变量可覆盖；dev profile（`application.yml:4-5`）生效。
- 忽略：`git check-ignore -v` 命中 `.gitignore:16`（application-secrets.properties）。
- 泄露面检查：`LlmClient` 打日志只输出 key **缺失事件与配置名**（`LlmClient.java:50`）；高德 infocode 10001/10003/10009 分级报错只含 infocode/info，不含 key（`AmapTravelProvider.java:116-120`）；LLM 失败日志对响应体截断 300 字符；`Authorization` 头不落日志。**本审计未输出任何密钥明文。**

## 6. 基线命令（复测用，PowerShell）

```powershell
cd D:\code\gdufs-campus
mvn -pl campus-server -am test          # 15/15 全绿，约 4.5 分钟（Testcontainers）
npm --prefix campus-web run build       # exit 0
git status --short                      # 基线：模块拆分暂存未提交 + campus-web 增量
```

## 7. 下一阶段精确文件落点

**B（权限隔离）请新增/触及：**
- `campus-server/src/test/java/com/campus/`（新增 `AssistantAuthAcceptanceTest` 建议名：AUTH-01~10 MockMvc 双账号用例）
- `campus-server/.../ai/controller/AssistantController.java`（统一错误码、`plan/save|validate|routes` 限流评估）
- `campus-server/.../ai/tool/AgentToolkit.java`（工具参数类型/坐标范围加固——已有基础校验，需加测试）
- `campus-server/.../map/service/impl/SharedMapServiceImpl.java`（成员/可见性已有实现，只补测试）
- 前置决策：R2/R3 的 place 可见性语义 → 先问产品再动代码

**C（一致性/并发）：**
- `campus-server/.../place/service/impl/PlaceServiceImpl.java` + `place/mapper/PlaceMapper.xml` + `db/schema.sql`（R1：格网锁/唯一约束选型，需迁移脚本三同步：schema.sql、test-init.sql、新迁移文件）
- `campus-server/.../ai/service/PlanSaveService.java`（R6：保存前回读 plan_version 对比，拒绝旧版本静默保存）
- 新增 `PlanSaveConcurrencyAcceptanceTest`（建议名：DB-01~08，含 10 并发保存与回滚注入）

**D（Agent/SSE/外部异常）：**
- `campus-server/.../ai/llm/LlmClient.java`（分片容错、有界重试评估）
- `campus-server/.../ai/service/AgentService.java`（AI-02~05 分支测试）
- `campus-web/src/api/aiStream.js`（R4：CRLF 兼容）与 `AssistantView.vue`（边界提示）
- 扩展 `AgentChatMockedAcceptanceTest`（AI-01~10 mock 场景）

## 8. 验收门槛 G-A 自评

| 门槛 | 状态 |
|---|---|
| 定位 4 条实际调用链 | ✅ §1.1–1.4，均含文件:行号 |
| 后端原有测试全绿 | ✅ 15/15 |
| 前端构建成功 | ✅ exit 0 |
| 未将密钥或私有用户数据写进日志/文档 | ✅ §5 |
| codegraph MCP 可用性 | ✅ 全程使用，返回 verbatim 源码；个别文件有 pending-sync 提示（daemon 增量同步中，未影响审计） |

**结论：G-A 通过。报告描述与源码一致（除 R7 注释矛盾）；两处高风险是 R1（并发造点）与 R2/R3（place 可见性语义需先定论）。**

---

## 9. 阶段 B（AUTH-01～10）执行结果 · 2026-10-15

**G-B 通过。** 语义基准（评审定论）：place 保持「全局地点库」，`search_places` 与 `/api/search` 同规范；placeId 仅存在性维度；会话越权统一 404「会话不存在」；`referenceMapId` 走共享地图成员可见性。

### 交付物

| 项 | 内容 |
|---|---|
| 新增测试 | `campus-server/src/test/java/com/campus/AssistantAuthAcceptanceTest.java` — 10 例（AUTH-01～10 一一对应）：未认证 401 矩阵、跨用户会话读隐藏、跨用户发消息不写库、跨用户保存不建活动、成员工具可读私密图、非成员工具报 error 无泄露、`search_places` 与普通搜索同一 placeId 且无 description 字段、非法 placeId 不落库且 save 报「暂无可保存的规划草稿」、地图失权后 `/plan/save`·`/plan/validate` 双 403 且活动数为 0、地点描述里的提示词注入不扩大工具权限（第二轮读他人私密图得到 error、内容不外泄） |
| 代码加固 | `AgentToolkit` 数字参数收敛（`asNumber`）：JSON number 与数字字符串都接受，其余报「参数 X 必须为数字」（400，经 error JSON 回填模型自查）；替换原先 `(Number)` 强转导致字符串数字落入泛化「工具执行异常」的行为 |
| 限流决策 | `plan/save` **不加**限流（R9 结案）：已有行锁+幂等+冲突回滚；且会干扰 C 阶段 DB-02 十并发用例 |

### 测试基建要点（后续阶段沿用）

- 多数派写法：`@SpringBootTest(properties="spring.profiles.active=test")` + `@Testcontainers(disabledWithoutDocker=true)`；每方法独立用户名（authXX_a/b）避免同容器串数据。
- mock LLM 采用链式 DSL（`stubRounds(dsl -> dsl.tool(...).reply(...))`）；工具结果 JSON 用 `ArgumentCaptor` 捕获最后一次 `chat()` 的消息历史抽取 `role=tool` 内容断言。
- **`X-Forwarded-For` 逐次伪造来源 IP**：`auth:login:{ip}` 限流 10 次/分钟按 IP 统计，MockMvc 同 IP 连续 login 会 429；按次递增伪造 IP 隔离配额（resolveClientIp 优先读该头，产品行为未改动）。

### 基线更新

- `mvn -pl campus-server -am test`：**25/25 全绿**（15 既有 + 10 AUTH）。
- 下一阶段落点保持不变（§7 C/D），另加一条：本测试类的 RoundDsl 脚手架可直接复用生成 C 阶段并发用例与 D 阶段 AI-02～05 分支用例。

---

## 10. 阶段 C（DB-01～08）执行结果 · 2026-10-15

**G-C 通过。** 基线 `mvn -pl campus-server -am test`：**33/33 全绿**（25 + 8）。

### 交付物

| 项 | 内容 |
|---|---|
| 并发防重 | `place/lock/PlaceGridLock`（新增）：MySQL `GET_LOCK` 命名锁九宫格 + 锁内复检 + 事务 `afterCompletion` 统一放锁；`PlaceMapper` 增 `getLock/releaseLock` 注解方法；`createPlace` 包裹改写，`ensurePlace` 自动受益。**无新表/新列/迁移**（不采用坐标取整唯一键，5m Haversine 精算保留） |
| 隔离级别 | `createPlace` 显式 `READ_COMMITTED`——修复复检快照固定陷阱（见踩坑①） |
| 版本竞赛防护 | `PlanSaveRequest{expectedPlanVersion}`（可选 body，旧前端不传兼容）→ `PlanSaveService` 行锁内比对，不一致 409「草稿已更新，请刷新后重新保存」；`ResultCode` 增 `CONFLICT(409)`、`GlobalExceptionHandler` 增 `case 409` |
| 并发测试 | `PlanSaveConcurrencyAcceptanceTest`（新增 8 例）：DB-01 顺序双存同活动 / DB-02 十线程并发保存恰一活动一组 stops / DB-03 stops 注入异常全量回滚（活动 0、会话仍 DRAFT、place 零残留）/ DB-04 版本竞赛三不变式（至多一活动、冲突必为旧版拒绝、活动标题必属 v2）/ DB-05 十并发同坐标恰建一地 / DB-06 3→2 站替换且 seq 连续 / DB-07 三类非法站点全拒绝且无脏数据 / DB-08 单站老流程兼容 |

### 踩坑记录（过程真实发生并修复）

1. **RR 快照固定**：默认 REPEATABLE READ 下，每个并发线程的 `validateCategory` 读在加锁前就钉住一致性视图；拿到格网锁后的"锁内复检"看到的仍是旧快照 → 复检判空 → 重复插入（10 并发出 5 条）。`READ_COMMITTED` 后复检每语句读最新已提交数据才闭合。**教训：锁到事务提交才释放只是必要条件，配对的读还必须能看见已提交数据。**
2. **`@MockitoBean` 替换 MyBatis Mapper 的副作用**：为 DB-03 注入把 `ActivityStopMapper` mock 掉后，全类 `selectByActivityId` 都返回 mock 默认空表（stops 全空、db08 假绿）。改用 `@MockitoSpyBean` 包装真实 Mapper，仅对 `insert` 打 `doThrow`。**教训：对被业务真实读写的基础设施 Bean 用 Partial Mock，不要整体替换。**
3. **越权断言语义**：db04 初版断言"竞争必一成一拒"过强——fresh 先赢后，过期线程按设计幂等返回同一活动。改为三不变式（至多一活动 / 冲突必为旧版 / 活动必属 v2 草稿）。

### 服务层并发测试基建（D 阶段沿用）

- 并发走**服务层多线程**而非 MockMvc（非线程安全）；每线程手工注入 `LoginUser(id)` SecurityContext（`asUser` helper，`SecurityUtils` 全链路可用）。
- `spring.datasource.hikari.maximum-pool-size=12`（DynamicPropertySource）保证 10 线程同时持连接等行锁。
- 测试间共享同一容器：**各用例地点坐标必须互距 >5m**（5m 查重会跨用例合并）；用户名 per 方法唯一；`X-Forwarded-For` 伪造隔离 `auth:login:{ip}`。

---

## 11. 阶段 D（AI-01～10 + D3 分级）执行结果 · 2026-10-15

**G-D 通过。** 基线 `mvn -pl campus-server -am test`：**51/51 全绿**（33 + 18）；`npm run build` exit 0（aiStream.js 改动）。

### 交付物

| 项 | 内容 |
|---|---|
| LlmClient 容错加固 ×2 | ① 缺 id 的 tool_calls 分片改合成 `piece_{index}` 占位（真实 id 迟到可覆盖），不再整段静默丢弃；② data 行 JSON 解析失败→跳过该行（原实现整个调用失败「模型调用失败或超时」） |
| aiStream.js CRLF 兼容 | 分帧改 `/​\r?\n\r?\n/g` 跨 chunk 增量扫描（`consumed` 游标），残余 buffer 冲刷保持（R4 结案） |
| `LlmClientStreamContractTest`（6 例，纯 JUnit + JDK HttpServer 桩） | 缺 id 分片合成不丢 / 延时真实 id 覆盖合成 id / 注释行与坏 data 行跳过、内容照常装配 / usage 取最后一次 / HTTP 500 报状态码 / 逐行取消检查即刻抛「已取消」——**载荷全部 ObjectMapper 构造，杜绝手拼 JSON 配对错误** |
| `AgentAssistantErrorFlowAcceptanceTest`（9 例） | AI-02 非法工具 JSON→error JSON 软失败循环继续 / AI-03 未知工具→「未知工具」 / AI-04 路线提供方抛错→error 且会话收尾正常 / AI-05（计划 AI-08 对应）POI 空列表→工具结果 `[]` / AI-06 估算路线→`estimated:true, provider:estimate` 注入工具结果 / AI-05 14 轮触顶→`verify(times(14))` 后会话无 DRAFT / AI-06 预取消 listener→LLM 零调用零写入 / AI-07 服务端线协议（`event:` 行 + `\n\n` 分帧）/ AI-10 submit_plan 传入过去时间→`accepted:false`、save 400 |
| `MissingConfigGradingAcceptanceTest`（3 例，真实降级路径） | `campus.llm.api-key=""`（DynamicPropertySource 最高优先级，压过本地 secrets）→ send 流 SSE error 含「AI 助手未配置模型 API Key」、无 assistant 消息；`campus.amap.web-key=""`→ route 工具真实本地估算（`estimated=true` 无外呼）、POI 工具明确「POI 检索服务未配置」 |
| AI-01 备注 | 正常多工具→唯一草稿已由 `AgentChatMockedAcceptanceTest` 覆盖，本阶段不重复建设 |

### 踩坑记录

1. **MockMvc 原始响应体按 ISO-8859-1 解码**：断言 SSE 中文 error 文案必须 `getContentAsString(StandardCharsets.UTF_8)`，否则"假失败"（文本完好只是读法不对）。
2. **HttpHandler lambda 与受检异常**：`data(...)` 抛 Exception 的表达式不能塞进 `HttpHandler` lambda 实参求值位置，先算局部变量再传。
3. **第三方流式不保证良构**：坏 JSON data 行现被跳过（此前一行坏流=整次对话失败），已由契约测试锁定。

### 线索遗留

- 真实 DashScope/高德 smoke、浏览器 E2E、固定场景成本基线 → G 阶段（E 阶段的观测也在 G 前完成）。

---

## 12. 阶段 E（观测基建 + 固定场景基线 + 优化对照）执行结果 · 2026-10-15

**G-E 通过（部分指标待 G 阶段用多轮采样校准）。** 测试基线 `mvn test` **53/53**（51 + 2 观测用例）；真实对照与结论见 **`docs/AI助手_性能与成本报告.md`** §2–§3（token 合计 −19%，s02 14→7 轮、s10 触顶→8 轮出稿；repeatHits 本次零命中、落记录）。

### 交付物

| 项 | 内容 |
|---|---|
| 观测基建 | `AgentService.RunTracker` → logger `AI_RUN` 单行 JSON（finally 兜底五类退出路径）；`RouteCache` hits/misses 计数器差分；观测行仅含 ID/计数/耗时/结局，无用户正文 |
| 优化开关 | `campus.ai.efficiency.enabled`（默认 false=旧行为；true=同参缓存+两条效率提示），性能对照专用，可随时回退 |
| 优化内容 | ① run 内同参工具结果复用；② 系统提示词追加效率指引 7/8 条（不改既有 6 条硬规则） |
| 测试 | `AgentRunObservationTest` 2 例（AI_RUN 字段校验 + 同参只执行一次 + 徽红线"观测行不含正文"） |
| 真实基线 | 10 固定场景 ×（优化前/后）+ 预热，脚本 `tools/phase-e-baseline.mjs`，原始数据在 `.ai-run/` |

### 真实联调发现的生产缺陷（本轮重要产出）

**SSE 完成后的 Tomcat async dispatch 重走安全链** → `AuthorizationFilter` 在已被 SSE 线程清理的上下文上拒绝（响应已 committed），浏览器表现为日志噪音/偶发流中断。修复：`SecurityConfig` 加 `.shouldFilterAllDispatcherTypes(false)`（鉴权仅作用于 REQUEST 分派）。MockMvc验收此前无法触达该路径——**只有真实异步链路能暴露**。

### 过程坑（记录）

- Spring 首个测试 context bootstrap 会**重置 logback 挂载的运行期 appender**：抓取 AI_RUN 必须在 `@BeforeEach`（context 就绪后）再挂载。
- 本机 Git Bash 的 `grep` 实为 ugrep，部分 `-c`/组合 pattern 表现异常——日志统计用 `awk` 更可靠。
- `mvn spring-boot:run` 的输出重定向目标目录必须先存在，否则整个命令连服务都没起来（进程存活判定不能仅靠 health 探测，需核对启动 banner 中的 PID 与时间戳）。
