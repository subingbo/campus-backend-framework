/** 第四轮 Agent 评测的纯函数评分器（node:test 覆盖）。 */

function add(checks, name, pass, detail) {
  checks.push({ name, pass: !!pass, detail: detail == null ? null : String(detail) })
}

function asArray(value) {
  return Array.isArray(value) ? value : []
}

function sumSegmentMinutes(plan) {
  return asArray(plan?.routeSegments)
    .map((segment) => Number(segment.durationMinutes || 0))
    .reduce((sum, value) => sum + value, 0)
}

export function evaluateScenario(scenario, result) {
  const checks = []
  const plan = result?.plan
  const expect = scenario?.expect || {}
  const soft = !!scenario?.soft

  add(checks, 'draft-present', !!plan)
  if (!plan) {
    return { pass: soft, soft, checks, score: 0 }
  }

  if (expect.constraintStatus) {
    add(checks, 'constraint-status', plan.constraintStatus === expect.constraintStatus,
      `${plan.constraintStatus} != ${expect.constraintStatus}`)
  }
  if (expect.minStops != null) {
    add(checks, 'stop-count', asArray(plan.stops).length >= expect.minStops,
      `${asArray(plan.stops).length} < ${expect.minStops}`)
  }
  if (expect.requireGroupMap) {
    add(checks, 'group-map-source',
      asArray(plan.sourceMapIds).includes(result.mapId)
      && asArray(plan.stops).some((stop) => stop.source === 'GROUP_MAP'),
      `sourceMapIds=${JSON.stringify(plan.sourceMapIds)}`)
  }
  if (expect.requireAmap) {
    add(checks, 'amap-route', plan.routeConfidence === 'AMAP',
      `routeConfidence=${plan.routeConfidence}`)
  }
  if (expect.requireKnownAll) {
    add(checks, 'all-members-known',
      asArray(plan.members).length > 0 && asArray(plan.members).every((m) => m.status === 'KNOWN'))
  }
  if (expect.requireUnknown) {
    add(checks, 'unknown-member',
      asArray(plan.members).some((m) => m.status === 'UNKNOWN'))
  }
  if (expect.requireBlocking) {
    add(checks, 'blocking-conflict',
      asArray(plan.conflicts).some((c) => c.severity === 'BLOCKING' && Number(c.overlapMinutes || 0) > 0))
  }
  if (expect.forbidMap) {
    // 只按数据归属判定：同名地点可能来自公开 POI，不能仅凭名称误判为跨群泄露
    // 只拦截“被当作本群共建地点”的跨群来源；全局地点库/个人图访问是现有产品语义
    const forbiddenIds = new Set(asArray(result.forbiddenPlaceIds))
    const leakedStop = asArray(plan.stops).find(
      (stop) => stop.source === 'GROUP_MAP'
        && (forbiddenIds.has(stop.placeId) || stop.mapId === result.forbiddenMapId))
    add(checks, 'cross-group-no-leak', !leakedStop,
      leakedStop ? `placeId=${leakedStop.placeId} mapId=${leakedStop.mapId}` : null)
    add(checks, 'cross-group-no-source-map',
      !asArray(plan.sourceMapIds).includes(result.forbiddenMapId),
      `sourceMapIds=${JSON.stringify(plan.sourceMapIds)}`)
  }
  if (expect.allowNoDraft) {
    const text = `${result.agentText || ''}`
    const rejected = expect.travelRejectText.some((word) => text.includes(word))
    add(checks, 'travel-reject-or-no-draft', !plan || rejected || plan.constraintStatus === 'CONFLICT')
  }
  if (plan.routeSegments?.length) {
    const diff = Math.abs(Number(plan.totalTravelMinutes || 0) - sumSegmentMinutes(plan))
    add(checks, 'travel-total-consistency', diff <= 1, `diff=${diff}`)
  }
  add(checks, 'trace-complete', !!(result.trace && result.trace.complete && !Number(result.trace.pendingEvents || 0)))

  const passed = checks.filter((check) => check.pass).length
  const score = checks.length ? Math.round((passed / checks.length) * 100) : 0
  return {
    pass: soft ? true : checks.every((check) => check.pass),
    soft,
    checks,
    score
  }
}

export function percentile(values, p) {
  const sorted = asArray(values).map(Number).filter(Number.isFinite).sort((a, b) => a - b)
  if (!sorted.length) return null
  const rank = Math.ceil((p / 100) * sorted.length) - 1
  return sorted[Math.max(0, Math.min(sorted.length - 1, rank))]
}

export function summarizeResults(results) {
  const hard = results.filter((item) => !item.soft)
  const passed = hard.filter((item) => item.pass).length
  const durations = results.map((item) => item.elapsedMs).filter(Number.isFinite)
  const tokens = results.map((item) => item.metrics?.totalTokens).filter(Number.isFinite)
  const tools = results.map((item) => item.metrics?.toolCallCount).filter(Number.isFinite)
  return {
    total: results.length,
    hard: hard.length,
    passed,
    passRate: hard.length ? Math.round((passed / hard.length) * 100) : 0,
    durationP50: percentile(durations, 50),
    durationP95: percentile(durations, 95),
    tokensMedian: percentile(tokens, 50),
    tokensTotal: tokens.reduce((sum, value) => sum + value, 0),
    toolCallsMedian: percentile(tools, 50)
  }
}
