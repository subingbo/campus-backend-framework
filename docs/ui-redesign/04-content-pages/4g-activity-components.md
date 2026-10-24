# 4G · 活动页组件级对齐

- 日期：2026-10-24
- 范围：活动页、活动卡、详情/地图选择/表单/邀请弹窗的可见操作
- 原则：日历、日期选择、Tabs、开关保留 Element Plus 语义；操作按钮统一原生 `ui-btn` / `ui-text-action`

## 改动

- 活动页空态、「我的日程」「我创建的」新建入口、当日列表详情/编辑/删除、邀请接受/拒绝、活动详情 footer、多地图选择 footer 全部换原生 `ui-btn`。
- `ActivityEntryCard`：报名/取消报名/签到/成员/编辑/取消活动/发邀请换原生按钮；按动作加 `pending`、`:disabled`、`:aria-busy`、处理中文本；失败恢复。
- 个人安排删除加行级 pending；分享开关沿用 `shareSaving`。
- `ActivityFormDialog`、`ScheduleFormDialog`、`InviteFriendsDialog` footer 换原生按钮；不再依赖 EP `loading`，用 `submitting/saving/removing/sending` 禁用与处理中文本；`ActivityFormDialog` 的站点上移/下移/删除/添加也换原生。
- 删除统一 `ui-btn--danger`；编辑/次要操作用 secondary/text；分享区从白色描边卡改为 `surface-2` 轻块。
- 日历头按钮用 `.activity-calendar :deep(.el-button)` 限定 32px 高和 v2 皮肤；日期浮层带 `ui-content-popper`；没有全局改 `.el-button`。
- 移动端：内容页动作 `ui-btn`/`ui-text-action` 命中区 44；活动卡 actions 44；375 无横向滚动。

## 实测

| 项目 | 结果 |
|---|---|
| 报名 → 取消报名 → 恢复报名 | 取消报名后「报名」、成员 3；重新报名后「取消报名」、成员 4 |
| 分享失败恢复 | 阻断 `/api/schedules/share` → 行内错误 + 重试；解除后重试恢复 |
| 临时个人安排 | API 创建 id=110 → 页面出现 → 原生删除 + MessageBox 确认 → 消失 |
| 详情 footer | 去现场/高德导航/邀请好友均为 `ui-btn`，36px |
| 个人安排弹窗 | 取消/保存 `ui-btn` 36px |
| 新建活动弹窗 | 取消/创建 `ui-btn` 36px |
| 邀请弹窗 | 取消/发送邀请 `ui-btn` 36px |
| 工作台活动卡 | 实际打开东区图书馆，成员/编辑按钮白底可见，无回退 |
| 375 | `scrollWidth=375`，无横向滚动 |
| build / test | build 通过；50/50（实际执行记录） |

## 截图

- `4g-activities-1440x900.png`：真实活动、个人安排、共享行
- `4g-activities-390x844.png`
- `4g-workspace-card-1440x900.png`：实际地点活动卡

## 未重放

- 签到：当前活动不在签到窗口，页面显示「开始前 15 分钟…」提示；未构造可签到活动。
- 新建活动/编辑/取消、邀请实际发送：会留下测试活动或 pending 邀请，未实际提交；表单与 footer 已验证，沿用原 API/权限。
- 失败注入只覆盖分享；报名/删除的失败恢复靠 pending/finally 代码与成功路径验证。
- 真机键盘/安全区仍待阶段 5。

## 结论

- 活动页的可见操作已从 EP 小按钮统一到与消息/好友页相同的 `ui-btn`/文本按钮；日历/日期/Tabs/开关保留 EP 语义但限定皮肤。
- 工作台共用活动卡无明显回退；本轮不再扩成全站重构。
