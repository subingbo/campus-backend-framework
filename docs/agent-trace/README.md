# Agent Trace P0 — 架构与开发说明

> 适用范围：当前 `campus-server` 的 AI 规划助手（个人会话 + 群聊 @AI）。
> P0 只做**可观测与 JSONL 导出**；P1 可视化、P2 回放不在本模块。

> 当前实施状态、已完成内容与剩余待办见 [实施完成情况与待办.md](实施完成情况与待办.md)。

## 1. 目标

不要求 LLM 自己写日志。由 Spring Boot 在真实执行点自动记录：

- 一次规划收到了什么模型/参数、调用了哪些工具、真实结果如何；
- 地图/地点/路线/忙闲的命中数量与耗时；
- 草稿版本、校验失败规则、确认保存与幂等结果；
- 一次请求的耗时、Token、失败环节与最终状态。

数据库（MySQL）是 P0 的权威事件源；JSONL 是按需从数据库生成的导出产物。

## 2. 组件与调用链

```text
AssistantController.send / GroupAgentService
  -> AgentService.run
     -> TraceRun（session/turn/step + stream）
     -> LlmClient.chat（llm/request/response/error）
     -> AgentToolkit.execute（tool/call/result/error + map/route/schedule 摘要）
     -> PlanValidator（plan/validate，带 ruleId）
     -> PlanSaveService（plan/confirm，afterCommit 才记 activity/created）
```

```text
TraceRun -> AgentTraceService.record（入队前脱敏）
         -> AgentTraceWriter（有界队列，单消费者）
         -> JdbcTraceEventStore（REQUIRES_NEW：行锁分配 seq + 写事件 + 更新计数）
         -> agent_trace_session / agent_trace_event
         -> AgentTraceController / AgentTraceExportService（查询 + JSONL）
```

核心代码位置：

| 文件 | 作用 |
|---|---|
| `campus-server/src/main/java/com/campus/ai/trace/config/AgentTraceProperties.java` | 配置开关 |
| `.../trace/domain/AgentEventType.java` | 事件类型常量 |
| `.../trace/dto/TraceEventRecord.java` | 不可变事件记录 |
| `.../trace/security/TraceDataRedactor.java` | 入队前脱敏与工具摘要 |
| `.../trace/store/AgentTraceWriter.java` | 有界队列/单消费者/flush |
| `.../trace/store/JdbcTraceEventStore.java` | seq 行锁与事务落库 |
| `.../trace/service/AgentTraceServiceImpl.java` | 会话解析、run/save 句柄、查询 |
| `.../trace/adapter/TraceRun.java` | 对话 run 埋点 |
| `.../trace/adapter/TraceSaveRun.java` | 保存/修订埋点 |
| `.../trace/service/AgentTraceExportService.java` | JSONL keyset 流式导出 |
| `.../trace/service/TraceStatsCalculator.java` | 详情耗时统计（p50/p95 最近秩） |
| `.../trace/service/AgentTraceCleanupJob.java` | 陈旧会话/过期清理 |
| `.../trace/controller/AgentTraceController.java` | 查询/导出 API |

## 3. 会话语义

- `sessionId`：一次规划 episode（同一 `ai_conversation` 从首次运行到成功保存）。多轮修改复用 RUNNING session。
- `traceId`：一次 HTTP/SSE/保存/修订请求。
- `turn`：同一 session 的交互轮次；保存/修订不推进 turn。
- `step`：一次 Agent 循环轮次（round）。
- `seq`：同一 session 内严格递增；由 `agent_trace_session.next_seq` 行锁分配。
- 状态：`RUNNING → COMPLETED`（保存成功）；长期无活动的 RUNNING 由清理任务标 `INTERRUPTED`。
- 保存后继续修订会创建新 session；重复点击保存（幂等）复用已完成 session，并记录 `idempotentReuse=true`。

## 4. 配置

`application.yml`：

```yaml
campus.agent.trace:
  enabled: true
  level: SUMMARY          # OFF / SUMMARY / DEBUG（P0 DEBUG 不额外采集原文）
  max-payload-bytes: 16384
  writer:
    queue-capacity: 2000
    batch-size: 50
    flush-timeout-ms: 3000
  retention:
    detail-days: 30
    stale-running-minutes: 120
    cleanup-cron: "0 30 3 * * *"
  export:
    enabled: true
    page-size: 500
  pricing:
    enabled: true
    currency: CNY
    version: demo-2026-10
    models:
      qwen-plus:
        input-per-million: 0.8
        output-per-million: 2.0
```

- 关闭 `enabled` 或 `level: OFF` 后，所有入口退化为 no-op，业务完全不受影响。
- 测试 profile 在 `src/test/resources/application-test.yml` 中提高队列容量并固定清理 cron。
- `pricing` 只影响读取时成本估算，不落库；`application-dev.yml` 的 qwen-plus 数值是**演示费率**，请按实际账单更新并同步 `version`。没有费率时返回 `UNKNOWN_RATE`/“不可估算”，不会写 0。
- 成本只在“单模型 + 每次 `llm/response` 都有 prompt/completion Token”时计算；旧 Trace 或混合模型保守返回不可估算。

## 5. 数据库迁移与回滚

迁移文件：`campus-server/src/main/resources/db/migration/20261019_01__create_agent_trace.sql`

```bash
docker compose exec -T mysql mysql -ucampus -pcampus123 campus \
  < campus-server/src/main/resources/db/migration/20261019_01__create_agent_trace.sql
```

全新初始化由 `db/schema.sql` 建表；测试由 `src/test/resources/db/test-init.sql` 建表。

回滚（会删除 Trace 数据）：

```sql
DROP TABLE IF EXISTS agent_trace_event;
DROP TABLE IF EXISTS agent_trace_session;
```

**部署顺序**：先执行迁移，再开启 `campus.agent.trace.enabled`。表缺失时 Trace 自动降级，业务继续，但不会产生追踪数据。

## 6. 可靠性

- 业务线程只做脱敏 + 入队；普通事件入队前先计入 `pendingEvents`，避免计数与消费竞态。
- 关键终态在队列满时先做有界等待（默认 500ms），尽量排在已入队事件之后；只有超时才同步 fallback，
  并记录可能造成 `seq` 顺序偏移的告警。
- 单消费者按入队顺序落库；`seq` 由 DB 行锁 + 同一事务分配，提交顺序即可见顺序。
- 单条写入失败只累计 `dropped_count` 并限频告警，不杀死消费者，不影响业务。
- `pendingEvents` 与未落库 drop 计数是单 JVM 内存态；进程重启后队列事件可能丢失，不能仅凭 `status`
  判断跨重启完整性。
- `complete` 由服务端判定：`status == COMPLETED` 且 effective `dropped_count == 0` 且 `pendingEvents == 0`。

## 7. 访问与隐私

- P0 仅**本人**可读：列表/详情/事件/导出均按 `user_id` 过滤，非本人与不存在统一 404。
- 群聊 Trace owner 是发起人；只存脱敏摘要，不存群聊原文/备注/地点名/坐标。
- 不存 JWT/Refresh Token/API Key/Cookie/密码；不存模型推理原文。
- 详细规则见 [privacy.md](privacy.md)。

## 8. 测试

```bash
mvn -pl campus-server -am -DskipTests compile
mvn -pl campus-server -am -Dtest=TraceDataRedactorTest,AgentTraceWriterTest test
mvn -pl campus-server -am -Dtest=AgentTraceFlowAcceptanceTest,AgentTraceConcurrencyTest test
mvn -pl campus-server -am test
npm --prefix campus-web run test:trace
npm --prefix campus-web run build
```

测试使用 Testcontainers（MySQL 8 + Redis 7），需要本机 Docker。

## 9. P1 Trace Center（前端）

本次已实现 Vue 3 追踪页面，入口在顶栏「执行追踪」或路由 `/traces`：

- 会话列表：状态/模型/触发源/耗时/Token/事件与工具数/错误数/完整性，支持状态筛选与翻页；
- 会话详情：端到端摘要卡片、Turn/Step 分组时间线；
- 事件时间线：类型、状态、`seq`、时间、`callId`、工具名、耗时、错误码，展开查看脱敏 `data`；
- 草稿变化：`plan/draft` / `plan/validate`（ruleId/details）/ `plan/revise` / `plan/confirm` / `activity/created` 在时间线中呈现；
- 事件筛选：按类型、`callId` 过滤，支持分页“加载更多”；
- 耗时指标卡（P1 增强）：详情响应 `stats`，按类型/工具给出次数、平均、p50、p95、合计；
- 草稿版本卡（P1 增强）：详情响应 `draftVersions`，相邻版本对比站点数、总行程、路线置信度、地点增删、校验通过/规则与修订变更；
- 并行工具泳道（P1 增强）：时间线/泳道可切换，按轮分组、每个 `callId` 一条泳道，`tool/call→result` 与 `llm/request→response` 配对成横条，错误红色、进行中显示起点标记；纯 CSS 实现，无图表库依赖；
- 自动刷新与尾部补拉（P1 收尾）：列表/详情仅在 `RUNNING` 时每 8 秒轮询；会话进入终态后继续按 `lastSeq`/`pendingEvents` 补拉尾部事件（writer 队列未清空也算未完成），每轮最多 3 页，追不平则短间隔重试，超限明确提示“末尾事件可能尚未同步”；详情与事件的更新时间/错误分别记录；
- 两套游标：历史分页只更新历史进度，`afterSeq` 增量只更新增量游标；历史未加载完时不自动追加，避免事件缺口；切换会话/筛选时旧响应按 epoch+filterSignature 丢弃；
- `step` 筛选、最近错误码、错误/空态区分、可重试；
- 成本估算（P1 收尾）：列表/详情展示按配置费率计算的会话成本；模型/Token 前提不完整或旧 Trace 显示“不可估算”，已知零成本显示 ¥0.00；费率版本随响应返回；
- 导出：直接下载 `session-<id>.jsonl`；
- Token 未返回显示「未返回」，不显示 0；不完整追踪显示降级标记；
- 路由懒加载：`TraceCenterView` 独立 chunk，不进主包。

前端文件：
`campus-web/src/views/TraceCenterView.vue`、`campus-web/src/api/index.js`（`traceApi`）、
`campus-web/src/router/index.js`、`campus-web/src/components/AppShell.vue`。

### 9.1 P2-A 执行链模型

新增纯读执行链组装（无新表/无 API）：

- `campus-server/src/main/java/com/campus/ai/trace/execution/`：
  `TraceExecutionNode`、`TraceExecutionNodeKind`、`TraceLinkConfidence`、
  `TraceExecutionDiagnostics`、`TraceExecutionAssembly`、`TraceExecutionAssembler`；
- 按 `seq` 建 SESSION/RUN/TURN/STEP，按 `callId` 配对工具，按 `traceId+turn+step` FIFO 配对 LLM，
  规划事件按 draftVersion/operation 结构化；
- 缺失开始/结束、seq gap、重复 callId、未知事件、payload 解析失败全部进入诊断，不补造执行事实；
- 节点属性走显式白名单，不复制聊天正文/地点/坐标/日程/密钥；
- 详见 [execution-model.md](execution-model.md)。

## 10. 不在本轮范围

- 工具响应快照、离线 Stub Replay、模型 A/B 对照（P2）。
- OpenTelemetry / Jaeger 接入（P3）。
- 管理员跨用户诊断读取、群成员共享读取（需要单独权限与产品规则）。

## 11. 已知边界

- `LlmClient` 当前没有重试；`llm/retry` 类型保留，P0 不会伪造重试事件。
- 成本是读取时按当前费率估算，不落库；旧 Trace 缺少 response 级 model/token 时显示“不可估算”。
- 自动刷新是 8 秒轮询，不是实时推送；终态尾部补拉有重试上限，超限需手动刷新。
- `pendingEvents` 是当前实例 writer 内存计数；多实例部署下只代表本实例，当前项目按单实例假设。
- `parentEventId` P0 不填（异步 writer 不返回 eventId）。
- 单实例假设用于会话解析/清理；多实例下 seq 仍由 DB 行锁保证唯一。
- 空闲窗口只记录数量/来源/未知成员数，不记录具体个人日程时间；详细方案在 `ai_conversation.plan_json`。
- 导出为已脱敏数据；导出中途失败时客户端可能收到截断文件。
