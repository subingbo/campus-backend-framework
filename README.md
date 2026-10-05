# Campus Server

Campus 后端基础设施：Spring Boot 3 + Spring Security 6 + JWT + Redis + 原生 MyBatis + MySQL。

## 技术栈

- Java 17、Spring Boot 3.5、Maven
- Spring Security 6（JWT 无状态、RBAC、`@PreAuthorize`）
- Redis（Refresh Token、用户缓存、Access 黑名单、限流）
- MyBatis（XML Mapper，无 MyBatis-Plus）
- Knife4j / OpenAPI 3

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

数据库与种子数据由 `src/main/resources/db/` 初始化。

### 2. 启动应用

```bash
mvn spring-boot:run
```

默认端口 `8080`，配置见 `application-dev.yml`。

### 3. API 文档

- Knife4j：http://localhost:8080/doc.html
- OpenAPI：http://localhost:8080/v3/api-docs

## 种子账号

| 用户名 | 密码     | 角色  |
|--------|----------|-------|
| admin  | admin123 | ADMIN |

## 核心接口

| 方法   | 路径                 | 说明           |
|--------|----------------------|----------------|
| POST   | /api/auth/register   | 注册（默认 USER） |
| POST   | /api/auth/login      | 登录           |
| POST   | /api/auth/refresh    | 刷新 Token（Rotation） |
| POST   | /api/auth/logout     | 登出（需 Access Token） |
| GET    | /api/users/me        | 当前用户       |

探测接口（验收权限与限流）：

- `DELETE /api/admin/user/{id}` — 需 `ROLE_ADMIN`
- `POST /api/posts` — 需 `post:create`，10 次/分钟/用户
- `POST /api/places` — 需 `place:create`，5 次/分钟/用户

## 验收用例（自动化）

```bash
mvn test
```

`InfrastructureAcceptanceTest` 使用 Testcontainers 启动 MySQL 与 Redis，覆盖文档中的 401/403/刷新/登出/限流等场景（需本机 Docker）。

## 项目结构

```text
com.campus
├── common      # Result、异常、限流、常量
├── config      # Redis、OpenAPI
├── security    # JWT、Filter、Security 配置
├── auth        # 认证接口
├── user        # 用户与 RBAC 数据访问
└── probe       # 权限/限流探测接口
```

业务模块（地点、圈子、帖子等）在基础设施验收通过后按模块增量接入。

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
