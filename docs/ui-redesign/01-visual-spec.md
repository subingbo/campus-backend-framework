# 阶段 1 · 视觉规范与静态样板

- 日期：2026-10-23
- 状态：**待你确认**（确认后才进入阶段 2 生产改造）
- Token 源文件：`campus-web/src/styles/tokens.css`
- 样板块：`campus-web/design-lab/`
- 截图：`docs/ui-redesign/01-samples/`

## 1. 设计意图

不是“给后台换一套皮肤”，而是把校园地图做成一个**以地图为画布、信息以轻量浮层出现**的产品：

- 地图永远是最大、最先被看到的区域。
- 面板有明确边界但不靠描边：白底、大圆角、柔和暖灰阴影、充分留白。
- 信息层级由字号、字重、颜色深浅和间距建立，不用黑框。
- 主色只出现在真正需要强调的地方：主操作、选中态、链接、关键状态。
- 移动端是地图 + BottomSheet，不是把桌面侧栏压扁。

## 2. 参考设计映射

| 参考 | 借鉴的具体特征 | 写入规范的落点 | 不照搬 |
|---|---|---|---|
| Apple Maps | 地图全铺底；控件是小面积白色浮层；移动端底部 Sheet；地图标注克制 | 地图 `inset:0`；地图控件 40/44px；移动 Sheet 22px 顶圆角；标记 11px 圆点 + 小标签 | 不复制其图标、地图配色、SF 字体 |
| Notion | 暖白底；排版建立层级；发丝分割线；内容不套卡；留白大 | `--ui-bg #F6F6F3`；正文 14/22；列表无边框；`--ui-line` 1px 发丝线 | 不复制 emoji 标注、封面式页面 |
| Linear | 列表行紧凑；hover 只变背景；状态点；少量强调色；焦点清晰 | 行高 52/64；hover `--ui-surface-2`；0 位移；`:focus-visible` 2px 环 | 不复制深色高密度界面、命令面板 |
| iOS | 44×44 触控区；Sheet 拖拽手柄；安全区；克制动效 | 移动主按钮/图标 44px；手柄 36×4；`env(safe-area-inset-bottom)`；160–220ms | 不做毛玻璃、发光、夸张弹簧 |

## 3. 固定 Token

### 3.1 颜色

| Token | 值 | 用途 |
|---|---|---|
| `--ui-bg` | `#F6F6F3` | 页面暖白底 |
| `--ui-bg-sunken` | `#EFEFEA` | 次级背景 |
| `--ui-surface` | `#FFFFFF` | 浮层面板、Sheet |
| `--ui-surface-2` | `#F3F3EF` | 输入框、hover、活动卡、气泡 |
| `--ui-surface-3` | `#EAEAE4` | 按下态、手柄、更强 hover |
| `--ui-ink` | `#21231E` | 标题/主文字（不用纯黑） |
| `--ui-ink-2` | `#5A5D55` | 正文/次文字 |
| `--ui-ink-3` | `#6A6E64` | 辅助信息（已按 AA 加深） |
| `--ui-line` | `rgba(28,30,25,.07)` | 唯一常规分割线 |
| `--ui-line-strong` | `rgba(28,30,25,.12)` | 焦点/错误兜底，不用于常规分区 |
| `--ui-accent` | `#3D6FB4` | 主操作、选中、链接 |
| `--ui-accent-hover` | `#335C99` | hover/pressed |
| `--ui-accent-tint` | `#EDF2FA` | 选中底、浅标签 |
| `--ui-danger` | `#A84A41` | 删除/错误文字 |
| `--ui-danger-tint` | `#F8EEEC` | 危险浅底 |
| `--ui-warning` | `#8A5A1F` | 提醒文字 |
| `--ui-warning-tint` | `#F7F0E3` | 提醒浅底 |
| `--ui-success` | `#447052` | 成功/已确认文字 |
| `--ui-success-tint` | `#EDF4EF` | 成功浅底 |

**实测对比度**（`node design-lab/check-contrast.mjs`，全部通过 AA）：

| 组合 | 比值 |
|---|---|
| ink / surface | 15.86:1 |
| ink-2 / surface | 6.71:1 |
| ink-3 / surface | 5.21:1 |
| ink-3 / surface-2 | 4.69:1 |
| white / accent | 5.08:1 |
| accent / accent-tint | 4.52:1 |
| danger / danger-tint | 4.95:1 |
| warning / warning-tint | 5.20:1 |
| success / success-tint | 5.10:1 |

### 3.2 字体与版式

| 角色 | 字号/行高 | 字重 |
|---|---|---|
| 页面标题 | 24/32 | 600 |
| 面板标题 | 20/28 | 600 |
| 小节标题 | 12.5–15/20–22 | 600 |
| 正文 | 14/22 | 400 |
| 详情正文 | 13.5/22 | 400 |
| 次文 | 13/20 | 400 |
| 辅助 | 11.5–12/18 | 400 |
| 按钮 | 13–14/20 | 500 |
| 数字 | tabular-nums | 500 |

- 字体栈：`'PingFang SC','Microsoft YaHei','Segoe UI',system-ui,-apple-system,sans-serif`。
- 中文正文行高 ≥1.55；长文最多 65–75 字符一行。
- 页面内容最大宽度：阅读/列表 760px；设置类 880px；顶栏内部 1280px；地图页全铺。
- 不使用纯黑 `#000`、不使用纯白正文、不用 28px 以上标题撑场面。

### 3.3 间距 / 圆角 / 阴影

- 间距：4 / 8 / 12 / 16 / 20 / 24 / 32 / 40。
- 圆角：输入 10、按钮 10、列表块 12、浮层 18、Sheet 顶 22、胶囊 999。
- 阴影：
  - `--ui-shadow-1`：小控件/顶栏胶囊
  - `--ui-shadow-2`：左列表/详情/摘要面板
  - `--ui-shadow-3`：Dialog/Sheet（仅必要时）
- 阴影颜色统一暖灰 `rgba(28,30,25,…)`，禁止蓝色投影。

### 3.4 动效与层级

- `--ui-dur 160ms`；Sheet 220ms；`cubic-bezier(.2,0,0,1)`。
- hover 只改背景/颜色/阴影；**不位移、不缩放**。
- `prefers-reduced-motion` 关闭位移动画。
- z-index：地图 0、控件 10、面板 20、顶栏 30、Popover 40、Sheet 50、Dialog 60、Toast 70。

## 4. 组件规格

### 4.1 悬浮顶栏（桌面）

- 结构：左侧白色胶囊（品牌 + 导航）+ 右侧白色账号胶囊，中间留空给地图。
- 左侧胶囊：高 52，圆角 16，`shadow-1`，内边距 0 14，品牌与导航间距 18。
- 账号胶囊：高 44，圆角 999，`shadow-1`，头像 30 + 名称 12.5。
- 导航项：高 32，内边距 0 10，圆角 8；active 背景 `surface-2` + 600；hover `surface-2`。
- 未读：16px 高 accent 胶囊，10px 白字；不压住文字。
- 移动端：独立紧凑顶栏，高 48，品牌 + 菜单 + 头像，不放大段导航。

### 4.2 浮层面板

| 面板 | 宽 | 位置 | 最大高度 |
|---|---|---|---|
| 左列表 | 300（≤1279 为 280） | top 80 / left 16 | `calc(100% - 96px)`，内容决定高度 |
| 右详情 | 380（≤1279 为 360） | top 80 / right 16 | `min(680px, calc(100% - 96px))` |
| 首页摘要 | 296 | top 80 / right 16 | 内容高度 |
| 聊天 | 360 | 右侧 / 底部 | 视口内 |
| AI 聊天 | 400 | 左侧 | 视口内 |

- 背景 `surface`，圆角 18，`shadow-2`，无边框。
- 面板内部只用发丝线（`--ui-line`）和留白分组，不做卡片套卡片。
- 内容少时面板收缩为内容高度，**不允许拉成整列侧栏**。

### 4.3 搜索与芯片

- 搜索：高 34，`surface-2` 底，圆角 10，左侧 search 图标 15，placeholder `ink-3`；focus 白底 + 1px accent 内环 + 3px `accent-ring`。
- 芯片：高 27，圆角 999，`surface-2` 底，11.5px `ink-2`；active 为 `accent-tint` 底 + `accent` 字 + 600；hover `surface-3`。
- 芯片横向滚动时隐藏滚动条，不换行。

### 4.4 列表行

- 单行高 52，双行/三行 64–74；内边距 10 12；圆角 12。
- 默认无边框、无阴影；行与行之间用 `inset 0 1px 0 --ui-line`。
- hover：背景 `surface-2`；selected：背景 `accent-tint` + 左侧 2×20px accent 短条（不是整框）。
- 名称 13/500；地址 11.5/ `ink-3` 单行省略；分类 10.5 `ink-3`；selected 名称/分类用 accent。
- 行尾 chevron 只在 hover/selected 出现。

### 4.5 按钮

| 变体 | 视觉 | 场景 |
|---|---|---|
| primary | accent 实底、白字、高 36（移动 44） | 每个视觉分区最多 1 个 |
| secondary | `surface-2` 底、无边框、ink 字 | 次操作 |
| ghost | 透明、hover `surface-2` | 取消、更多 |
| text | accent 文字、无底 | 列表内“查看全部/添加” |
| icon | 36/44 方形、`surface-2` 底 | 聊天/分享/更多 |
| danger | `danger-tint` 底 + `danger` 字 | 删除（文字项优先） |

- 圆角 10；内边距 0 12/14/16；图标与文字间距 6。
- 禁止：黑色描边按钮、两个同等重量的主按钮、所有按钮都做大色块。

### 4.6 输入与状态

- 高 36（桌面）/40（移动）；textarea 最小 88。
- `surface-2` 底、无边框、圆角 10、padding 0 12。
- hover `surface-3`；focus 白底 + 1px accent + 3px 光晕；error 1px danger + tint；disabled 透明度 .5。
- 键盘 `:focus-visible` 统一 2px accent 环 + 2px offset；地图上同样可见。

### 4.7 详情面板结构

1. 顶部：分类 pill + 关闭 icon（44 触控区）。
2. 标题 18–20/600；地址 12.5 `ink-2` + pin 图标；来源 11.5 `ink-3`。
3. 操作行：primary「导航」+ 3 个 icon（聊天/分享/更多）。
4. 描述 13.5/22 `ink-2`。
5. 发丝线。
6. 「本地点活动」小节：标题 + 数量 pill + 右侧 text action；活动卡 `surface-2`、圆角 12、无边框。
7. 发丝线。
8. 「评论」小节：姓名 11.5/600 + 时间 10.5 + 正文 12.5；评论之间发丝线。
9. 底部 composer：输入 `surface-2` + 34px（移动 44px）accent 圆形发送。

### 4.8 空状态 / 加载 / 错误

- 空状态：最小高 160；20px 线性图标放 40px `accent-tint` 圆内；标题 14/500；说明 13/20 `ink-3`，最大 260px；操作只用 text button。
- 加载：列表用 3 行 skeleton（`surface-2`，圆角 12）；按钮 loading 保留原宽，14px 旋转指示。
- 错误：面板内联一条 `danger-tint` 浅底提示，不用整页红块；地图失败时保留列表与手动添加入口。

### 4.9 覆盖层

- Dialog：仅用于必须打断的任务；宽 ≤480（复杂表单 560）；圆角 20；padding 24；遮罩 `rgba(20,22,18,.24)`；地点详情/添加地点禁止用 Dialog。
- Popover：圆角 12，`shadow-2`，padding 8；用于账号/更多。
- Toast：顶部居中 24，白底，圆角 12，13px，最大 360。
- BottomSheet：仅移动；顶部 22；手柄 36×4；最大 88dvh；遮罩 `.18`；关闭 44×44；支持下拉关闭 + ESC；底部安全区。

### 4.10 地图元素

- 底图：AMap `amap://styles/whitesmoke`（阶段 2 接入）；样例中用同明度静态底图。
- 标记：11px accent 圆点 + 2.5px 白环 + 轻阴影；标签白底 10.5px、圆角 6、无边框。
- 选中：14px 深 accent + 3px 白环 + 7px 淡 accent 光环；标签 `accent-tint` + accent 600。
- 我的位置：绿色点 + 静态淡光环（不做夸张动画）。
- 控件：40×40（移动 44×44）白底、圆角 12、`shadow-1`；定位用 accent 图标，缩放用 plus/minus。
- 地图提示：左下白色胶囊，11px，`shadow-1`；有左侧面板时水平偏移 32px。
- 保留 AMap logo/版权，不隐藏。

## 5. 页面配方

### 5.1 地图首页（桌面）

- 地图全铺；顶部左右两个胶囊；左侧 296–300px「我的地图」面板（头部 + 搜索 + 列表 + 邀请码加入）；右上 296px「当前地图」摘要（eyebrow、标题、描述、头像叠放、成员/地点、进入地图 primary、复制邀请码 ghost）。
- 选中地图的行用 `accent-tint` + 短条；地图上铺该地图的地点标记；点标记进详情。
- 地图控件右下；左下地图提示。
- 1280：保持左右面板。
- 1024：隐藏摘要，只保留左侧地图列表；地图可见宽度 ≥66%。
- 768 以下：切移动样板（顶部紧凑栏 + 搜索胶囊 + 底部 Sheet）。

### 5.2 地图工作台（桌面）

- 地图全铺；顶部左右胶囊；左侧 300px 地点面板；右侧 380px 详情面板；地图控件右下（偏移到详情面板左侧）；左下提示。
- 左面板：返回 + 群名 + 成员/地点 + 群组入口；搜索；分类芯片；地点列表；底部「添加地点 / 定位」text action。
- 右面板：见 4.7。
- 1280：左 280 / 右 360。
- 1024：左面板收起为「地点 6」胶囊；只保留详情面板；地图可见 ≥60%。
- 移动：地图全屏；顶部紧凑栏；底部「地点 6」触发条（298px peek）→ 列表 Sheet；选中地点 → 详情 Sheet（68%）；聊天 Sheet；右下定位/图层 FAB。

### 5.3 移动首页

- 地图全铺；顶部 48px 胶囊（品牌 + 菜单 + 头像）；搜索胶囊；4 个标记；右下定位/图层按钮。
- 底部 298px Sheet：手柄、标题「我的地图」+ 副标题、展开图标、2 行地图、底部「用邀请码加入」。
- 无横向滚动；Sheet 底部安全区。

### 5.4 移动地点详情

- 地图留在上方；68% Sheet；手柄、分类 + 关闭、标题、地址、来源、操作行（导航 primary + 聊天/分享/更多）、描述、活动卡、评论、底部 composer。
- Sheet 内容可滚动；composer 固定在 Sheet 底部；首屏至少完整展示活动卡。
- 375 宽：内容更紧，允许评论在滚动区；不出现横向滚动。

## 6. 状态矩阵

| 状态 | 规则 |
|---|---|
| Default | 无边框；面板白底；列表透明 |
| Hover | 背景 `surface-2`；不位移；160ms |
| Active/Selected | `accent-tint` + 2px 短条 / accent 文字；不整框描边 |
| Focus-visible | 2px accent 外环 + 2px offset；地图上也可见 |
| Disabled | 透明度 .5；cursor not-allowed；不改布局 |
| Loading | skeleton 或保留宽度 + 14px spinner |
| Empty | EmptyState：图标 + 标题 + 说明 + text action |
| Error | `danger-tint` 内联提示 + 重试 text action |
| Long text | 名称 1 行省略；描述 3 行 clamp；不撑破面板 |
| Multiple overlays | 同屏最多列表 + 详情；打开聊天自动收起列表 |
| Mobile keyboard | composer 随 `visualViewport` 上移；不被软键盘遮挡 |
| Sheet drag | 下拉关闭；高度吸附；不出现半像素抖动 |

## 7. 响应式规则

| 宽度 | 工作台 | 首页 | 非地图页 |
|---|---|---|---|
| ≥1280 | 左列表 + 右详情 | 左列表 + 右摘要 | 760px 内容列 |
| 1024–1279 | 左列表收起为胶囊；右详情 | 左列表；摘要隐藏 | 760px |
| 769–1023 | 列表/详情改 Sheet | 列表 Sheet | 760px / 单列 |
| ≤768 | 地图全屏 + BottomSheet + FAB | 地图 + 底部 Sheet | 单列；边距 16 |
| ≤389 | Sheet 最大 88dvh；边距 12；隐藏非必要文字 | 同左 | 单列；边距 12 |

验收视口：1440×900、1280×800、1024×768、768×1024、390×844、375×667。

## 8. 可访问性

- 正文/次文/辅助文字均通过 AA（见 3.1 实测表）。
- 所有可交互元素有 `:focus-visible`；地图上的标记/控件同样有焦点环。
- 移动端主要操作 ≥44×44；桌面 icon 按钮 ≥32×32。
- Sheet/Dialog：`role="dialog"`、`aria-modal`、标题关联、ESC 关闭、焦点返回触发元素。
- 图标按钮必须有 `aria-label`；纯装饰图标 `aria-hidden="true"`。
- `prefers-reduced-motion` 关闭位移动画。
- 不使用颜色作为唯一信息载体：状态同时有文字/图标。

## 9. 明确禁止

- 黑色/深灰粗边框、1px 以上的可见描边。
- 卡片套卡片、每个模块一个大方框。
- 用描边制造层次，而不是留白/字号/浅底。
- 大面积渐变、发光、玻璃拟态、彩色投影。
- 多个 primary 按钮同屏争抢。
- 把所有内容塞进卡片；能直接排版就排版。
- 为了“设计感”加无意义装饰。
- 地图被面板挤到不足一半且无收起方式。
- emoji 当图标（新代码统一 AppIcon；旧页面阶段 4 替换）。

## 10. 样板索引与截图

| 样板 | 文件 | 截图 |
|---|---|---|
| 地图首页-桌面 | `campus-web/design-lab/home-desktop.html` | `01-samples/home-desktop-{1440x900,1280x800,1024x768}.png` |
| 工作台-桌面 | `campus-web/design-lab/workspace-desktop.html` | `01-samples/workspace-desktop-{1440x900,1280x800,1024x768}.png` |
| 地图首页-移动 | `campus-web/design-lab/home-mobile.html` | `01-samples/home-mobile-{390x844,375x667}.png` |
| 地点详情-移动 | `campus-web/design-lab/place-sheet-mobile.html` | `01-samples/place-sheet-mobile-{390x844,375x667}.png` |

本地查看：`http://localhost:5173/design-lab/home-desktop.html` 等；需先启动 `campus-web` 的 Vite。

## 11. 样板已知限制

1. 样板使用静态 SVG 底图（`design-lab/map-base.svg`），用于确认 UI 与地图的明度/层级关系；生产接入 AMap `whitesmoke`，不把 SVG 打包进业务。
2. 样板数据为 `design-lab/sample-data.js`，不接后端；真实字段以阶段 0 的接口为准。
3. BottomSheet 的拖拽只是静态手柄，生产阶段 2 实现下拉关闭与高度吸附。
4. 样板未覆盖加载/空/错误/长文本的完整页面，只覆盖默认态；这些状态在规范第 6 节定义，阶段 2 逐项实现并截图。
5. 1024 宽首页隐藏摘要面板，进入地图靠点击地图行；生产可增加 hover chevron 与键盘 Enter。
6. 地图标记为静态定位；生产由 `createPlaceMarkerController` 渲染，位置随真实坐标变化。

## 12. 阶段 1 门禁（需要你确认）

请基于以下 10 条给出“通过 / 修改”：

1. 没有粗黑边框或多余描边。
2. 没有卡片套卡片；面板内只用发丝线与留白。
3. 地图在无面板时 ≥70%，单面板时 ≥55%，双面板时仍是最大区域。
4. 每个视觉分区最多 1 个 primary。
5. 左侧面板是内容高度浮层，不是整列侧栏。
6. 移动端无横向滚动；顶栏不出现滚动条。
7. 移动端主要操作 ≥44×44；Sheet 有手柄与安全区。
8. 正文/辅助文字对比度通过 AA（已实测）。
9. 焦点环在白色与地图背景上都可见。
10. 整体像校园地图产品，而不是后台模板。

**确认方式**：回复“样板通过”或指出要改的样板/区域；未确认前不进入阶段 2。
