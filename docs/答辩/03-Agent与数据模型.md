# Agent 与数据模型

## 工具集（13 个 = 12 可执行 + submit_plan）

| 类别 | 工具 |
|---|---|
| 地图/地点 | `list_my_maps`、`list_map_places`、`search_places`、`get_group_map_context`、`search_group_map_places`、`get_place_context` |
| 时间 | `get_my_schedule`、`find_free_windows`、`plan_itinerary` |
| 路线/定位 | `route_info`、`amap_poi_search`、`get_my_location` |
| 提交 | `submit_plan` |

## 一次群规划

`diagrams/04-agent-group-sequence.mmd`

1. `CHAT_SEND @AI` → 同群单飞（`GroupAgentRunRegistry`），失败 `BUSY`。
2. 后端注入 `viewerId/groupId`，模型不能指定身份。
3. 工具读群地图/忙闲/路线；`plan_itinerary` 返回 Top-3 方案与 `routeConfidence`。
4. `submit_plan`：
   - `PlanValidator` 校验标题/时间/站点/权限/交通；
   - `stampGroupPlanContext` 盖 `groupId/sourceMapIds/selectedPlaceIds/指纹/来源`；
   - `PlanConflictAnalyzer` 写冲突/成员状态；
   - `group_agent_plan` 快照与 AGENT 消息同事务。
5. 发起人 handoff → `/assistant` 编辑 → `PlanSaveService` 行锁/版本/二次校验 → 活动。

## 规划数据字段（质量证据）

- `constraintStatus`：FEASIBLE / TENTATIVE / CONFLICT。
- `routeConfidence`：AMAP / ESTIMATED / PARTIAL / NONE（模型自述被清空重算）。
- `conflicts`：BLOCKING/ADJACENT、`overlapMinutes`、可执行建议。
- `members[].status`：KNOWN / UNKNOWN。
- `sourceMapIds/selectedPlaceIds/planningSourceVersion`：来源与指纹。
- `stops[].source`：GROUP_MAP / PERSONAL_MAP / AMAP_POI / USER_LOCATION。

## 表（33 张，重点）

- `shared_map/map_member/map_place/place_comment`：群体空间记忆。
- `personal_schedule/schedule_share_permission`：个人日程与 FREE_BUSY。
- `activity/activity_member/activity_invite/activity_stop`：活动与多站行程。
- `ai_conversation/ai_message/group_agent_plan`：对话、草稿、群快照。
- `notification`：`(user_id,dedupe_key)` 收件箱。
- `agent_trace_session/event`：执行证据。

ER：`diagrams/07-data-model-er.mmd`。
