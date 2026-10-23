# UI v2 设计系统（阶段 6 冻结）

- 日期：2026-10-23
- 状态：阶段 3/4 已迁移页面按此为准；TraceCenter 等未迁移页面保留旧兼容变量
- 唯一品牌源：`campus-web/src/styles/tokens.css` + `ui-v2.css` + `content-pages.css`

## 1. Token

- 颜色：`--ui-bg` 暖灰页面底、`--ui-surface` 白面、`--ui-surface-2/3` 浅层、`--ui-ink/ink-2/ink-3` 文字、`--ui-accent` 雾蓝、`--ui-accent-tint` 浅雾蓝、`ui-danger/warning/success` 及 tint。
- 圆角：`--ui-radius-sm/md/lg/xl`（8/10/12/18–22 的使用场景见下）。
- 阴影：`--ui-shadow-1` 轻浮层卡片、`--ui-shadow-2` 浮层、`--ui-shadow-3` 弹窗；普通列表行无阴影。
- 字体：`--ui-text-*` 与正文/辅助字号；中文系统字体。
- 动效：`--ui-dur`、`--ui-ease`；减少动态效果时保留极短 opacity 过渡，不做大位移。
- 触控：`--ui-tap` 44px；移动主要按钮/输入/关闭至少 44。

旧 `--app-*` / `--brand-*` / `--el-color-primary` 已在 `theme.css` 指向 UI v2（兼容未迁移页面）；不要在新代码中使用旧变量。

## 2. 页面骨架

### 内容页
- 路由 `meta.uiV2: true`（+ `mobileNav: true`）→ AppShell 根 `.app-shell--content-v2.ui-v2`。
- 页面根 `.page.content-page`；最大宽 760px；padding：≤389 `20/12`、390–768 `24/16`、≥769 `32/24`。
- 页头统一 `PageHeader`：`title`、`description`、`summary`/`actions` 插槽；不用旧 hero。
- 列表：连续行 + 1px `--ui-line`，无行阴影；行内操作尾部或移动端整行右对齐。

### 地图页
- 首页/助手/工作台保留全屏地图；浮层用 `FloatingPanel` / 绝对定位聊天 / `BottomSheet`。
- 地图本身 `fresh`；地图内部 z-index 收进 `.home-map` 或 `.map-col` 的 z 0 栈，浮层在其上。
- 地图页移动端仍用 56px mobileNav + 触发条/Sheet，不改成内容列。

### 登录页
- 不在 AppShell 内，根节点自己加 `ui-v2`；暖灰底 + 品牌 mark + 白色轻表单卡；不使用内容页 `PageHeader`。

## 3. 组件清单

- `AppIcon`：线性图标；操作不再用 emoji。
- `PageHeader`：内容页标题/说明/摘要/操作。
- `SectionTitle`：分组标题 + 计数/操作。
- `EmptyState`：空态；`compact` 用于区块内。
- `SearchBar`：搜索输入；`@submit` 保留回车。
- `ui-btn`：primary/secondary/ghost/danger/sm；每区最多一个 primary。
- `ui-input` / `ui-textarea` / `ui-select`；EP 输入/选择在 `.ui-v2` 下自动套 v2。
- `ui-chip`：分段/范围；`is-active`。
- `ui-pill` / `ui-count`：状态/计数；计数不改变服务端 summary。
- `ui-text-action`：行内次要操作。
- `FloatingPanel` / `BottomSheet`：仅地图页/需要浮层时使用。
- Dialog：`class="ui-dialog"` + `modal-class="ui-content-overlay"` + `append-to-body`；日期/下拉 `popper-class="ui-content-popper"`。
- 确认框仍用 Element Plus MessageBox（默认皮肤）；不新造确认系统。

## 4. 页面配方

| 页面 | 配方 |
|---|---|
| 首页 | 全屏地图 + 左列表 300 + 摘要 296 / 1100–1439 胶囊 / ≤768 触发条 + 68dvh Sheet |
| AI 助手 | 地图全铺 + 桌面左 400 浮层 / 769–1099 380 / ≤768 62dvh 面板 + 收起 + 键盘可见区 |
| 工作台 | 地图 + 左 300 地点列表 + 右详情抽屉；compact 用 group pill |
| 消息 | PageHeader + EP Tabs（四段）+ 通知范围 chips + 连续行；四区独立 loading/error/empty |
| 日程 | PageHeader + 四 Tabs + 桌面月历/≤768 日期选择 + 当日行；忙闲共享轻块；活动卡发丝线 |
| 好友/聊天 | 内容列 + 连续好友行；私聊弹窗内 `.ui-v2` 包 ChatPanel；群聊/地点聊复用 ChatPanel |
| 方案 | content-page + PageHeader（返回群聊）+ 地图卡 + GroupPlanCard；真实/示意线图例常驻 |
| 登录 | 独立 ui-v2 分栏/单列 + 轻表单卡 |

## 5. 响应式与可访问性

- 断点：389 / 390 / 768 / 769 / 1099 / 1100 / 1439 / 1440；内容页与移动导航统一 768。
- 移动导航：品牌 + 4 入口 + 账号；≤600 只留图标，≤389 品牌文字隐藏；`nav.clientWidth === scrollWidth`。
- 触控：移动主要操作 ≥44；表单 footer sticky；日期时间范围在窄屏上下两行。
- 焦点：`:focus-visible` 雾蓝环；Dialog 保留 EP 焦点管理。
- 对比度：正文 ink / 辅助 ink-2 / 弱化 ink-3；状态同时有文字。
- 减少动态：AI reveal 保留短 fade；不依赖大位移。

## 6. Teleport 与层级

- 所有 body 浮层显式带 `ui-dialog` / `ui-content-overlay` / `ui-content-popper`；不能只依赖页面祖先 `.ui-v2`。
- 地图页 z：地图 0、地图内浮窗 200、聊天 20、Sheet 22、顶栏 30；地图内部 logo 收在 z 0 栈。
- 弹窗：EP overlay 默认之上；移动宽度由 theme.css 统一 `calc(100vw - 24px)`。

## 7. 已知例外 / 待办

- `TraceCenterView` 仍主要使用旧 EP 主题（已通过兼容变量变雾蓝，但未按内容页重构）。
- `ElMessageBox` 确认框未套 v2 Dialog 皮肤。
- `ChatPanel` 的旧 scoped 色已 token 化；地图聊天与私聊共用同一套。
- IAB 截图面不稳定；阶段 3/4 视觉证据部分来自 headless Chrome 真实渲染。
- iOS/Android 真机键盘、刘海安全区、200% 字体仍需设备验证。
- 阶段 5 的未重放任务链：消息邀请接受、AI 草稿保存、聊天发送未读消退。

## 8. 禁止

- 新页面不再引入旧 hero、蓝灰阴影行卡、渐变/玻璃、卡片套卡片。
- 不新建第三套 token/组件；不把颜色写成镜像测试。
- 不用 `immersive` 套内容页；不隐藏消息四分区；不把接口失败显示成空态。
- 不用 emoji 作操作图标；不把普通列表做成大面积浮动卡。
