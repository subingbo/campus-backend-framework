import test from 'node:test'
import assert from 'node:assert/strict'
import { buildPublicRoutePairs, hiddenStops, publicMapStops } from '../src/utils/groupPlanMap.js'

test('相邻公开站点生成路线，隐藏站点不画点', () => {
  const stops = [
    { seq: 1, name: 'A', longitude: 113.1, latitude: 23.1, locationVisible: true },
    { seq: 2, name: 'B', longitude: 113.2, latitude: 23.2, locationVisible: false },
    { seq: 3, name: 'C', longitude: 113.3, latitude: 23.3, locationVisible: true }
  ]
  assert.deepEqual(publicMapStops(stops).map((s) => s.name), ['A', 'C'])
  // A 公开、B 私有、C 公开 → 不能压缩成 A→C
  assert.deepEqual(buildPublicRoutePairs(stops), [])
  assert.deepEqual(hiddenStops(stops).map((s) => s.name), ['B'])
})

test('连续三个公开站点生成两段相邻路线', () => {
  const stops = [
    { seq: 1, name: 'A', longitude: 113.1, latitude: 23.1, locationVisible: true, transportMode: 'WALK' },
    { seq: 2, name: 'B', longitude: 113.2, latitude: 23.2, locationVisible: true, transportMode: 'RIDE' },
    { seq: 3, name: 'C', longitude: 113.3, latitude: 23.3, locationVisible: true, transportMode: 'DRIVE' }
  ]
  const pairs = buildPublicRoutePairs(stops)
  assert.equal(pairs.length, 2)
  assert.deepEqual(pairs.map((p) => [p.from.name, p.to.name, p.mode]), [['A', 'B', 'RIDE'], ['B', 'C', 'DRIVE']])
})

test('缺坐标或 locationVisible 缺省的站点一律不公开', () => {
  const stops = [
    { seq: 1, name: 'A', longitude: 113.1, latitude: 23.1 },
    { seq: 2, name: 'B', longitude: null, latitude: 23.2, locationVisible: true },
    { seq: 3, name: 'C', longitude: 113.3, latitude: 23.3, locationVisible: true }
  ]
  assert.deepEqual(publicMapStops(stops).map((s) => s.name), ['C'])
  assert.deepEqual(buildPublicRoutePairs(stops), [])
  // 缺坐标不是隐私隐藏，只有 locationVisible!=true 的 A 计入锁定说明
  assert.equal(hiddenStops(stops).length, 1)
})

test('空输入不会抛错', () => {
  assert.deepEqual(publicMapStops(null), [])
  assert.deepEqual(buildPublicRoutePairs(undefined), [])
  assert.deepEqual(hiddenStops(null), [])
})
