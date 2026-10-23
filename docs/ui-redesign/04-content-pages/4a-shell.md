# 4A · 内容页壳层与公共基础

- 日期：2026-10-23
- 范围：先为消息、日程启用内容页主题；页面内容仍在 4B/4C 重构

## 改动

- 新增 `src/styles/content-pages.css`：内容页暖灰画布、`.content-page` 边距、PageHeader、EP Tabs 分段皮肤、Teleport 的 `.ui-content-overlay` / `.ui-content-popper`。
- 新增 `src/components/ui/PageHeader.vue`：`title` / `description` props，`summary` / `actions` 插槽，无数据/路由/API 逻辑。
- `main.js` 在 tokens/ui-v2 之后引入 `content-pages.css`。
- 路由：`/messages`、`/activities` 增加 `meta.uiV2` 与 `meta.mobileNav`；没有给内容页加 immersive。
- `AppShell.vue`：新增 `.app-shell--content-v2.ui-v2` 根作用域；内容页使用正常流顶栏 + 暖灰主区；移动顶栏复用品牌/四入口/账号/未读，移除横向滚动；≤389 隐藏品牌文字。
- `MessagesView.vue` / `ActivitiesView.vue` 根节点先加 `.content-page`，后续再换 PageHeader/列表/弹窗。

## 实测

| 页面 | 375 | 390 | 768 | 769 | 1024 | 1440 |
|---|---|---|---|---|---|---|
| 消息 docScroll/client | 375/375 | 390/390 | 768/768 | 769/769 | 1024/1024 | 1440/1440 |
| 日程 docScroll/client | 375/375 | 390/390 | 768/768 | 769/769 | 1024/1024 | 1440/1440 |
| 导航条目 | 4 | 4 | 4 | 4 | 4 | 4 |
| 导航 client/scroll | 221/221 | 118/118 | 462/462 | 435/435 | 690/690 | 1106/1106 |
| 页面 padding | 20/12 | 24/16 | 24/16 | 32/24 | 32/24 | 32/24 |

- 375：品牌文字隐藏，四导航图标 + 未读角标 + 头像可达。
- 390：品牌文字、四图标、头像可达；导航内部 118=118，无横向滚动。
- 控制台：12 次页面加载 0 条 error。
- build 通过；测试 50/50。

## 截图

- `4a-messages-375x667.png` / `4a-messages-1440x900.png`
- `4a-activities-375x667.png` / `4a-activities-1440x900.png`

> 4A 只验证壳层和公共基础；消息列表、日程内容仍为旧样式，4B/4C 替换后再做页面级视觉验收。

## 下一步

- 4B：消息四分区、PageHeader、四类独立 loading/error/empty、操作 pending、375 四 Tab。
- 4C1/4C2：日程四分区、768 断点、关联卡片与弹窗。
