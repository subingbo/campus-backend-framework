# Agent Trace 查询与导出 API

前缀 `/api/agent/traces`，全部需要 JWT 登录；数据范围由 `TraceAccessPolicy` 限定为本人。
返回统一使用项目既有 `Result<T>` 包装（导出接口除外）。

## 1. 会话列表

```http
GET /api/agent/traces?page=1&size=20&status=COMPLETED
```

- `page` 默认 1；`size` 默认 20，最大 100。
- `status` 可选：`RUNNING` / `COMPLETED` / `INTERRUPTED` / `FAILED`。
- 按 `started_at DESC`。

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "list": [
      {
        "sessionId": "43eebfbd-ea43-46fd-b3a9-09aa58a5de16",
        "conversationId": 1,
        "triggerSource": "chat",
        "provider": "dashscope-compatible",
        "model": "qwen-plus",
        "status": "COMPLETED",
        "startedAt": "2026-10-19T16:56:00.855",
        "endedAt": "2026-10-19T16:56:01.995",
        "durationMs": 1077,
        "promptTokens": 520,
        "completionTokens": 200,
        "totalTokens": 720,
        "eventCount": 36,
        "toolCallCount": 3,
        "errorCount": 0,
        "droppedCount": 0,
        "lastSeq": 36,
        "pendingEvents": 0,
        "complete": true,
        "estimatedCost": 0.00092,
        "costCurrency": "CNY",
        "costStatus": "KNOWN",
        "costPricingVersion": "demo-2026-10"
      }
    ],
    "page": 1,
    "size": 20,
    "total": 1
  }
}
```

`complete` 由服务端判定：`status == COMPLETED`、effective `dropped_count == 0` 且 `pendingEvents == 0`。
effective dropped 包含 writer 中尚未持久化的丢弃计数。
`totalTokens` / `promptTokens` / `completionTokens` 在模型服务端未返回时为 `null`，不是 0。
`lastSeq` 是已落盘的最后事件序号（复用 `agent_trace_session.next_seq`），自动刷新用它判断尾部是否追平。
`pendingEvents` 是仍在 writer 队列中、尚未落库的事件数；终态会话只有 `pendingEvents=0` 且事件数追平
`lastSeq` 时才可认为尾部同步完成。该计数是**当前应用实例内存态**；多实例部署下只代表本实例，
当前项目按单实例假设。

成本字段为读取时估算，不落库：

- `costStatus=KNOWN`：模型唯一且与会话模型一致、所有 `llm/response` 都有 prompt/completion Token、费率配置有效；`estimatedCost` 才非空。
- `PARTIAL`：存在丢弃事件（含尚未落库的 drop），Token 可能不完整；金额为 null，不能宣称完整成本。
- `UNKNOWN_MODEL`：模型缺失、混合或与会话模型不一致（旧 Trace 通常属于此类）。
- `UNKNOWN_RATE`：模型无费率或费率非法。
- `UNKNOWN_TOKENS`：Token 缺失/负数，或 usage 不完整。
- `DISABLED`：成本核算关闭。
- 未知状态时 `estimatedCost=null`，前端显示“不可估算”，不会用 0 冒充；已知零成本仍可返回 0。
- 成本只按当前 `campus.agent.trace.pricing` 费率计算，费率变化后历史会按新费率重算，`costPricingVersion` 用于标注口径。

## 2. 会话详情

```http
GET /api/agent/traces/{sessionId}
```

返回与列表项相同的字段，另附两个仅详情返回的聚合字段（P1 增强）：

- `stats`：耗时统计，按事件类型聚合（`tool/result` 再按工具名细分），只统计 `duration_ms` 非空的事件；百分位用最近秩法。
- `draftVersions`：草稿版本摘要，由 `plan/draft` + `plan/validate` + `plan/revise` 事件聚合，供前端做版本 diff；基于脱敏摘要（站点数/总行程/路线置信度/地点 ID），不含原文。

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "43eebfbd-ea43-46fd-b3a9-09aa58a5de16",
    "status": "COMPLETED",
    "complete": true,
    "stats": [
      {"type": "llm/response", "tool": null, "count": 4, "totalMs": 4200, "avgMs": 1050, "p50Ms": 900, "p95Ms": 1600},
      {"type": "tool/result", "tool": "route_info", "count": 1, "totalMs": 320, "avgMs": 320, "p50Ms": 320, "p95Ms": 320}
    ],
    "draftVersions": [
      {
        "version": 1,
        "draftId": 12,
        "stopCount": 2,
        "totalTravelMinutes": 18,
        "routeConfidence": "REAL",
        "constraintStatus": null,
        "selectedPlaceIds": [101, 102],
        "validatePassed": true,
        "validatePhase": "SUBMIT",
        "ruleIds": [],
        "reviseChanged": null,
        "revisedFromVersion": null
      }
    ]
  }
}
```

非本人/不存在：HTTP 404 + `Result.code=404`。聚合失败不影响详情主数据（对应字段缺省）。

## 3. 事件分页

```http
GET /api/agent/traces/{sessionId}/events?page=1&size=100&type=tool/result&callId=call_1&step=1
```

- `size` 默认 100，最大 200。
- `type` 精确匹配 `domain/action`；`callId` 精确定位一次工具调用；`step` 精确匹配步骤。
- 按 `seq ASC`。
- `afterSeq` 可选，用于自动刷新增量追加；存在时**优先于 `page`**，`page` 被忽略且响应 `page=1`。只返回 `seq > afterSeq` 的事件，仍应用 `type/callId/step` 过滤；`total` 始终是当前筛选条件下的全量计数。
- `afterSeq` 为负数返回 HTTP 400；越权/不存在仍在查询前返回 404。
- 增量不是历史分页：历史加载继续用 `page`；客户端必须分别维护历史进度和增量游标。

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "list": [
      {
        "eventId": 9,
        "type": "tool/result",
        "seq": 9,
        "time": 1792400160973,
        "turn": 1,
        "step": 1,
        "callId": "call_1",
        "status": "SUCCESS",
        "durationMs": 3,
        "tool": "list_my_maps",
        "errorCode": null,
        "data": {"output": {"resultCount": 0}, "reused": false}
      }
    ],
    "page": 1,
    "size": 100,
    "total": 36
  }
}
```

`data` 是已脱敏的 `payload_json` 解析结果，不含原文/坐标/秘密。

## 4. JSONL 导出

```http
GET /api/agent/traces/{sessionId}/export?format=jsonl
```

- `format` 仅支持 `jsonl`；其他值 400。
- `Content-Type: application/x-ndjson;charset=UTF-8`
- `Content-Disposition: attachment; filename="session-<id>.jsonl"`
- 流式输出：keyset 按 `seq` 分页（每批 500），不一次性装入内存。
- 首行 `trace/meta`，后续每行一个事件；每行都是合法 JSON。
- 导出走与列表相同的权限校验；内容已脱敏。

示例（脱敏）：[sample-session.jsonl](sample-session.jsonl)。

## 5. 错误与边界

| 场景 | 返回 |
|---|---|
| 未登录 | 401 |
| 非本人 / 不存在 | 404（避免探测） |
| `format` 非 jsonl | 400 |
| 导出开关关闭 | 403 |
| 会话无事件 | 200，仅首行 `trace/meta` |
| 导出中途 DB 异常 | 已发送部分无法回滚；服务端记录错误，客户端可能收到截断文件 |

## 6. curl 示例

```bash
TOKEN="Bearer ..."
curl -H "Authorization: $TOKEN" "http://localhost:8080/api/agent/traces?page=1&size=20"
curl -H "Authorization: $TOKEN" "http://localhost:8080/api/agent/traces/<sessionId>"
curl -H "Authorization: $TOKEN" "http://localhost:8080/api/agent/traces/<sessionId>/events?size=200"
curl -H "Authorization: $TOKEN" "http://localhost:8080/api/agent/traces/<sessionId>/events?afterSeq=36&size=200"
curl -H "Authorization: $TOKEN" -o session.jsonl \
  "http://localhost:8080/api/agent/traces/<sessionId>/export?format=jsonl"
```
