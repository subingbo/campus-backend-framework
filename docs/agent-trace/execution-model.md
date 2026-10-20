# Agent Trace 执行链模型（P2-A）

> 范围：把已持久化的 `agent_trace_event` 组装成只读执行链。
> 本轮只做模型/组装器与单元测试，不新增表、不加 HTTP API、不做回放 UI、不调用 LLM/工具。

## 1. 原则

- **证据驱动**：每个节点至少由一个真实持久化事件支撑；不创建历史中不存在的阶段。
- **按 seq 排序**：`seq` 是持久化顺序；不按时间戳排序，不假设连续。
- **缺失不补造**：缺开始/结束保留 `hasStart=false` / `hasEnd=false`，不补 SUCCESS、不补时间。
- **未知不丢弃**：未知事件保留为 `UNKNOWN` 节点并计入诊断。
- **只读**：组装器是纯函数，不访问数据库、不调用模型/高德/业务写接口。
- **脱敏**：节点 `attributes` 使用显式白名单；不复制完整 payload。

## 2. 节点类型

`TraceExecutionNodeKind`：

```text
SESSION, RUN, STREAM, TURN, STEP,
LLM_CALL, TOOL_CALL,
MAP_SEARCH, PLACE_RETRIEVAL, ROUTE_ESTIMATE, SCHEDULE_WINDOW, PERMISSION_FILTER,
PLAN_VALIDATE, PLAN_DRAFT, PLAN_REVISE, PLAN_CONFIRM, ACTIVITY_CREATED,
PLAN_SAVE_ERROR, INTEGRITY, UNKNOWN
```

- `RUN` 对应一个 `traceId`；保存/修订流程通常没有 `step`，单独成 RUN，不并入聊天最后一个 step。
- `SESSION` 是根节点，包含多个 RUN 与 `trace/partial` 的 INTEGRITY 节点。
- `STREAM/TURN/STEP` 是结构化包装节点；没有对应 start 事件时 `hasStart=false`。
- `INTEGRITY` 只来自 `trace/partial`，不推断丢失事件内容。

## 3. 节点字段

```text
nodeId, kind, eventType, traceId, turn, step, callId, tool,
status, startSeq, endSeq, eventSeqs, startedAtMs, endedAtMs,
durationMs, durationDerived, hasStart, hasEnd, inferred,
errorCode, linkConfidence, attributes, children, warnings
```

- `status` 只取事件观察值；父节点 `SUCCESS` 不代表子工具成功。
- `durationMs` 优先取终止事件 `durationMs`；缺失时才用 start/end 时间派生，并置 `durationDerived=true`。
- `inferred=true` 只用于由事件分组得到的 RUN/TURN/STEP 包装，不表示虚构步骤。
- `linkConfidence`：`EXPLICIT_CALL_ID`（工具/业务）、`STRUCTURAL_STEP`（LLM/step 结构）、
  `REFERENCE_VERSION`（计划确认/活动）、`SESSION`。
- `warnings` 为稳定代码（如 `MISSING_END`、`DUPLICATE_CALL_ID`），不写自由文本。

### 稳定 nodeId

```text
session:{sessionId}
run:{traceId}
stream:{traceId}
turn:{traceId}:{turn}
step:{traceId}:{turn}:{step}
llm:{traceId}:{turn}:{step}:{ordinal}
tool:{traceId}:{callId}（重复 callId 追加 #n；无 callId 用 step/tool/ordinal）
plan-validate:{traceId}:{seq}
plan-draft:{traceId}:{seq}
plan-revise:{traceId}:{seq}
plan-confirm:{traceId}:{seq} 或 confirm 的 draftVersion/operation 组合
activity:{traceId}:{seq}
unknown:{seq}
```

同一事件集重复组装必须得到相同 nodeId 和顺序。

## 4. 关联规则

### 4.1 会话 / RUN / Turn / Step

1. `session/start`、`session/end`、`trace/partial` 归 SESSION。
2. `session/end` 之后仍继续消费事件；幂等保存/修订可能追加事件，计数写入 `eventsAfterSessionEnd`。
3. 其他事件按 `traceId` 建 RUN；`traceId` 为全零的 integrity 事件归 SESSION。
4. `turn/start`/`turn/end` 建 TURN；只有 `step>0` 才建 STEP；step 0 是流/轮次信封。
5. 保存/修订事件通常 `step=null`，直接挂在 RUN 下。

### 4.2 LLM

- `llm/request` → `llm/response` / `llm/error` 按 `(traceId, turn, step)` FIFO 配对。
- LLM 事件没有 callId，这是正常设计；关联置信度为 `STRUCTURAL_STEP`，不宣称因果级关联。
- 没有 request 的 response 保留为 `hasStart=false`；没有 response 的 request 保留为 `hasEnd=false`。
- 未实现的 `llm/retry` 保留为 UNKNOWN 事件，不伪造重试节点。

### 4.3 工具与业务事件

- `tool/call` → `tool/result` / `tool/error` 优先按 `(traceId, callId)` FIFO 配对。
- callId 缺失时按 `(traceId, turn, step, tool, ordinal)`；同名工具不合并。
- 同一 step 内重复 callId 追加 `#n` 并计入 `duplicateCallIdCount`；跨 step 复用 synthetic callId 不误判为重复。
- 业务事件（`map/search`、`place/semantic-retrieval`、`route/estimate`、`schedule/free-window`、
  `map/permission-filter`）挂到同 step/callId 的 TOOL 节点；找不到父工具时保留为 orphan 并告警。

### 4.4 规划链

- `plan/validate`、`plan/draft`、`plan/revise` 为单事件节点。
- `plan/confirm RUNNING` 与终态按 `draftVersion + operation` 配对；无 RUNNING 的终态是幂等保存的正常情况，
  不伪造开始。
- `activity/created`、`plan/save-error` 在 draftVersion 匹配时挂到最近 confirm 下，否则独立保留。
- 不把 `draftId` 当作真实草稿表外键；当前实现中它对应 `ai_conversation.id`。

## 5. 缺失与异常

| 情况 | 处理 |
|---|---|
| 缺终止事件 | `hasEnd=false`，保留最后观察状态，warnings 加 `MISSING_END` |
| 缺开始事件 | `hasStart=false`，不反推开始时间 |
| seq 不连续 | 按实际 seq 保留；`seqContiguous=false`，`seqGaps` 记录跳变 |
| 重复 callId | 同 step 内追加 `#n`，计入 `duplicateCallIdCount` |
| 未知事件类型 | 保留为 `UNKNOWN`，计入 `unknownTypeCount` |
| payload 截断/解析失败 | 空 attributes，计入 `payloadErrorCount` |
| `session/end` 后仍有事件 | 保留并计入 `eventsAfterSessionEnd` |
| `trace/partial` | INTEGRITY 节点，累计 `droppedCount` |

## 6. 诊断

`TraceExecutionDiagnostics`：

```text
eventCount, firstSeq, lastSeq, seqContiguous, seqGaps,
droppedCount, unknownTypeCount, missingStartCount, missingEndCount,
duplicateCallIdCount, orphanBusinessCount, eventsAfterSessionEnd,
payloadErrorCount, warnings
```

诊断只说明“哪里不完整”，不补造丢失内容，也不把 PARTIAL 显示成 COMPLETE。

## 7. 属性白名单

节点 `attributes` 只保留安全摘要：模型、Token、计数、ID、枚举、规则 ID、错误码、耗时、布尔值等。
以下内容不会进入节点：

- 聊天正文、prompt、模型回答、推理内容；
- 地点名/地址/备注/描述；
- 经纬度、折线、轨迹；
- 日程标题/备注/具体占用时间；
- JWT、API Key、Cookie、密码、签名 URL。

即使旧数据或导入数据里出现未知字段，也不会被复制到节点属性。

## 8. 与后续阶段的关系

- P2-B 将复用本模型做只读回放 API/播放器；本轮不新增接口。
- P2-C 快照的 `summary_json` 可以直接序列化本模型的执行链摘要与诊断，但本轮不建快照表。
- P2-D 只在真实事件已采集的前提下补充约束/版本展示，不允许用 LLM 事后编造解释。

## 9. 测试

`TraceExecutionAssemblerTest` 覆盖：

1. 正常 chat + tool + LLM + plan 链；
2. 工具缺终止事件不伪造成功；
3. LLM response 缺 request；
4. 同 step 重复 callId/同名工具不合并；
5. save/revise RUN 与 chat STEP 分离；
6. seq gap、未知事件、敏感 payload、orphan business、trace/partial；
7. 1 万事件稳定组装、nodeId 唯一、重复组装结果一致。
