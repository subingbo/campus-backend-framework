/**
 * 活动展示公共工具：阶段/分类文案 + 时间格式化。
 * 后端 LocalDateTime 序列化为数组（[年,月,日,时,分(,秒)]）或 ISO 字符串，统一在这里兼容。
 * 注意：时间展示一律用这些函数，不要直接 new Date(数组)。
 */

export const PHASE_TEXT = { OPEN: '报名中', ONGOING: '进行中', ENDED: '已结束', CANCELLED: '已取消' }
export const PHASE_TAG = { OPEN: 'primary', ONGOING: 'success', ENDED: 'info', CANCELLED: 'danger' }
export const CATEGORY_TEXT = { MEETING: '会议', COMPETITION: '比赛', OTHER: '其他' }
export const TRANSPORT_TEXT = { WALK: '🚶 步行前往', RIDE: '🚲 骑行前往', DRIVE: '🚗 驾车前往', TAXI: '🚕 打车前往', TRANSIT: '🚌 公交前往' }

export function phaseText(phase) {
  return PHASE_TEXT[phase] || phase
}

export function phaseTagType(phase) {
  return PHASE_TAG[phase] || 'info'
}

export function categoryText(category) {
  return CATEGORY_TEXT[category] || ''
}

export function transportText(mode) {
  return TRANSPORT_TEXT[mode] || ''
}

export function toDate(value) {
  if (!value) return null
  if (Array.isArray(value)) {
    return new Date(value[0], value[1] - 1, value[2] ?? 0, value[3] ?? 0, value[4] ?? 0, value[5] ?? 0)
  }
  return new Date(value)
}

export function pad2(n) {
  return String(n).padStart(2, '0')
}

/** 月-日 时:分（活动卡片等窄场景） */
export function fmt(value) {
  const d = toDate(value)
  if (!d || Number.isNaN(d.getTime())) return ''
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

export function fmtRange(a, b) {
  return `${fmt(a)} ~ ${fmt(b)}`
}

export function fmtClock(value) {
  const d = toDate(value)
  if (!d || Number.isNaN(d.getTime())) return ''
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** 转 el-date-picker 可提交的本地时间字符串（后端校验用绝对时间） */
export function toLocalIso(date) {
  const d = toDate(date)
  if (!d || Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}
