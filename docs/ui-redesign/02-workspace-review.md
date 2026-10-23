# 阶段 2 · 地图工作台改造与视觉验收

- 日期：2026-10-23
- 账号：`demo01 / demo1234`，Map A `[DEMO] 周末去哪儿`（id=51）
- 环境：后端 8080、前端 `http://localhost:5173`、Chromium IAB
- 参照：`01-visual-spec.md`、`00-baseline/audit.md`

## 0. 结论先行

- **视觉验收：通过（工作台主流程）**。P0/P1 视觉问题已清零；地图成为唯一主画布，左右面板为内容高度浮层，1024 只保留一个侧面板，移动端为地图 + Sheet。
- **功能回归：未全绿**。18 项中 11 项完整实测通过，4 项完成 UI 路径但未提交真实数据，3 项因权限/外部导航/环境限制未执行；另有 1 项环境级问题：Element Plus Dialog 的关闭过渡在 IAB 中会卡在 leave 状态（真实 Chrome 未复核）。详见第 7 节。
- `npm run build` 通过；`npm run test` **50/50** 通过。

## 1. 改动范围

### 新增
| 文件 | 作用 |
|---|---|
| `src/components/ui/AppIcon.vue` | 内联 Lucide 风格图标，替代 emoji，无新增依赖 |
| `src/components/ui/FloatingPanel.vue` | 统一悬浮面板（header / body / footer） |
| `src/components/ui/SearchBar.vue` | 胶囊搜索，支持 clear / submit / autofocus |
| `src/components/ui/MapActionButton.vue` | 地图控件按钮，显式 `click` 事件 |
| `src/components/ui/PlaceListItem.vue` | 无边框地点行，选中浅底 + 短条 |
| `src/components/ui/SectionTitle.vue` | 小节标题 + 计数 + 右侧 text action |
| `src/components/ui/EmptyState.vue` | 统一空状态 |
| `src/components/ui/DetailDrawer.vue` | 右侧浮动详情容器 |
| `src/components/ui/BottomSheet.vue` | 移动端 Sheet：手柄、拖拽、ESC、遮罩、焦点管理 |
| `src/components/place/PlaceDetailPanel.vue` | 桌面抽屉 / 移动 Sheet 共用的地点详情 |
| `src/components/place/AddPlaceForm.vue` | 桌面抽屉 / 移动 Sheet 共用的添加地点表单 |
| `src/components/place/GroupInfoPanel.vue` | 桌面群组浮层 / 移动群组 Sheet 共用 |
| `src/styles/ui-v2.css` | 工作台作用域样式（`.ui-v2` / `.ui-dialog` / `.ui-sheet`） |

### 修改
| 文件 | 改动 |
|---|---|
| `src/views/MapWorkspaceView.vue` | 模板/样式重构：地图全铺、左列表、右详情、选点、Sheet、群组、聊天、删除确认；业务函数保留 |
| `src/components/AppShell.vue` | immersive 路由（`/maps/:id`）悬浮胶囊顶栏；移动端 nav 滚动条隐藏 |
| `src/router/index.js` | `maps/:id` 增加 `meta.immersive` |
| `src/main.js` | 引入 `tokens.css`、`ui-v2.css` |
| `src/composables/useAmap.js` | `mapStyle`（默认 `fresh`）+ `controls` 开关 |
| `src/assets/map-markers.css` | 新标记、选中态、我的位置、选点预览 |
| `src/components/ChatPanel.vue` | AGENT 消息不再误判为“我的”；🤖 换 `AppIcon` |
| `src/components/ActivityFormDialog.vue` | 加 `ui-dialog` 皮肤类 |

### 明确保留
所有 API 契约、WS 订阅、`?place=`/`?chat=1` 深链、OWNER/EDITOR/VIEWER 权限、重复地点检测、评论、分享、高德导航、活动报名/签到/邀请、AI 聊天与计划逻辑均未改。未改后端、数据库、演示数据。

## 2. 最终布局

### 桌面 ≥1280
- 地图 `inset:0` 全铺；顶栏拆成左侧品牌/导航胶囊 + 右侧账号胶囊。
- 左侧地点面板 300px，内容高度（最大 `calc(100% - 96px)`）；可收起为群组胶囊。
- 右侧详情抽屉 380px，最大高度 680px；底部 composer 固定。
- 地图控件右下，详情打开时左移到面板左侧；地图提示左下，列表打开时右移。
- 同屏允许“左列表 + 右详情”；聊天打开时左列表收起，群组胶囊出现。

### 1024–1279
- 左列表收起，只显示群组胶囊（群名 / 地点 6 / 聊天）。
- 打开列表或详情时只保留一个侧面板；选点后列表让位给详情。

### 移动 ≤768
- 全局顶栏隐藏，使用工作台自己的 48px 胶囊（返回 / 群名 / 搜索 / 聊天）。
- 地图全屏；底部 52px“地点 · N”触发条 + 定位/添加 FAB。
- 列表、详情、添加、群组、聊天全部为 BottomSheet；详情 72dvh，添加/聊天 88dvh。
- 触发条底部留 30px，AMap logo 与版权可见（实测 logo y=823，触发条底 814）。

## 3. 截图索引

目录：`docs/ui-redesign/02-workspace/`

| 截图 | 状态 |
|---|---|
| `workspace-default-1440x900.png` | 桌面默认：地图 + 左列表 + 控件 |
| `workspace-detail-1440x900.png` | 桌面地点详情（活动/评论/composer） |
| `workspace-add-1440x900.png` | 桌面地图选点后的添加抽屉 |
| `workspace-empty-1440x900.png` | 搜索无结果的 EmptyState |
| `workspace-edit-1440x900.png` | 编辑地点 Dialog |
| `workspace-remove-1440x900.png` | 自定义“从地图移除”确认 |
| `workspace-activity-form-1440x900.png` | 从地点安排活动 Dialog |
| `workspace-group-1440x900.png` | 桌面群组浮层（列表已让位） |
| `workspace-chat-1440x900.png` | 桌面聊天（左列表已收起，群组胶囊） |
| `workspace-1024x768.png` | 1024 默认（只保留群组胶囊） |
| `workspace-panel-1024x768.png` | 1024 仅列表展开 |
| `workspace-detail-1024x768.png` | 1024 仅详情展开 |
| `workspace-mobile-390x844.png` | 移动默认：地图 + 触发条 + FAB |
| `workspace-mobile-list-390x844.png` | 移动地点列表 Sheet |
| `workspace-mobile-detail-390x844.png` | 移动地点详情 Sheet |
| `workspace-mobile-add-390x844.png` | 移动添加地点 Sheet |
| `workspace-mobile-chat-390x844.png` | 移动聊天 Sheet |
| `workspace-mobile-group-390x844.png` | 移动群组 Sheet |

## 4. 第一轮复审：结构与视觉

| 问题 | 处理 |
|---|---|
| AMap `whitesmoke` 底图过淡，道路/建筑几乎不可见 | 对比 `whitesmoke`/`light`/`normal`/`fresh`，最终 `fresh`：有完整标注、自然色、UI 仍清晰 |
| 左侧面板被 `bottom:16px` 拉成整列侧栏 | 改内容高度 `max-height: calc(100% - 96px)`；2 张地图时收缩，6 个地点时到 691px |
| 详情抽屉全高 844px，像第二根侧栏 | `max-height: min(680px, calc(100% - 96px))`；地图下方仍可见 |
| 群组浮层与左列表叠压，底部露出左面板 footer | 打开群组时关闭列表并隐藏群组胶囊；关闭后恢复原列表（`restorePanelOnGroupClose`） |
| 聊天打开后左列表仍在，地图被两侧夹住 | `openChat` / `openPlaceChat` 收起列表；胶囊 300ms 防穿透，避免同一次点击又把列表打开 |
| 移动端触发条遮住 AMap logo | 触发条底距 30px，FAB 上移；实测 logo/copyright 可见 |
| 筛选芯片被裁切 | 面板与 Sheet 芯片改为换行（`flex-wrap: wrap`） |
| 详情操作行双倍左右内边距（head + actions 各 18px） | actions 改 `padding: 10px 0 0`，水平由 head 统一 |
| AI 消息被当成“我的消息”整块变蓝 | `ChatPanel` 的 `mine` 条件排除 `AGENT`；v2 作用域内 AI 气泡改 `accent-tint` |
| 聊天输入区被长消息挤出面板 | 补 `.chat-panel-ui__body` 的 `flex:1/min-height:0`；ChatPanel 在面板内正常收缩 |
| 移动聊天发送按钮掉到下一行 | 移动 composer 改 grid：AI 按钮独占一行，textarea 与 44px 发送按钮同一行 |
| 移动 Sheet 背景透明 / 关闭后 DOM 不卸载 | 改内联绝对定位 + z-index 22；关闭路径改 `:css="false"` + `v-if`，打开保留 200ms 上浮动画 |
| `MapActionButton` 点击不触发 | 组件显式 `defineEmits(['click'])` + 根按钮 `@click`；实测 zoom 16.08→16.13 且 handler 计数增加 |
| 默认 `ElMessageBox` 与 v2 风格不一致且取消不收起 | 改为工作台自己的 `ui-dialog` 移除确认；取消/移除按钮为 ghost/danger 浅底 |

## 5. 第二轮复审：交互与场景

- **默认 / hover / active / focus**：列表 hover 只变背景，选中为 `accent-tint` + 2px 短条；按钮/链接有统一 `:focus-visible`；`prefers-reduced-motion` 关闭位移动画。
- **空状态**：搜索无结果显示图标 + “没有匹配的地点” + “清除筛选”；无地点显示“还没有地点”。
- **长文本**：AI 聊天 50 条消息（含长规划推理）滚动正常；地点名称/地址单行省略；详情描述 3 行内自然换行。
- **多浮层**：桌面列表 + 详情可并存；聊天打开收起列表；群组打开收起其他；1024 强制单侧面板；移动端所有 Sheet 互斥。
- **移动端**：列表→详情切换、详情→聊天、群组 Sheet、添加 Sheet 均验证；ESC 以合成 keydown 验证关闭；遮罩点击以 DOM handler 验证；拖拽以合成 PointerEvent 验证（>96px 关闭）。
- **地图**：zoom 按钮生效；标记选中高亮；定位按钮存在；深链 `?place=113` 打开“校车站”详情，`?chat=1` 打开 50 条消息的地图群聊。
- **表单**：添加表单地图选点、坐标回填、分类 chip、手动坐标折叠均渲染；编辑/活动/移除确认均打开。
- **无横向滚动**：390 宽 `documentElement.scrollWidth = 390`。

## 6. 量化结果

| 指标 | 结果 |
|---|---|
| 1440 左列表展开、无详情 | 地图可见面积约 **82%**（1,296,000 − 300×691 − 顶栏胶囊） |
| 1440 左列表 + 右详情 | 地图可见面积约 **62%** |
| 1024 仅右详情（360px） | 地图可见面积约 **65%** |
| 390 宽横向滚动 | 无；`scrollWidth = 390` |
| 移动顶栏 | 48px；返回/搜索/聊天均 44×44 |
| 移动添加 FAB / 定位 FAB | 52×52 / 48×48 |
| 移动 Sheet 关闭/发送 | 44×44；发送按钮 52×44 |
| 触发条与 AMap 版权 | 触发条底 814；logo y=823、copyright y=826，未遮挡 |
| 对比度 | `tokens.css` 11 组全部 AA（见阶段 1） |
| `npm run build` | 通过（11s 左右；主包 1.37MB / gzip 453kB，构建警告保留） |
| `npm run test` | **50/50 通过** |

## 7. 18 项回归明细

| # | 项目 | 结果 | 说明 |
|---|---|---|---|
| 1 | 进入 / 加载 | PASS | 加载覆盖层、地图初始化、6 个地点 |
| 2 | 搜索 | PASS | 过滤、空态、清除筛选 |
| 3 | 分类 | PASS | 全部/分类 chips，计数联动 |
| 4 | 选中列表/标记 | PASS | 高亮、`focusPlace`、列表滚动到选中行 |
| 5 | 详情 | PASS | 桌面抽屉 / 移动 Sheet；活动、评论、composer |
| 6 | 编辑 | **部分** | 打开、字段渲染、取消路径 OK；**IAB 中 EP Dialog 关闭过渡会卡住** |
| 7 | 移除 | **部分** | 自定义确认打开/文案/buttons OK；**IAB 中 EP Dialog 关闭过渡会卡住** |
| 8 | 评论 | **部分** | 输入框/发送按钮渲染；未提交（保护演示数据） |
| 9 | 分享 | **部分** | 分享菜单列出另一张地图；未真正分享 |
| 10 | 导航 | 未执行 | 会打开高德外部页；代码未改 |
| 11 | 聊天两 Tab | PASS | 地图群聊 50 条；地点聊天切换到校车站；详情自动收起 |
| 12 | 添加 | **部分** | 地图选点、坐标回填、表单渲染 OK；未提交/未走重复检测 |
| 13 | 定位 | 未执行 | 浏览器权限；代码未改 |
| 14 | 缩放 | PASS | 按钮 handler 触发，zoom 16.08→16.13（动画中） |
| 15 | 权限只读 | 代码审查 | `canEdit` 仍为 OWNER/EDITOR；未用 VIEWER 实机跑 |
| 16 | 地图失败 | 代码审查 | 错误卡 + 手动添加 + 重试；未模拟真实失败 |
| 17 | 深链 | PASS | `?place=113`、`?chat=1` |
| 18 | 空态/长文本/多浮层 | PASS | 见第 5 节 |

## 8. 已知限制与未验证

1. **EP Dialog 关闭（最高优先级）**：编辑、活动表单、自定义移除确认都使用 `el-dialog`；在 IAB 中关闭后 overlay 停在 `dialog-fade-enter-from dialog-fade-leave-active`，DOM 不卸载。已给 `.ui-dialog` / overlay 关闭过渡设 `none`，仍复现；判断为 AMap iframe + IAB 的过渡/合成问题，**真实 Chrome 未复核**。若真实 Chrome 同样复现，建议把工作台三个弹窗改为与 Sheet 相同的 `v-if` + 无过渡外壳。
2. **移动详情 Sheet 遮挡选中标记**：详情 Sheet 72dvh，选中标记在地图中心（y≈442，Sheet top≈270），会被盖住。尝试用 `setCenter` 偏移，但 AMap 在该状态下把中心位移钳制在 ~54m；已回退，避免更差的跳动。关闭/下拉 Sheet 后可看到选中标记。
3. **共享组件仍是旧视觉**：`GroupPlanCard` 的蓝色/emoji 计划卡、`ActivityFormDialog` 内部部分描边、`ChatPanel` 的“问 AI 规划”按钮细节，留到阶段 4。
4. **IAB 截图合成不稳定**：部分截图出现底图未加载、图层透明/旧帧；终稿的聊天/群组截图已重抓到正确状态，但个别早前截图可能保留轻微旧帧。真实 Chrome 需复核。
5. **拖拽/ESC**：拖拽用合成 PointerEvent 验证（>96px 关闭）；ESC 用合成 keydown 验证；真实触摸/键盘焦点未实测。
6. **未提交类操作**：评论发送、地点提交、重复检测提交、分享提交、活动创建/报名均未执行，避免污染演示数据；接口与业务函数未改。
7. **权限/地图失败**：VIEWER 账号与地图加载失败未实机跑；代码路径保留。

## 9. 阶段 2 判定

- **视觉：工作台主流程通过**。P0/P1 清零；地图优先、轻量悬浮、无粗描边、无卡片套卡片、1024 单面板、移动 Sheet 与 44px 触控均已落地。
- **功能：不宣称 18/18**。第 7 节已逐项标注；EP Dialog 关闭是唯一需要在真实浏览器复核的 P1 风险。
- 建议：先在真实 Chrome 复核编辑/活动/移除三个弹窗的关闭；若正常，阶段 2 可判定完成；若同样卡住，下一步把这几个弹窗换成无过渡的 `v-if` 外壳，再进入阶段 3。
