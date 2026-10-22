# 第三轮验收证据

- 代码基线：`main` / `f97658d`，tag `v0.3.0-round3`
- 归档日期：2026-10-22
- 环境：Windows 10 x64、Java 21.0.10、Spring Boot 3.5.0、MySQL 8（Testcontainers）、Redis 7、Vue 3 + Vite 6

| 文件 | 命令 | 结果 |
|---|---|---|
| `round3-backend-test.log` | `mvn test` | 168/168 通过，BUILD SUCCESS |
| `round3-frontend-test.log` | `cd campus-web && npm test` | 46/46 通过 |
| `round3-frontend-build.log` | `cd campus-web && npm run build` | 通过 |

说明：
- 后端完整日志约 3.7MB，保留在构建机 `/tmp/mvn-round3-b2.log`，仓库只归档最终摘要与计数。
- 日志不含 API Key、JWT、真实用户密码或生产数据；测试账号均为临时注册。
