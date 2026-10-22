# 第四轮 Agent 验收（真实 qwen-plus，2026-10-22）

## 环境

| 项 | 值 |
|---|---|
| 基线 | `main` / `f97658d`，tag `v0.3.0-round3` |
| 分支 | `feat/round4-agent-acceptance` |
| 模型 | qwen-plus（DashScope OpenAI 兼容） |
| 路线 | 高德 Web 服务（真实返回，routeConfidence=AMAP） |
| 数据 | `seed-demo.mjs`，Map A `[DEMO] 周末去哪儿`、Map B `[DEMO] 社团活动策划` |
| 重复 | 每场景 3 次；S6 1 次 |
| 输入 | `tools/eval/scenarios.json`；输出 `docs/验收记录/第四轮/raw/f2-final.jsonl` |

## 场景与结果

| ID | 场景 | 结果 | 关键断言 |
|---|---|---|---|
| S1 | Map A 两站步行，全员授权 | **3/3 PASS** | FEASIBLE、2 站、GROUP_MAP、AMAP、总交通=分段和、7 人 KNOWN |
| S2 | Map B 临时撤销 demo09 授权 | **3/3 PASS** | TENTATIVE、UNKNOWN 成员、恢复授权 |
| S3 | Map A 与 demo02 日程重叠 | **3/3 PASS** | CONFLICT、BLOCKING、overlapMinutes>0 |
| S4 | Map A 语境询问 Map B 专属地点 | **3/3 PASS** | 不把 Map B 地点当作 GROUP_MAP；sourceMapIds 不含 Map B |
| S5 | 站间只留 2 分钟（软观察） | 1/1 观察 | 模型调整/放宽了时间并生成 FEASIBLE；硬校验规则由 `PlanValidatorTravelCheckTest` 覆盖 |
| S6 | 保存/幂等/版本 409 | **PASS** | 过期版本 409、重复保存同 activityId |

- 回归：后端 **185/185**、前端 46/46、构建通过；证据在 `docs/验收记录/evidence/round4-*.log`。
- 硬门禁：**S1–S4 各 3/3，S6 通过**。
- 汇总：13 次硬运行全部通过；p50=26.1s、p95=40.1s、Token 合计 469,264、工具调用中位数 7。
- S1 单场景：3/3，Token 平均 33,817，轮次 6，工具 5，耗时平均 23.4s。
- S2 单场景耗时 21.9–40.1s；S3 26.1–28.3s；S4 26.0–37.1s。

## 验收过程中发现并修复的问题

1. **聊天类 Trace 永远 `complete=false`**：`TraceRun.finish` 只调用 `onRunClosed`，从未登记 `finishSession`，导致 `session/end` 不落库。已改为 finish 前登记 pending，再 close；S1 重跑后 Trace 100% 完整。
2. **群语境下模型伪造 `referenceMapId`**：一次 S1 运行中模型填了不存在的 `mapId:1`，`PlanValidator` 以“无权限”拒绝，导致无草稿。已改为群语境下丢弃模型 `referenceMapId`，以服务端 `sourceMapId` 盖章为准；S1 随后 3/3。
3. **评测提示需明确“必须调用 submit_plan”**：首轮 S2 有一次模型只输出文字、未实际提交工具。场景提示已加“只有工具返回 accepted:true 才算完成”；最终 S2 3/3。
4. **公开卡片字段口径**：`verify-round2-real-llm.mjs` 不再对 `AGENT_PLAN_CARD` 断言 `groupId/sourceMapIds`（公开摘要没有这些字段），改从发起人 handoff → AI 会话详情读取完整草稿。

## 数据与隐私

- 评测数据使用 `[R4-EVAL]` 前缀；S3 临时日程结束时删除；S2 授权在 finally 恢复；S6 保存的活动随后取消。
- 落盘 JSONL 已脱敏：不写完整草稿坐标、私有地点名、AGENT 原文或提示词，只保留约束状态/站点数/来源/Token/耗时等指标。
- 不提交 `.ai-run` 原始日志、API Key、JWT 或私聊内容。

## 复现

```bash
# 后端需最新 jar / qwen-plus 与高德 key
node tools/seed-demo.mjs --verify-only

# 3 次重复 + 保存/幂等/409
node tools/eval/run.mjs --scenario S1,S2,S3,S4 --repeat 3 --save \
  --out docs/验收记录/第四轮/raw/f2-final.jsonl

# 交通软观察
node tools/eval/run.mjs --scenario S5 --repeat 1 \
  --out docs/验收记录/第四轮/raw/f2-travel.jsonl
```

## 未覆盖/边界

- S5 的“只留 2 分钟”没有被模型原样提交；它调整了时间后生成可行方案。硬性交通拒绝由 `PlanValidatorTravelCheckTest` 覆盖，不能把 S5 当作真实 LLM 拒绝证据。
- 本轮只验证单实例、单机、固定 demo 数据；多实例 WS/调度不在范围内。
- 结果受模型采样与外部网络影响，N=3 只用于观察稳定性，不外推为线上 SLA。
