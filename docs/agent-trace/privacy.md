# Agent Trace 隐私与数据保留

## 1. 基本原则

Trace 涉及位置、共享地图、群聊备注、个人日程与路线信息，比普通工程日志更敏感。
P0 只保存**解释业务所需的最小事实**：ID、计数、耗时、状态、枚举、规则 ID、Token 数。
不保存用户正文、地点文本、精确坐标、模型推理内容。

## 2. 绝不入库/导出

| 类别 | 例子 |
|---|---|
| 认证秘密 | JWT、Refresh Token、API Key、`Authorization`、Cookie、密码 |
| 用户聊天 | 用户消息原文、助手回复全文、群聊消息原文 |
| 地点文本 | 地点名、地址、描述、群备注原文 |
| 精确位置 | 经纬度、折线/轨迹、`get_my_location` 返回值 |
| 个人日程 | 日程标题、备注、具体占用时间；Trace 只记窗口数量/来源/未知成员数 |
| 模型内部 | reasoning 内容、原始 prompt、原始响应体、带签名 URL |

## 3. 允许保存的摘要

`TraceDataRedactor` 在**入队之前**执行：

- 工具入参：`keywordLength`、`stopCount`、`titleLength`、`mode`、`durationMinutes`、`placeId`、`mapId` 等；
- 工具结果：结果数量、地点 ID（最多 10 个）、provider、是否估算、耗时、`accepted`、`available`；
- 计划：草稿 ID/版本、站点数、来源地图 ID、约束状态、路线置信度；
- 校验：ruleId、数值型 details（可用/所需分钟、站点序号、最早开始时间）；
- 模型响应：model、prompt/completion/total Token 数（用于成本估算，不含 prompt/回答原文）；
- 保存：活动 ID、草稿版本、operation、幂等命中标记。

字符串值只允许安全字符集（枚举/ID/时间/模型名等），中文或自由文本替换为 `[text:N]`；
命中 `sk-…`、`Bearer …`、JWT 形态的值替换为 `[REDACTED]`。

## 4. DEBUG 语义

`campus.agent.trace.level=DEBUG` 在 P0 **不会**自动记录原文/坐标，采集范围与 `SUMMARY` 相同。
DEBUG 只表示允许更详细的调试日志级别；任何原文采集都必须另行设计字段白名单、授权与加密。

## 5. 访问控制

- P0 只允许**本人**读取：会话列表、详情、事件、JSONL 导出都按 `user_id` 过滤。
- 非本人/不存在统一 404，避免通过 `sessionId` 探测他人会话。
- 管理员跨用户诊断读取、群成员共享读取不在 P0 暴露；需要单独权限与审计。
- 群聊 @AI 的 Trace owner 是发起人；内容仅为脱敏摘要，不含群聊原文/备注。

## 6. 保留与删除

- 明细默认保留 `detail-days=30` 天；过期会话按批删除，事件通过外键级联删除。
- `RUNNING` 超过 `stale-running-minutes=120` 分钟：标记 `INTERRUPTED` 并写 `trace/partial`，
  不伪造 `session/end`；正在运行的会话会被跳过。
- 账号/会话删除、群权限收回等场景的 Trace 联动删除尚未在 P0 实现，后续按产品政策补充。

## 7. 验证

- 单测：`TraceDataRedactorTest` 覆盖秘密/正文/坐标/地点名剔除与安全字段保留。
- 验收：`AgentTraceFlowAcceptanceTest` 扫描 DB payload 与 JSONL，断言不含草稿标题、地点名、
  经纬度、`Bearer`、`sk-`。
- 代码审查红线：任何新事件新增 `data` 字段前，必须先更新白名单与测试。
