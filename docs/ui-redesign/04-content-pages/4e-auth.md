# 4E · 登录与注册

- 日期：2026-10-23
- 范围：`/login` 登录/注册；不改校验、会话、redirect 语义
- 证据：1440 / 390

## 改动

- 登录页根节点启用 `ui-v2`。
- 去掉旧蓝色渐变 hero；改为暖灰背景 + 品牌 mark + 30/34px 标题 + 辅助文字，和内容页视觉一致。
- 右侧表单改为白色轻卡片（`--ui-surface`、`--ui-radius-xl`、`shadow-1`）；登录/注册分段用 surface-2 + 选中白底/ accent 文字，保留 `role=tablist`，补 `role=tab`/`aria-selected`。
- 表单继续使用 `el-form` + `el-input` 的 v2 皮肤；密码增加 `show-password` 可见性切换。
- 内容手机断点从 900px 保持单列；登录页使用 `min-height:100dvh`，面板改为整宽轻卡，避免小屏两侧留白。
- 保留：登录/注册顺序、注册后自动登录、昵称兜底、`beginSession`、`safeRedirect`、路由守卫、401 刷新链路；没有新增前端校验规则。

## 实测

| 项目 | 结果 |
|---|---|
| 1440 | 左品牌/卖点 + 右表单卡片；无渐变；按钮/输入一致 |
| 390 | 单列；品牌/标题 + 表单卡；无横向滚动 |
| 登录提交 | 填入 demo01/demo1234 → 跳转 `/`，accessToken 写入 |
| build / test | build 通过；50/50 |

## 截图

- `4e-login-1440x900.png`
- `4e-login-390x844.png`

## 未做/边界

- 注册提交未实际执行，避免写入演示账号数据；校验/注册后自动登录逻辑未改。
- 非法 redirect、会话过期/401 刷新链路未重新触发；保留 `safeRedirect`/`authSession` 原实现，阶段 5 任务链回归。
- 真机键盘、刘海安全区仍待阶段 5。
