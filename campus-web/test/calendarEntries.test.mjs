import test from 'node:test'
import assert from 'node:assert/strict'
import { calendarDayKey, groupEntriesByDay, mergeCalendarEntries } from '../src/utils/calendarEntries.js'

test('活动与个人日程按时间合并，key 带 kind 前缀', () => {
  const merged = mergeCalendarEntries(
    [
      { id: 1, title: '群活动', startTime: [2026, 10, 24, 15, 0], endTime: [2026, 10, 24, 17, 0], phase: 'OPEN' },
      { id: 2, title: '上午活动', startTime: '2026-10-24T09:00:00', endTime: '2026-10-24T10:00:00', phase: 'OPEN' }
    ],
    [
      { id: 1, title: '个人课程', startTime: [2026, 10, 24, 8, 0], endTime: [2026, 10, 24, 9, 30] },
      { id: 2, title: '个人会议', startTime: '2026-10-24T14:00:00', endTime: '2026-10-24T14:30:00' }
    ]
  )

  assert.deepEqual(merged.map((e) => e.title), ['个人课程', '上午活动', '个人会议', '群活动'])
  assert.deepEqual(merged.map((e) => e.key), ['schedule-1', 'activity-2', 'schedule-2', 'activity-1'])
  assert.equal(merged[0].kind, 'schedule')
  assert.equal(merged[3].kind, 'activity')
})

test('非法/缺失开始时间被忽略，结束时间缺失时退回开始时间', () => {
  const merged = mergeCalendarEntries(
    [{ id: 3, title: '无时间' }],
    [{ id: 4, title: '只有开始', startTime: '2026-10-24T12:00:00' }]
  )
  assert.equal(merged.length, 1)
  assert.equal(merged[0].title, '只有开始')
  assert.equal(merged[0].end.getTime(), merged[0].start.getTime())
})

test('按本地日分组并保持组内排序', () => {
  const entries = mergeCalendarEntries(
    [{ id: 1, title: 'A', startTime: [2026, 10, 24, 15, 0], endTime: [2026, 10, 24, 16, 0] }],
    [{ id: 2, title: 'B', startTime: [2026, 10, 24, 9, 0], endTime: [2026, 10, 24, 10, 0] }]
  )
  const byDay = groupEntriesByDay(entries)
  assert.deepEqual(byDay.get('2026-10-24').map((e) => e.title), ['B', 'A'])
  assert.equal(calendarDayKey([2026, 10, 24, 23, 59]), '2026-10-24')
})
