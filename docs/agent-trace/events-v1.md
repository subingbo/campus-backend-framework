# Agent Trace 事件协议 v1

> `schemaVersion = 1`。每行一个合法 JSON；不 Pretty Print；时间使用 UTC epoch 毫秒。
> `data` 只包含白名单摘要，绝不包含聊天原文、地点名/地址/备注、精确经纬度/轨迹、JWT/API Key。

## 1. 顶层字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `schemaVersion` | int | 固定 `1` |
| `type` | string | 事件类型 `domain/action` |
| `sessionId` | string | 追踪会话 UUID |
| `traceId` | string | 单次请求/执行尝试 UUID |
| `seq` | long | 会话内严格递增（从 1 开始） |
| `time` | long | 业务发生时间（UTC epoch ms） |
| `turn` | int? | 交互轮次 |
| `step` | int? | Agent 循环步骤 |
| `callId` | string? | 工具调用关联键（模型真实 tool_call id） |
| `status` | string? | RUNNING/SUCCESS/FAILED/CANCELLED/PARTIAL/COMPLETED… |
| `durationMs` | long? | 本事件耗时 |
| `tool` | string? | 工具名 |
| `errorCode` | string? | 稳定错误码/校验规则 ID |
| `data` | object | 脱敏摘要 |

`parentEventId` 在 P0 不填。JSONL 导出首行是 `trace/meta`（不是 DB 事件），没有 `seq`。

## 2. 事件清单

| type | data 关键字段 | 说明 |
|---|---|---|
| `session/start` | trigger、conversationId、provider、model | 新 episode 开始 |
| `session/end` | status、reason、durationMs、droppedCount | 保存成功/显式中断 |
| `turn/start` | messageLength、hasUserLocation、historyLimit | 不记录消息正文 |
| `turn/end` | outcome、reason、rounds、toolCallCount、hasDraft、durationMs | DRAFT/ANSWERED/ERROR/CANCELLED/BUDGET_EXCEEDED |
| `run/metrics` | rounds、totalTokens、toolCallCount、firstVisibleMs、totalMs、routeCallCount、routeEstimatedCount、repeatHitCount、routeCacheHitCount、routeCacheMissCount、hasDraft、outcome、stopCause | 单次 run 性能/质量汇总；评测脚本读取，不替代 AI_RUN 日志 |
| `step/start` | round | 每轮 Agent 循环 |
| `step/end` | round、status | 每轮结束 |
| `llm/request` | provider、model、maxTokens、messageCount、toolCount、stream | 无 prompt 文本 |
| `llm/response` | model、finishReason、toolCallCount、toolNames、contentLength、prompt/completion/totalTokens | token 未返回则字段缺省；成本估算要求每次响应都有 prompt/completion 且模型唯一 |
| `llm/error` | errorType、httpStatus、cancelled | 无原始响应体 |
| `llm/retry` | — | P0 保留；当前 LlmClient 无重试 |
| `tool/call` | input（白名单摘要） | callId 关联 |
| `tool/result` | output（计数/ID/枚举）、reused | 不含工具原始文本 |
| `tool/error` | reused / errorCode | 分类：PERMISSION_DENIED/NOT_FOUND/PROVIDER_UNAVAILABLE/… |
| `map/search` | tool、source、resultCount、mapId | 列表/搜索/POI/群图上下文 |
| `place/semantic-retrieval` | hitCount、noteHitCount、distinctPlaceCount、placeId | 语义检索摘要 |
| `route/estimate` | mode、provider、estimated、durationMinutes、distanceMeters | 无坐标/折线 |
| `schedule/free-window` | source、windowCount、durationMinutes、unknownCount、solutionCount、routeConfidence | 不记录个人日程时间 |
| `plan/draft` | draftId、draftVersion、stopCount、selectedPlaceIds、sourceMapIds、constraintStatus | 草稿已生成 |
| `plan/revise` | draftId、fromVersion、toVersion、changed | 修订成功提交后 |
| `plan/validate` | phase、draftVersion、passed、ruleIds、ruleSet、details、travelMinutes | phase=SUBMIT/SAVE/REVALIDATE/REVISE |
| `plan/confirm` | draftId、draftVersion、activityId、operation、idempotentReuse、rolledBack | RUNNING→SUCCESS/FAILED |
| `activity/created` | activityId、draftId、draftVersion、operation、idempotentReuse | **仅事务提交后** |
| `plan/save-error` | operation、httpStatus、ruleIds、rolledBack | 保存/修订失败 |
| `stream/open` | transport | sse/ws |
| `stream/close` | closeReason、cancelled、durationMs | 总是记录 |
| `stream/error` | errorCode、clientCancelled | ERROR/BUDGET_EXCEEDED |
| `trace/partial` | reason、inferred、droppedCount | 队列满/陈旧会话推断 |
| `trace/meta` | status、eventCount、droppedCount、complete、model、startedAt、endedAt、durationMs、exportedAt | 仅 JSONL 首行 |

## 3. 校验规则 ID（`plan/validate`）

`PLAN_JSON_INVALID`、`PLAN_EMPTY`、`TITLE_MISSING`、`TITLE_TOO_LONG`、`CATEGORY_INVALID`、
`TIME_MISSING`、`TIME_ORDER_INVALID`、`TIME_IN_PAST`、`MAX_MEMBERS_INVALID`、
`STOPS_EMPTY`、`STOPS_TOO_MANY`、`STOP_NAME_MISSING`、`PLACE_NOT_FOUND`、
`STOP_LOCATION_MISSING`、`STOP_COORD_INVALID`、`STOP_TRANSPORT_INVALID`、
`STOP_TIME_BEFORE_ACTIVITY`、`STOP_TIME_ORDER_INVALID`、`STOP_END_BEFORE_START`、
`STOP_END_AFTER_ACTIVITY`、`SOURCE_MAP_TOO_MANY`、`PLACE_TOO_MANY`、`COVERAGE_TOO_LARGE`、
`REFERENCE_MAP_FORBIDDEN`、`ROUTE_ESTIMATE_FAILED`、`TRAVEL_TIME_CONFLICT`。

`TRAVEL_TIME_CONFLICT.details` 示例：

```json
{"fromSeq":1,"toSeq":2,"transportMode":"WALK","availableMinutes":5,"requiredMinutes":10,"earliestStart":"10:35:00"}
```

## 4. 示例（来自真实测试运行的脱敏导出）

见 [sample-session.jsonl](sample-session.jsonl)。节选：

```json
{"schemaVersion":1,"type":"tool/result","sessionId":"...","traceId":"...","seq":8,"time":1792400160,"turn":1,"step":1,"callId":"call_1","status":"SUCCESS","durationMs":6,"tool":"list_my_maps","data":{"output":{"mapCount":0},"reused":false}}
{"schemaVersion":1,"type":"plan/validate","sessionId":"...","traceId":"...","seq":22,"time":1792400160,"turn":1,"step":3,"status":"SUCCESS","data":{"phase":"SUBMIT","passed":true,"ruleSet":"plan-v1","stopCount":1,"draftVersion":1,"hasReferenceMap":false}}
{"schemaVersion":1,"type":"activity/created","sessionId":"...","traceId":"...","seq":35,"time":1792400161,"turn":1,"status":"SUCCESS","data":{"draftId":1,"operation":"CREATE","activityId":1,"draftVersion":1,"idempotentReuse":false}}
```

## 4.1 成本相关字段（新增，schemaVersion 仍为 1）

- `llm/response.data` 现在包含 `model`、`promptTokens`、`completionTokens`、`totalTokens`。
- 这些字段只用于 usage 完整性与读取时成本估算，不含原文/坐标。
- 2026-10-20 之前导出的旧 Trace 没有 response 级 `model/token` 字段，成本会保守判为不可估算；事件查看/导出不受影响。

## 5. 版本变更规则

- 新增字段：保持 `schemaVersion=1`，消费方必须容忍未知字段。
- 删除/重命名字段、改变语义：升 `schemaVersion`，并在本文件记录迁移说明。
- 事件类型只增不删；废弃类型保留但停止产生。
- `data` 白名单变化视为协议变更，必须同步更新 `privacy.md` 与脱敏测试。
