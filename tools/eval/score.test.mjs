import test from 'node:test'
import assert from 'node:assert/strict'
import { evaluateScenario, percentile, summarizeResults } from './score.mjs'

const basePlan = {
  constraintStatus: 'FEASIBLE',
  sourceMapIds: [51],
  routeConfidence: 'AMAP',
  totalTravelMinutes: 10,
  routeSegments: [{ durationMinutes: 4 }, { durationMinutes: 6 }],
  members: [{ status: 'KNOWN' }, { status: 'KNOWN' }],
  stops: [{ source: 'GROUP_MAP' }, { source: 'GROUP_MAP' }],
  conflicts: []
}

test('S1 可行方案全部校验通过', () => {
  const result = evaluateScenario(
    { expect: { constraintStatus: 'FEASIBLE', minStops: 2, requireGroupMap: true, requireAmap: true, requireKnownAll: true } },
    { plan: basePlan, mapId: 51, trace: { complete: true, pendingEvents: 0 } }
  )
  assert.equal(result.pass, true)
  assert.equal(result.score, 100)
})

test('缺草稿直接失败', () => {
  const result = evaluateScenario({ expect: { constraintStatus: 'FEASIBLE' } }, { plan: null })
  assert.equal(result.pass, false)
})

test('冲突场景要求 BLOCKING', () => {
  const plan = { ...basePlan, constraintStatus: 'CONFLICT', conflicts: [{ severity: 'ADJACENT', overlapMinutes: 0 }] }
  const result = evaluateScenario({ expect: { constraintStatus: 'CONFLICT', requireBlocking: true } }, {
    plan, mapId: 51, trace: { complete: true, pendingEvents: 0 }
  })
  assert.equal(result.pass, false)
  assert.ok(result.checks.find((check) => check.name === 'blocking-conflict' && !check.pass))
})

test('跨群隔离按 placeId/mapId 判定，同名 POI 不误报', () => {
  const sameNamePoi = { ...basePlan, sourceMapIds: [51], stops: [{ source: 'AMAP_POI', name: '打印店', placeId: null }] }
  const allowed = evaluateScenario({ expect: { forbidMap: '社团活动策划' } }, {
    plan: sameNamePoi,
    mapId: 51,
    forbiddenMapId: 52,
    forbiddenPlaceIds: [901, 902],
    trace: { complete: true, pendingEvents: 0 }
  })
  assert.equal(allowed.pass, true)

  const leaked = { ...basePlan, sourceMapIds: [51, 52], stops: [{ source: 'GROUP_MAP', placeId: 901, mapId: 52 }] }
  // 通过全局地点库/个人图访问同名地点不算“群地图来源泄露”
  const personal = { ...basePlan, sourceMapIds: [51], stops: [{ source: 'PERSONAL_MAP', placeId: 901, mapId: null }] }
  const personalAllowed = evaluateScenario({ expect: { forbidMap: '社团活动策划' } }, {
    plan: personal,
    mapId: 51,
    forbiddenMapId: 52,
    forbiddenPlaceIds: [901],
    trace: { complete: true, pendingEvents: 0 }
  })
  assert.equal(personalAllowed.pass, true)
  const denied = evaluateScenario({ expect: { forbidMap: '社团活动策划' } }, {
    plan: leaked,
    mapId: 51,
    forbiddenMapId: 52,
    forbiddenPlaceIds: [901],
    trace: { complete: true, pendingEvents: 0 }
  })
  assert.equal(denied.pass, false)
})

test('百分位与汇总不因空输入抛错', () => {
  assert.equal(percentile([], 95), null)
  assert.equal(percentile([10, 20, 30, 40], 50), 20)
  const summary = summarizeResults([])
  assert.equal(summary.total, 0)
  assert.equal(summary.passRate, 0)
})
