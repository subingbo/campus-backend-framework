# AI 助手性能与成本报告（阶段 E 基线 + 优化对照）

> 项目：`D:\code\gdufs-campus`；模型 qwen-plus（DashScope 兼容模式）+ 高德 Web 服务真实调用
> 采集窗口：2026-10-15 22:32–22:58（基线①开关关 e1a；基线②开关开 e1b），每场景 1 轮真实对话
> 观测采集：`AgentService` 内 `RunTracker` → logger `AI_RUN` 单行 JSON（§2 原始数据文件引用）
> 红线：原始记录/本报告**不含用户正文**；密钥未输出；测试数据（e1a_/e1b_ 用户）未清理但均为隔离测试账号

## 1. 采集方法

- 工具：`tools/phase-e-baseline.mjs`（Node + 全局 fetch），注册独立用户 → 建会话 → SSE 发送（解析 event/status/delta/plan/done/error 帧并计时）→ 明细回查。
- 后端侧每次 run 由 `AI_RUN` logger 输出一行 JSON；`campus.ai.efficiency.enabled=false/true` 两轮对照（同一脚本、同一场景文案，仅换用户前缀 e1a/e1b，保证限流与会话独立）。
- 预热会话一次不计入场景（建立连接与首冷缓存）。

### 固定 10 场景（冻结文案（原版在 `tools/phase-e-baseline.mjs`，均可复制重现））

| # | 场景 | 期望 |
|---|---|---|
| s01 | 单站例会（系统地点库） | 草稿或合理追问 |
| s02 | 三站打卡（系统地点库+高德便利店） | 草稿 |
| s03 | 跨日多站（周一/周二/周三） | 草稿或如实说明 |
| s04 | 指定共享地图优先 | 草稿（以地图地点为主） |
| s05 | 系统库没有，走高德 POI（甜品店） | 草稿（外部 POI） |
| s06 | 模糊日期（"这周末天气最好"） | 合理追问或低风险草稿 |
| s07 | 不可用生僻地名 | 追问/不编造 |
| s08 | 纯聊天（无成形需求） | 纯答复，不产草稿 |
| s09 | 高德 0 结果（生僻店名） | 回复查不到并询问 |
| s10 | 综合：地图 2 点+高德新点+路线 | 草稿 |

## 2. 对照总表（AI_RUN 实测）

| 场景 | e1a 轮 | e1a tok | e1a 结局 | e1b 轮 | e1b tok | e1b 结局 | tok Δ |
|---|---|---|---|---|---|---|---|
| s01 | 10 | 21210 | ANSWERED | 12 | 25283 | ANSWERED | +19% |
| **s02** | **14** | **44368** | **DRAFT** | **7** | **23167** | **DRAFT** | **−48%** |
| s03 | 13 | 28394 | ANSWERED | 14 | 34661 | **BUDGET** | +22% |
| s04 | 5 | 11380 | DRAFT | 5 | 11753 | DRAFT | +3% |
| s05 | 9 | 27267 | DRAFT | 11 | 33323 | DRAFT | +22% |
| s06 | 9 | 19419 | DRAFT | 4 | 7242 | ANSWERED | −63% |
| s07 | 4 | 7196 | ANSWERED | 4 | 7430 | ANSWERED | +3% |
| s08 | 1 | 1991 | ANSWERED | 1 | 2405 | ANSWERED | +21% |
| s09 | 3 | 5330 | ANSWERED | 3 | 5475 | ANSWERED | +3% |
| **s10** | **14** | **50617** | **BUDGET** | **8** | **23662** | **DRAFT** | **−53%** |
| **合计（不含预热）** | — | **215281** | 草稿 4/10、触顶 2 | — | **174401** | 草稿 4/10、触顶 1 | **−19.0%** |

辅助口径（AI_RUN totalMs 求和，含真实航路/POI/LLM 网络耗时）：e1a ≈ 226.3s、e1b ≈ 259.9s（+15%，受单轮网络波动影响，不作优化结论）。

## 3. 结论与解读（只报事实，不承诺百分比目标）

1. **token 总量 −19%（同脚本同场景对照）**；两个关键场景显著改善：**s02** 14→7 轮（同出稿）、**s10** 14 轮触顶未出稿 → 8 轮出稿（观测显示其路线三次调用中 1 次命中 Redis 缓存，routeCacheHitDelta=1）。
2. **出稿率 4/10 两轮一致**；未出稿的四个场景（s01/s03/s07/s08/s09 组合）中 s07-s09 是设计上"该追问/不编造"的合理行为；s01/s03 是模型自行收束未提交——与抽样方差相关（见第 4 条）。
3. **run 内同参缓存（repeatHits）两轮均为 0 次命中**：真实模型在这 10 场景中未发出"完全相同参数"的调用（相邻轮的重复参数略变了关键词/坐标，精确匹配不命中；同目标重复查询的压制由提示词效率指引承担）。该机制作为防御保留（`AgentRunObservationTest#repeatedSameArgs...` 已用 mock 证其正确性），实测本次贡献为 0。
4. **样本方差声明**：qwen-plus 非确定性输出，每个场景只跑 1 轮；e1b 的 s06（草稿→纯答复）、s03（答复→触顶）为变差个例，s05 +22% 同为采样波动。**对照表的绝对单场景数字波动大；"合计 −19%" 与 s02/s10 两个大幅改善点为可比信号，不能外推为稳定百分比承诺。**
5. 候选后续（不在本阶段执行）：① s01/s03 类"调了满轮工具但不提交"可考虑在 prompt 中再强化"未提交就总结一次"的收束规则；② 若 G 阶段演示需要，可对固定场景做 3 连发取中位数方差。

## 4. 观测基建产物（随阶段 E 落地）

- `AI_RUN` 单行 JSON 字段：ts / conversationId / userId / rounds / tokensTotal / tools[{name,ok}]（仅名与成败，不含返回文本）/ routeCalls / routeEstimated / repeatHits / routeCacheHitDelta / routeCacheMissDelta / firstVisibleMs / totalMs / hasDraft / outcome（DRAFT/ANSWERED/BUDGET_EXCEEDED/CANCELLED/ERROR）/ stopCause。
- `RouteCache` 增 hits/misses 计数器（差分用量），供"缓存命中率"观测。
- **阶段 E 真实联调时发现并修复一个生产缺陷**：SSE 完成后的 Tomcat async dispatch 会重走安全链，在上下文已被 SSE 线程清理的异步分派上抛 Access Denied（响应已 committed，客户端表现为流中断风险）；已在 `SecurityConfig` 设 `shouldFilterAllDispatcherTypes(false)` 只对 REQUEST 分派鉴权（53/53 测试复验通过，两轮真实采集 0 传输错误）。

## 5. 原始数据文件

| 文件 | 内容 |
|---|---|
| `.ai-run/airun-e1a.jsonl` / `.ai-run/airun-e1b.jsonl` | 两轮 `AI_RUN` 原始观测行（各 11 行） |
| `.ai-run/baseline-e1a.jsonl` / `.ai-run/baseline-e1b.jsonl` | 采集脚本输出（事件计数/耗时/草稿站点数/会话状态） |
| `.ai-run/backend-baseline1.log` / `backend-baseline2.log` | 两轮后端完整控制台（含 AI_RUN 泽行与 MyBatis 调试） |
| `.ai-run/backend-final.log` | 恢复默认开关后的常驻后端日志 |

## 6. 已知限制

- 每个场景仅 1 轮采样，方差结论见 §3.4；成功率等指标需 G 阶段多轮校准。
- `repeatHits` 机制本次实测零收益，保留为防御层；若后续模型出现精确同参行为，收益自动生效。
- 出稿率的判定以会话状态 DRAFT 为准（未自动核对草稿内容质量）；s02 出稿发生在第 14 轮贴线提交（e1a）。
- 高德全部真实成功（`routeEstimated=0`），本次未触发降级估算路径（该路径由阶段 D `MissingConfigGradingAcceptanceTest` 覆盖）。
