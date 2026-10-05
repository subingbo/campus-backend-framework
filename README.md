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
