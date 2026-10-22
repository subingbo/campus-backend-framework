import { toDate } from './format.js'

/** 本地日 key（YYYY-MM-DD），与 el-calendar 的 data.day 对齐 */
export function calendarDayKey(value) {
  const date = toDate(value)
  if (!date || Number.isNaN(date.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function normalize(kind, item) {
  const start = toDate(item.startTime)
  const end = toDate(item.endTime)
  if (!start || Number.isNaN(start.getTime())) return null
  return {
    kind,
    key: `${kind}-${item.id}`,
    id: item.id,
    title: item.title || '',
    startTime: item.startTime,
    endTime: item.endTime,
    start,
    end: end && !Number.isNaN(end.getTime()) ? end : start,
    phase: kind === 'activity' ? item.phase : null,
    urgent: kind === 'activity' ? !!item.urgent : false,
    raw: item
  }
}

/**
 * 活动与个人日程统一排序：先按开始时间，再按 key 稳定排序。
 * key 带 kind 前缀，避免活动/日程数字 id 相同导致 Vue key 冲突。
 */
export function mergeCalendarEntries(activities = [], schedules = []) {
  const entries = []
  for (const activity of activities) {
    const entry = normalize('activity', activity)
    if (entry) entries.push(entry)
  }
  for (const schedule of schedules) {
    const entry = normalize('schedule', schedule)
    if (entry) entries.push(entry)
  }
  return entries.sort((left, right) => {
    const diff = left.start.getTime() - right.start.getTime()
    return diff !== 0 ? diff : left.key.localeCompare(right.key)
  })
}

/** 按本地日期分组，组内保持 mergeCalendarEntries 的顺序 */
export function groupEntriesByDay(entries = []) {
  const byDay = new Map()
  for (const entry of entries) {
    const key = calendarDayKey(entry.start)
    if (!key) continue
    if (!byDay.has(key)) byDay.set(key, [])
    byDay.get(key).push(entry)
  }
  return byDay
}
