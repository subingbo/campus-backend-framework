# 3E · AI 助手移动（62dvh 面板 + 收起/展开 + 键盘）

- 日期：2026-10-23
- 范围：`/assistant` ≤768；桌面 3D 不回退
- 账号：demo01；会话 chat=197

## 改动

- `AssistantView.vue`
  - 移动端聊天改为底部面板：`height:62dvh`、顶部 22px 圆角、44px 手柄（视觉仍为 36×4 条）、`chevron` 收起时旋转 180°。
  - 收起态 72px：只保留手柄 +「AI 规划助手」标题；会话/参考地图/新对话/输入区隐藏；地图完整可操作。
  - 键盘：
    - `interactive-widget=resizes-content`（`index.html`）；
    - `visualViewport` 的 resize/scroll + `window.resize` 计算 inset，80px 阈值过滤地址栏抖动；
    - 键盘 overlay 时面板 `bottom=inset`、`height=calc(100% - inset)`；布局视口被压缩（innerHeight < 基线 75%）时面板填满主区；
    - 键盘态隐藏会话/参考地图/草稿入口/出行方式，优先保证消息、输入框、发送按钮可见。
  - 中文输入：`@keydown.enter.exact="onComposerKeydown"`，`isComposing`/`keyCode 229` 时不发送、不 preventDefault。
  - 地理浮窗：移动端点芯片/标记先收起聊天，再按 `mapRect` 相对坐标定位；没有足够空间时关闭浮窗，不塞进聊天下方。
- 未改：流式、会话、工具、草稿状态机、保存/修订、地图业务、桌面布局。

## 实测

| 状态 | 结果 |
|---|---|
| 390×844 展开 | 面板 390×523（62dvh），地图可见 265px；输入/发送完整；map controls 在面板上方 |
| 375×667 收起 | 面板 375×72；标题不裁；地图可操作；无横向滚动 |
| 375×367 键盘模拟 | 面板填满主区（y=56, h=311）；composer 在底部（y=274, h=93）；输入框与发送按钮完整可见 |
| IME 回车 | `isComposing=true` → 消息数 4→4，`defaultPrevented=false`，不发送 |
| 芯片浮窗 | 点「东区图书馆」→ 聊天收起为 72；浮窗 280×184 在 y=218，位于面板上方 |
| 构建 / 测试 | build 通过；`npm run test` 50/50 |

## 截图

- `3e-assistant-390x844.png`：展开态。
- `3e-assistant-collapsed-375x667.png`：收起态。
- `3e-assistant-keyboard-375x367.png`：输入聚焦 + 键盘压缩视口。
- `3e-assistant-geo-390x844.png`：芯片浮窗 + 自动收起聊天。

> IAB 截图面在本轮继续超时；截图由本机 Chrome 无头实例经 CDP 真实渲染（`--no-proxy-server`），地图、路线、聊天均出现。375×367 是模拟键盘后的可见视口，不是 375×667 全屏。

## 剩余项（不阻塞本轮）

- iOS Safari / Android 真机键盘与刘海安全区未验证；本轮**没有**加 `viewport-fit=cover`，`env(safe-area-inset-bottom)` 在 iOS 上可能为 0，首页 Sheet 还有一处重复 env padding（3C 遗留）。
- 200% 字体、极端长文本、地图失败/流式异常未重触发。
- 阶段 4–6（全站统一）未开始。

## 视觉判定

- **移动助手：通过（真机键盘除外）**。62dvh 面板、收起态、键盘可见区、IME、芯片浮窗避让均以真实截图/DOM 验证；无横向滚动，输入/发送不被遮挡。
- 桌面 3D 证据保持有效。
