# Campus Server

校园共享地图与社交平台后端，Maven 多模块工程：`campus-framework`（脚手架基础设施）+ `campus-server`（校园业务）。

## 技术栈

- Java 17、Spring Boot 3.5、Maven（聚合父 POM + 两个子模块）
- Spring Security 6（JWT 无状态、RBAC、`@PreAuthorize`）
- Redis（Refresh Token、用户缓存、Access 黑名单、限流、在线态与未读数）
- MyBatis（XML Mapper，无 MyBatis-Plus）
- springdoc-openapi 2.8 + Swagger UI（接口文档按「脚手架基础设施 / 校园业务」分组）

## 快速开始

### 1. 启动依赖

```bash
docker compose up -d
```

默认：

| 服务  | 地址            | 账号              |
|-------|-----------------|-------------------|
| MySQL | localhost:3306  | campus / campus123 |
| Redis | localhost:6379  | 无密码            |

数据库与种子数据由 `campus-server/src/main/resources/db/` 初始化（`docker-compose.yml` 挂载的就是这两个文件）。

### 2. 启动应用

```bash
mvn -pl campus-server -am spring-boot:run
```

默认端口 `8080`，配置见 `campus-server/src/main/resources/application-dev.yml`。

> 根目录是聚合父 POM，可执行模块是 `campus-server`（含启动类 `CampusApplication`）。
> 也可以先 `mvn install`，再 `cd campus-server && mvn spring-boot:run`，或直接跑打包产物
> `java -jar campus-server/target/campus-server-0.0.1-SNAPSHOT.jar`。

### 3. API 文档

- Swagger UI：http://localhost:8080/swagger-ui.html （左上角切换分组）
- 分组 JSON：`/v3/api-docs/脚手架基础设施`、`/v3/api-docs/校园业务`；默认 JSON：http://localhost:8080/v3/api-docs

## 种子账号

| 用户名 | 密码     | 角色  |
|--------|----------|-------|
| admin  | admin123 | ADMIN |

## 核心接口（脚手架基础设施 · campus-framework）

| 方法   | 路径                 | 说明           |
|--------|----------------------|----------------|
| POST   | /api/auth/register   | 注册（默认 USER） |
| POST   | /api/auth/login      | 登录           |
| POST   | /api/auth/refresh    | 刷新 Token（Rotation） |
| POST   | /api/auth/logout     | 登出（需 Access Token） |
| DELETE | /api/admin/user/{id} | 权限探测（需 `ROLE_ADMIN`） |

限流探测接口已由业务接口承担：`POST /api/posts` 需 `post:create`（10 次/分钟/用户），`POST /api/places` 需 `place:create`（5 次/分钟/用户）。

## 业务接口（campus-server）

用户资料与用户主页：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | /api/users/me | 当前用户 |
| PUT  | /api/users/me | 修改个人资料 |
| GET  | /api/users/{userId} | 用户主页（含发帖/获赞统计） |

其余按模块分布：共享地图与群组（下节）、地点与地点圈、动态/评论/点赞、好友与会话消息、活动、举报、搜索、管理端（`/api/admin/**`）、WebSocket（`/ws`）。完整清单见 Swagger UI 的「校园业务」分组。

## 验收用例（自动化）

```bash
mvn test
```

四个验收用例都在 `campus-server` 模块（`mvn -pl campus-server -am test` 等价）。`InfrastructureAcceptanceTest`（脚手架侧 401/403/刷新/登出/限流）+ `MvpBusinessAcceptanceTest`、`SharedMapAcceptanceTest`、`EnhancementAcceptanceTest`（业务侧），均使用 Testcontainers 启动 MySQL 与 Redis（需本机 Docker）。

## 项目结构

```text
campus/                        # 聚合父 POM（packaging=pom），统一依赖与版本
├── campus-framework/          # 脚手架基础设施（jar，无启动类）
│   └── com.campus
│       ├── common             # Result/PageResult、异常、限流、常量（RedisKey）
│       ├── config             # Redis、OpenAPI（含文档分组）、CORS、Password
│       ├── security           # JWT、Filter、Security 配置
│       ├── auth               # 认证接口
│       └── user               # 用户实体、Mapper、UserService、权限探测接口
└── campus-server/             # 校园业务 + 启动类（可执行 jar）
    └── com.campus
        ├── profile            # 用户资料与用户主页接口
        ├── place / post / map / social / activity / report / search / admin
        ├── ws                 # WebSocket 聊天与地图事件
        └── common.{geo,util}  # 地理计算（bbox/Haversine）与分页、LIKE 转义工具
```

依赖方向单向：`campus-server` → `campus-framework`，框架模块不引用任何业务类型。Mapper XML 分别放在各模块 `src/main/resources/mapper/` 下，由 `mybatis.mapper-locations: classpath*:mapper/*.xml` 一起装载。

## 共享地图（Campus Map P0）

后端新增 `com.campus.map` 模块，核心接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/groups | 创建群组 + 1:1 共享地图 |
| POST | /api/groups/join | 邀请码加入 |
| GET | /api/maps | 我加入的地图 |
| GET | /api/maps/{id} | 地图详情 / 成员 |
| POST | /api/maps/{id}/places | 地图内添加地点（5m 融合） |
| PUT | /api/places/{id} | 编辑地点（需为地图 Editor+） |

自动化：`SharedMapAcceptanceTest`（Testcontainers）。

### 前端 campus-web

```bash
cd campus-web
cp .env.example .env   # 配置 VITE_AMAP_KEY
npm install
npm run dev
```

开发服务器默认 `http://localhost:5173`，通过 Vite 代理访问后端 `/api`。

## 地理数据与 5m 去重

### 存储分工

| 组件 | 职责 |
|------|------|
| **MySQL** | 地点坐标与业务数据的 **Source of Truth**（`place.longitude/latitude` 为 `DECIMAL(10,7)`，`address` 为展示用） |
| **`map_place`** | 共享地图与地点的多对多关联，**不重复存坐标**；同一 `place_id` 可出现在多张地图 |
| **Redis** | 登录态、地点详情等**热点缓存**，不承载主地理数据 |
| **Java** | Bounding Box 粗筛 + **Haversine** 精确距离（米），用于 5m 业务去重 |

暂不引入 MySQL Spatial / PostGIS / Redis GEO：校园规模（单校几百～几万 POI、单图通常几十～几百点）下，应用层距离计算足够，答辩也便于讲清分层。

### 坐标体系与客户端分层

- **坐标体系：GCJ-02**。`place.longitude/latitude` 统一按 GCJ-02 使用。库内暂无种子地点，现有坐标都由客户端写入：Web 端地图选点与 `AMap.Geolocation` 定位均经 `convert: true` 从 WGS84 修正。手机端将来也用 GCJ-02 写入，保证两端同一基准。
- **客户端分层**：`campus-web` 用高德 JS API。定位能力在 `composables/useLocation.js`（不认识任何地图厂商），地图能力在 `composables/useAmap.js`（含高德定位适配器），两层互不 import，由视图通过 provider 契约组合；手机端（`campus-mobile`，后续独立）换成系统定位 provider 即可复用同一套定位层与后端接口。
- **后端不绑定地图厂商**：接口只收发 `longitude`/`latitude`，5m 融合判定在服务端完成，客户端只负责展示。
- 遗留标注：`db/schema.sql` 中 `place.longitude/latitude` 的列注释仍写着 `WGS84`，与实际写入的 GCJ-02 不一致，待后续统一。

### 表关系（示例）

```text
place (id=100, 北门烧烤, lon/lat)
   ↑
map_place: (map_id=1, place_id=100), (map_id=2, place_id=100)
```

### 两条 5m 路径（概念不同）

1. **全局** `POST /api/places`（保留，兼容验收）：在全库 `place` 上做 **bbox 查询 → Haversine ≤5m**；有候选则返回 `candidates`，`confirmNew=true` 可强制新建实体。
2. **地图内** `POST /api/maps/{id}/places`（产品主路径）：仅在**当前地图已关联**的地点上做 **map_id + bbox → Haversine ≤5m**；用户选「补充已有地点」时只 `INSERT map_place`，不新建 `place`。

地图 A 内的 5m 候选**不会**阻止用户在地图 B 同坐标新建或关联。

### 查询流程

```text
上传坐标 (lng, lat)
      ↓
GeoUtils 计算 bbox（约 ± 半径对应度数）
      ↓
MySQL 范围查询（全库或 map_id 限定）
      ↓
Java Haversine 精确筛选 ≤ 5m
      ↓
返回候选 / 创建 place 或 map_place
```

实现见 `com.campus.common.geo.GeoCandidateQuery`、`PlaceServiceImpl`、`SharedMapServiceImpl`。

连接与容器均使用 **utf8mb4**（见 `application-dev.yml` 的 JDBC URL、`hikari.connection-init-sql` 与 `docker-compose.yml`）。若历史数据已乱码，需修正错误写入的行，或 `docker compose down -v` 后重建库（会清空数据）。

## AI 活动规划助手（2026-10-15）

一句话描述活动 → 助手结合**共建共享地图**的地点与**高德**真实路线/POI → 流式输出多站时间轴草稿 → 确认后落成系统活动（报名/邀请/签到/日历照用）。核心叙事：共享地图感知（AI 优先用朋友共同收集的地点）、真实工具调用（Function Calling，不编造）、活动业务闭环。

- **页面**：`/assistant`（左对话流式 + 右地图站点折线/时间线卡片 + 保存为活动）；全局顶栏导航壳（`campus-web/src/components/AppShell.vue`）提供「AI 助手」入口，工作台与群聊 AI 卡片均跳同一处。
- **新增接口（校园业务分组）**：`/api/assistant/chats*`（会话与 SSE 消息，event= status/delta/plan/**geo**/done/error；消息体可带 `userLocation`，含 `source: device|manual`）、`POST /api/assistant/chats/{id}/plan/save`（草稿→活动，幂等）、`GET /api/assistant/routes`（站间折线，与规划同源缓存）。
- **Agent**：DashScope OpenAI 兼容模式 + function calling，13 个工具（2026-10-18 新增 `get_my_location`：/assistant 输入区「📍 位置」开关打开后，前端每条消息附实时定位快照注入本轮工具语境；个人会话「参考地图」支持多选，注入多张图名优先级）
- **对话即地图（2026-10-18）**：工具查到/路过的地点、真实路线与"我的位置"会实时画到右侧地图并生成可点击 chip（点 chip 居中并开浮窗，共建/收藏点可跳「在共建地图打开」）；点空白处关浮窗、点地图标记同样开浮窗。定位不准可点「纠偏」在地图上点选真实位置（优先于 GPS、localStorage 记忆、可清除）。AI 回复用 Markdown 渲染（marked + DOMPurify）。地图上的点/路线/我位置随助手消息落库（`ai_message.geo_json`），刷新与重开历史会话会一并回放。历史会话用输入区上方的「会话 ▾」切换（标题/草稿·已保存徽标/时间，当前会话高亮）。草稿提交已**直点化**：信息够时模型直接 `submit_plan`（不再停下来请示用户），草稿一生成立即弹出「草稿已生成」确认弹窗（一键保存为活动）；模型若仍发出确认式问句，气泡下会出现「提交吧/先不提交」快捷键。回复下的地点芯片会**同点合并**（同一地点只出现一次，带地点 ID 的版本优先）；最后查看的会话记在本地，刷新/重进页面回到你正看的会话。芯片**按行程路线顺序排列**：命中行程站的芯片排前并带站序号角标（①②③…），其余按工具时间足迹跟后。后端带三道收敛护栏：同参重复调用直接打回（duplicate_call）、搜索类工具单次规划限 6 次真实执行（search_budget_exhausted）、第 8 轮起注入“预算将尽”提示；所有工具失败原因进 AI_RUN 观测日志。（共建地图语义检索/地点上下文/个人地图/系统地点/路线/POI/忙闲/空闲求解/时空联合规划 plan_itinerary/提交草稿）——群聊 @AI 由后端盖章 groupId/sourceMapIds/来源标签与规划指纹；`SpatiotemporalPlanner` 确定性联合求解（共同空闲 + 高德真实交通 + 停留 + 收尾缓冲，LLM 不判可行性）；`PlanValidator` 校验含站点间交通运输硬校验；保存链路带群体二次校验（退群/地图变化/忙闲变化 → 409 重新确认）与 revise 无变更守卫；限流 `ai:chat` 10 次/分钟；预算 14 轮/每轮 5 工具。
- **多站行程**：`activity_stop` 表（GCJ-02，`uk_activity_seq`），创建/编辑活动支持可选 `stops`；详情与「我的日程」带出时间线；签到/邀请/提醒仍挂主活动。
- **手动创建活动（2026-10-18）**：活动日程页「＋ 新建活动」——选已有地点，或「地图点选新建」（mini 地图点选 + 命名/分类，自动先建地点再建活动，5 米查重自动复用）；表单可选填 ≤8 站行程（站点选地点 + 时间/交通/备注；编辑时整体替换，纯坐标站只读保留）。工作台「安排活动」入口保留。
- **存量库迁移**（initdb.d 不会对旧库重跑）：

```bash
docker compose exec -T mysql mysql -ucampus -pcampus123 campus < campus-server/src/main/resources/db/migration/20261015_01__create_activity_stop.sql
docker compose exec -T mysql mysql -ucampus -pcampus123 campus < campus-server/src/main/resources/db/migration/20261015_02__create_ai_tables.sql
```

- **密钥配置**：真实 key 放 git 忽略的 `campus-server/src/main/resources/application-secrets.properties`（`DASHSCOPE_API_KEY` / `AMAP_WEB_KEY`），`application.yml` 经 `spring.config.import: optional:` 引入；也可直接配同名环境变量覆盖。高德这边是**Web服务**类型 key（与前端 JS key 不同）。
- **依赖**：后端零新增 Maven 依赖（JDK HttpClient + SseEmitter）；前端新增 `marked` + `dompurify`，用于 AI 回复的 Markdown 渲染（v-html 必须消毒）。
- **测试**：`ActivityStopAcceptanceTest`、`AgentChatMockedAcceptanceTest`（mock 大模型全链路，无需真实 key）。

## 时空协同智能体（Spatiotemporal Agent · 2026-10-16）

在原「AI 活动规划助手」基础上按《docs/时空协同Agent开发计划.md》升级为群体时空协同。

### 新增能力

- **个人日程**：`personal_schedule` 表 CRUD（`/api/schedules`），activity（已报名/创建）为第二忙碌来源——忙碌视图=两者联合（不复制存储）。
- **免费忙闲授权**：`/api/schedules/share`（USER/GROUP 目标，FREE_BUSY 等级=仅时段不露标题）；`GET /api/schedules/group-busy` 返回 known（含未知）视图，未授权成员不视为有空。
- **时间求解器**：`com.campus.ai.planner.FreeWindowSolver`（区间合并+补集+时长筛选，计划 §5.2）。
- **Agent 工具（共 7+1）**：新增 `get_my_schedule` / `find_free_windows`；群聊语境下 `find_free_windows` 返回群体忙闲+coverage（由后端 ws 会话注入 viewerId/groupId，模型不可指定身份）。
- **群聊 @AI**：地图群聊发送 `@AI 帮我们……` → 复用 AgentService 规划 → 群内收到 AGENT 消息 + `AGENT_PLAN_CARD` 帧（前端 ChatPanel 渲染）；规划者可在 `/assistant` 编辑（v2）并保存为活动。
- **草稿编辑**：`POST /api/assistant/chats/{id}/plan/revise`（行锁+重校验+版本+1，expectedPlanVersion 不符返回 409），前端计划卡支持标题/时间/站点时间/备注/删除/排序编辑。

### 启动与迁移

- 全新初始化：`schema.sql` 已含全部表。
- 存量库：执行 `db/migration/20261016_01__create_personal_schedule.sql`（个人日程+授权表）；此前若未执行 20261015 两个迁移，一并执行。
- 演示脚本：`node tools/phase-g-demo.mjs demoGX .ai-run/phase-g-demoGX.jsonl`；话术见 `docs/比赛演示脚本.md`。

### 已知限制（V1）

- 群聊 `AGENT_GEO` 地理帧只实时不回放（个人会话的点与路线随助手消息 `geo_json` 落库，刷新可回放）；
- 群体忙闲的授权对象只对 GROUP 生效（USER 粒度已建模型、控制面待接）；`@AI` 卡片不做离线补推（在线帧收，历史见 AGENT 消息正文）；
- `find_free_windows` 群体分支不把" apenas 已邀未报名"计入忙碌；TRANSIT 仍不支持（沿用阶段 D 语义）；
- 演示话术默认 qwen-plus（真调用），离线兜底见演示脚本文档头部。

## 延伸阅读

- [操作逻辑与业务流程说明](docs/操作逻辑与业务流程说明.md)：系统怎么跑起来、各业务模块的操作逻辑，重点是「安排 Agent」的两条入口与线程身份、工具循环与 12 个工具、草稿生命周期的四处防线、端到端时序，以及数据流程（29 张表 / Redis key / WebSocket 帧 / 第三方调用）与 20 条容易搞错的点。
