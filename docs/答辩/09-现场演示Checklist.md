# 现场演示 Checklist（10 分钟）

## 环境

- [ ] `docker compose up -d`；MySQL/Redis healthy。
- [ ] 8 个 migration 已执行；启动日志 `SchemaPreconditionChecker` 通过。
- [ ] 后端启动日志显示 JVM/DB 时区一致（偏差 < 2s）。
- [ ] `DASHSCOPE_API_KEY`、`AMAP_WEB_KEY` 已配置；真实 LLM 探针成功。
- [ ] 前端 `npm run dev`；`localhost:5173` 可登录。
- [ ] `seed-demo.mjs` + `demo-report --verify` 通过；40 条未来日程未过期。

## 演示前三账号

- [ ] demo01 / demo02 / demo03 三个独立 origin 登录（5173/5174/5175 或三设备）。
- [ ] demo01 有 1 条未读好友申请通知；Map A 全员授权。
- [ ] Map B demo09 授权处于已恢复状态（UNKNOWN 场景只在演示中临时撤销）。
- [ ] 准备一条已完成的 Trace 作为备份；不依赖视频。

## 演示顺序

- [ ] 地图地点/备注/群聊。
- [ ] 忙闲共享 + Map B 临时撤销/恢复（脚本或 UI）。
- [ ] 群 @AI：固定 prompt；口播等待 25–60s；卡片出现。
- [ ] 公开方案：非发起人只看到公开站点/路线。
- [ ] 保存为活动 + 邀请 demo02/demo03 + 接受。
- [ ] 消息中心：通知/会话/好友申请/活动邀请。
- [ ] Trace：开发者视图 → 时间线/p50/p95/JSONL。

## 故障切换

- [ ] LLM 超时/配额：改用 `/assistant` 确定性工具（忙闲/路线）继续讲；或展示已完成的 S1 Trace。
- [ ] 高德失败：说明 `routeConfidence=ESTIMATED`，不是伪造成功。
- [ ] WS 断开：说明持久收件箱/消息中心可查，实时帧是尽力投递。

## 红线

- [ ] 不展示 `.env`、`application-secrets.properties`、JWT/API Key。
- [ ] 不打开他人私人日程标题/私聊原文；不展示真实坐标截图。
- [ ] 不宣称“所有地点/活动都做了 ACL”或“多实例已支持”。
- [ ] 不把 N=3 的结果说成线上 SLA。
