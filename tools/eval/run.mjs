#!/usr/bin/env node
/**
 * 第四轮真实 qwen-plus 评测执行器。
 *
 * 用法：
 *   node tools/eval/run.mjs --repeat 3
 *   node tools/eval/run.mjs --scenario S1,S3 --repeat 3
 *   node tools/eval/run.mjs --save          # 额外跑保存/幂等/409
 *
 * 前置：后端已启动、已 seed-demo、DASHSCOPE_API_KEY / AMAP_WEB_KEY 可用。
 * 输出：docs/验收记录/第四轮/raw/<timestamp>.jsonl + 终端汇总。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { evaluateScenario, summarizeResults } from './score.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const BASE = (process.env.CAMPUS_API || 'http://localhost:8080/api').replace(/\/$/, '')
const WS_BASE = BASE.replace(/^http/, 'ws').replace(/\/api$/, '')
const PASSWORD = process.env.DEMO_PASSWORD || 'demo1234'
const TIMEOUT_MS = Number(process.env.REAL_LLM_TIMEOUT_MS || 300000)
const args = process.argv.slice(2)

function argValue(name, fallback = null) {
  const index = args.indexOf(name)
  return index >= 0 ? args[index + 1] : fallback
}
const REPEAT = Math.max(1, Number(argValue('--repeat', '3')))
const ONLY = (argValue('--scenario', '') || '').split(',').map((item) => item.trim()).filter(Boolean)
const SAVE = args.includes('--save')
const KEEP_EVAL_DATA = process.env.KEEP_EVAL_DATA === '1'
const OUT = argValue('--out', path.join('docs/验收记录/第四轮/raw', `${new Date().toISOString().replace(/[:.]/g, '-')}.jsonl`))

let ipSeq = 1
const ip = () => `10.252.${Math.floor(ipSeq / 250)}.${(ipSeq++ % 250) + 1}`

async function call(pathname, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${BASE}${pathname}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-Forwarded-For': ip(),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const text = await res.text()
  let payload = null
  try {
    payload = text ? JSON.parse(text) : null
  } catch {
    throw new Error(`${method} ${pathname} 返回非 JSON（${res.status}）：${text.slice(0, 200)}`)
  }
  if (!res.ok || (payload && payload.code !== 200)) {
    const err = new Error(`${method} ${pathname} 失败（${res.status}）：${payload?.message || text}`)
    err.status = res.status
    err.payload = payload
    throw err
  }
  return payload?.data
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function login(username) {
  const data = await call('/auth/login', { method: 'POST', body: { username, password: PASSWORD } })
  const me = await call('/users/me', { token: data.accessToken })
  return { username, token: data.accessToken, userId: me.id }
}

function pad(value) {
  return String(value).padStart(2, '0')
}

function iso(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T`
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:00`
}

/** 下一个周日（若今天周日已过 14:00 则取下一周） */
function nextSunday() {
  const now = new Date()
  const date = new Date(now)
  date.setHours(14, 0, 0, 0)
  let delta = (7 - now.getDay()) % 7
  if (delta === 0 && date <= now) delta = 7
  date.setDate(now.getDate() + delta)
  return date
}

function promptOf(scenario, sunday) {
  const label = `${pad(sunday.getMonth() + 1)}-${pad(sunday.getDate())}`
  return scenario.prompt
    .replaceAll('${SUNDAY_CN}', '本周日')
    .replaceAll('${SUNDAY}', label)
}

async function mapByName(token, namePart) {
  const maps = (await call('/maps', { token })) || []
  const map = maps.find((item) => (item.name || '').includes(namePart))
  assert(map, `找不到地图：${namePart}`)
  const detail = await call(`/maps/${map.id}`, { token })
  return { ...map, groupId: detail.groupId }
}

async function conversationFor(token, mapId) {
  const conversation = await call(`/conversations/maps/${mapId}`, { token })
  assert(conversation?.id, `地图 ${mapId} 缺少群会话`)
  return conversation
}

async function shareGroupId(user, mapNamePart) {
  const options = (await call('/schedules/share', { token: user.token })) || []
  const option = options.find((item) => (item.mapName || item.groupName || '').includes(mapNamePart))
  assert(option, `${user.username} 找不到群授权：${mapNamePart}`)
  return option.groupId
}

async function setGrant(user, groupId, grant) {
  await call('/schedules/share', {
    method: 'PUT',
    token: user.token,
    body: { targetType: 'GROUP', targetId: groupId, grant }
  })
}

/** 发 @AI 并等待卡片/终态；无卡片（校验拒绝）也正常返回 */
function waitForGroupRun(conversationId, token, prompt) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(token)}`)
    const state = {
      card: null,
      planId: null,
      agentText: '',
      geoCount: 0,
      statuses: [],
      error: '',
      timedOut: false
    }
    const startedAt = Date.now()
    let graceTimer = null
    const finish = () => {
      clearTimeout(timer)
      clearTimeout(graceTimer)
      try { ws.close() } catch { /* ignore */ }
      resolve({ ...state, elapsedMs: Date.now() - startedAt })
    }
    const timer = setTimeout(() => {
      state.timedOut = true
      finish()
    }, TIMEOUT_MS)
    const terminal = (status) => ['DONE', 'FAILED', 'CANCELLED'].includes(status)
    ws.onopen = () => {
      ws.send(JSON.stringify({
        type: 'CHAT_SEND',
        conversationId,
        content: prompt,
        clientMsgId: `r4-eval-${crypto.randomUUID()}`
      }))
    }
    ws.onmessage = (event) => {
      let frame
      try { frame = JSON.parse(event.data) } catch { return }
      if (frame.type === 'AGENT_GEO') {
        state.geoCount += 1
      } else if (frame.type === 'CHAT_MESSAGE' && frame.message?.type === 'AGENT') {
        state.agentText = frame.message.content || state.agentText
        if (state.card) finish()
      } else if (frame.type === 'AGENT_PLAN_CARD') {
        state.card = frame.plan
        state.planId = frame.planId
        graceTimer = setTimeout(finish, 800)
      } else if (frame.type === 'AGENT_STATUS') {
        state.statuses.push(frame.status)
        if (terminal(frame.status)) {
          graceTimer = setTimeout(finish, 600)
        }
      } else if (frame.type === 'ERROR') {
        state.error = frame.message || '未知错误'
        finish()
      }
    }
    ws.onerror = () => {
      clearTimeout(timer)
      clearTimeout(graceTimer)
      reject(new Error('WS 连接失败'))
    }
  })
}

async function fullPlanOf(asker, conversationId, card) {
  if (!card?.planId) return { plan: null, aiConversationId: null, planVersion: null }
  const handoff = await call(`/conversations/${conversationId}/agent-plans/${card.planId}/handoff`,
    { token: asker.token })
  assert(handoff?.aiConversationId, 'handoff 缺少 aiConversationId')
  const detail = await call(`/assistant/chats/${handoff.aiConversationId}`, { token: asker.token })
  return {
    plan: detail?.plan || null,
    aiConversationId: handoff.aiConversationId,
    planVersion: detail?.conversation?.planVersion
  }
}

async function waitForTrace(token, aiConversationId) {
  const deadline = Date.now() + 30000
  while (Date.now() < deadline) {
    const traces = await call('/agent/traces?size=20', { token })
    const session = (traces?.list || []).find(
      (item) => Number(item.conversationId) === Number(aiConversationId))
    if (session && session.complete && !Number(session.pendingEvents || 0)) {
      const events = await call(`/agent/traces/${session.sessionId}/events?size=200`, { token })
      const metricsEvent = (events?.list || []).find((item) => item.type === 'run/metrics')
      return { ...session, metrics: metricsEvent?.data || null }
    }
    await sleep(1000)
  }
  return null
}

async function createBusySchedule(user, scenario, sunday) {
  const spec = scenario.busySchedule
  const start = new Date(sunday)
  start.setHours(spec.startHour, 0, 0, 0)
  const end = new Date(sunday)
  end.setHours(spec.endHour, 0, 0, 0)
  const created = await call('/schedules', {
    method: 'POST',
    token: user.token,
    body: {
      title: spec.title,
      startTime: iso(start),
      endTime: iso(end),
      note: '[R4-EVAL] 评测临时占用'
    }
  })
  return created.id
}

async function runOne(scenario, context, index) {
  const asker = context.users[scenario.asker]
  const map = scenario.map.includes('社团') ? context.mapB : context.mapA
  const conversation = await conversationFor(asker.token, map.id)
  const prompt = promptOf(scenario, context.sunday)
  let scheduleId = null
  let revoked = false
  try {
    if (scenario.revokeUser) {
      await setGrant(context.users[scenario.revokeUser], context.mapBGroupId, false)
      revoked = true
    }
    if (scenario.busySchedule) {
      scheduleId = await createBusySchedule(context.users[scenario.busySchedule.user], scenario, context.sunday)
      console.log(`[eval] ${scenario.id} 临时日程 id=${scheduleId} ${iso(context.sunday)} ${scenario.busySchedule.startHour}:00-${scenario.busySchedule.endHour}:00`)
    }
    const state = await waitForGroupRun(conversation.id, asker.token, prompt)
    const full = await fullPlanOf(asker, conversation.id, state.card)
    const trace = full.aiConversationId ? await waitForTrace(asker.token, full.aiConversationId) : null
    const result = {
      scenarioId: scenario.id,
      scenarioTitle: scenario.title,
      repeat: index + 1,
      asker: scenario.asker,
      mapId: map.id,
      prompt,
      elapsedMs: state.elapsedMs,
      geoCount: state.geoCount,
      statuses: state.statuses,
      agentText: state.agentText,
      card: state.card,
      plan: full.plan,
      trace: trace ? {
        sessionId: trace.sessionId,
        complete: trace.complete,
        pendingEvents: trace.pendingEvents,
        totalTokens: trace.totalTokens,
        toolCallCount: trace.toolCallCount,
        eventCount: trace.eventCount,
        durationMs: trace.durationMs,
        metrics: trace.metrics
      } : null,
      metrics: trace?.metrics || null,
      forbiddenMapId: scenario.expect?.forbidMap ? context.mapB.id : null,
      forbiddenPlaceIds: scenario.expect?.forbidMap ? context.mapBPlaceIds : []
    }
    const evaluation = evaluateScenario(scenario, result)
    return { ...result, ...evaluation, timedOut: state.timedOut, error: state.error }
  } finally {
    if (scheduleId && !KEEP_EVAL_DATA) {
      await call(`/schedules/${scheduleId}`, { method: 'DELETE', token: context.users[scenario.busySchedule.user].token })
        .catch(() => {})
    }
    if (revoked) {
      await setGrant(context.users[scenario.revokeUser], context.mapBGroupId, true).catch(() => {})
    }
  }
}

async function runSaveScenario(context) {
  const scenario = {
    id: 'S6',
    title: '保存/幂等/版本 409',
    asker: 'demo01',
    map: '周末去哪儿',
    prompt: '@AI 请安排${SUNDAY_CN}（${SUNDAY}）14:00-16:00，从东区图书馆步行到南门咖啡的两站活动，使用群地图地点，直接提交草稿。',
    expect: { constraintStatus: 'FEASIBLE', minStops: 2, requireGroupMap: true }
  }
  const asker = context.users.demo01
  const conversation = await conversationFor(asker.token, context.mapA.id)
  const prompt = promptOf(scenario, context.sunday)
  const state = await waitForGroupRun(conversation.id, asker.token, prompt)
  const full = await fullPlanOf(asker, conversation.id, state.card)
  assert(full.aiConversationId && full.plan, 'S6 未拿到可保存草稿')
  const version = full.planVersion
  let staleStatus = null
  try {
    await call(`/assistant/chats/${full.aiConversationId}/plan/save`, {
      method: 'POST', token: asker.token, body: { expectedPlanVersion: Number(version) + 1 }
    })
  } catch (error) {
    staleStatus = error.status || 0
  }
  const saved = await call(`/assistant/chats/${full.aiConversationId}/plan/save`, {
    method: 'POST', token: asker.token, body: { expectedPlanVersion: version }
  })
  const again = await call(`/assistant/chats/${full.aiConversationId}/plan/save`, {
    method: 'POST', token: asker.token, body: { expectedPlanVersion: version }
  })
  if (saved?.activityId) {
    await call(`/activities/${saved.activityId}`, { method: 'DELETE', token: asker.token }).catch(() => {})
  }
  const checks = [
    { name: 'stale-version-409', pass: staleStatus === 409, detail: String(staleStatus) },
    { name: 'saved-activity', pass: !!saved?.activityId },
    { name: 'idempotent-save', pass: Number(saved?.activityId) === Number(again?.activityId) }
  ]
  return {
    scenarioId: 'S6',
    scenarioTitle: scenario.title,
    repeat: 1,
    elapsedMs: state.elapsedMs,
    pass: checks.every((check) => check.pass),
    soft: false,
    checks,
    score: Math.round((checks.filter((check) => check.pass).length / checks.length) * 100),
    savedActivityId: saved?.activityId,
    staleStatus
  }
}

/** 落盘前脱敏：不写完整草稿坐标、私有地点名、AGENT 原文或提示词。 */
function redactResult(item) {
  const plan = item.plan
  return {
    scenarioId: item.scenarioId,
    scenarioTitle: item.scenarioTitle,
    repeat: item.repeat,
    asker: item.asker,
    mapId: item.mapId,
    elapsedMs: item.elapsedMs,
    geoCount: item.geoCount,
    statuses: item.statuses,
    timedOut: item.timedOut,
    error: item.error,
    pass: item.pass,
    soft: item.soft,
    score: item.score,
    checks: item.checks,
    metrics: item.metrics,
    trace: item.trace,
    plan: plan ? {
      constraintStatus: plan.constraintStatus,
      stopCount: Array.isArray(plan.stops) ? plan.stops.length : 0,
      sources: [...new Set((plan.stops || []).map((stop) => stop.source).filter(Boolean))],
      sourceMapIds: plan.sourceMapIds,
      groupId: plan.groupId,
      routeConfidence: plan.routeConfidence,
      totalTravelMinutes: plan.totalTravelMinutes,
      memberStatuses: (plan.members || []).map((member) => member.status),
      conflictCount: (plan.conflicts || []).length,
      blockingCount: (plan.conflicts || []).filter((conflict) => conflict.severity === 'BLOCKING').length
    } : null,
    savedActivityId: item.savedActivityId,
    staleStatus: item.staleStatus
  }
}

async function main() {
  const scenarios = JSON.parse(fs.readFileSync(path.join(__dirname, 'scenarios.json'), 'utf8'))
  const selected = ONLY.length ? scenarios.filter((item) => ONLY.includes(item.id)) : scenarios
  const users = {
    demo01: await login('demo01'),
    demo02: await login('demo02'),
    demo08: await login('demo08'),
    demo09: await login('demo09')
  }
  const mapA = await mapByName(users.demo01.token, '周末去哪儿')
  const mapB = await mapByName(users.demo08.token, '社团活动策划')
  const mapBDetail = await call(`/maps/${mapB.id}`, { token: users.demo08.token })
  const mapBPlaceIds = (await call(`/maps/${mapB.id}/places`, { token: users.demo08.token }))
    .map((place) => place.id)
    .filter((id) => id != null)
  assert(users.demo01.token && users.demo08.token && users.demo09.token, 'demo 账号登录失败')
  const context = {
    users,
    mapA,
    mapB: { ...mapB, groupId: mapBDetail.groupId },
    mapBGroupId: await shareGroupId(users.demo09, '社团活动策划'),
    mapBPlaceIds,
    sunday: nextSunday()
  }
  console.log(`[eval] base=${BASE} scenarios=${selected.map((s) => s.id).join(',')} repeat=${REPEAT} sunday=${iso(context.sunday)}`)

  const results = []
  for (const scenario of selected) {
    for (let i = 0; i < REPEAT; i += 1) {
      try {
        const result = await runOne(scenario, context, i)
        results.push(result)
        console.log(`${result.pass ? 'PASS' : 'FAIL'} ${scenario.id} #${i + 1} `
          + `status=${result.plan?.constraintStatus || '-'} stops=${result.plan?.stops?.length || 0} `
          + `elapsed=${result.elapsedMs}ms score=${result.score}`)
        if (!result.pass) {
          for (const check of result.checks.filter((item) => !item.pass)) {
            console.log(`  - ${check.name}: ${check.detail || 'failed'}`)
          }
        }
      } catch (error) {
        const failed = {
          scenarioId: scenario.id,
          repeat: i + 1,
          pass: false,
          soft: !!scenario.soft,
          checks: [{ name: 'runner', pass: false, detail: error.message }],
          score: 0
        }
        results.push(failed)
        console.log(`FAIL ${scenario.id} #${i + 1} runner: ${error.message}`)
      }
    }
  }

  if (SAVE) {
    try {
      const saveResult = await runSaveScenario(context)
      results.push(saveResult)
      console.log(`${saveResult.pass ? 'PASS' : 'FAIL'} S6 save/idempotency/409`)
    } catch (error) {
      results.push({
        scenarioId: 'S6', repeat: 1, pass: false, soft: false,
        checks: [{ name: 'runner', pass: false, detail: error.message }], score: 0
      })
      console.log(`FAIL S6 runner: ${error.message}`)
    }
  }

  const summary = summarizeResults(results)
  const redacted = results.map(redactResult)
  const payload = {
    generatedAt: new Date().toISOString(),
    base: BASE,
    model: 'qwen-plus',
    sunday: iso(context.sunday),
    repeat: REPEAT,
    summary,
    results: redacted
  }
  fs.mkdirSync(path.dirname(OUT), { recursive: true })
  fs.writeFileSync(OUT, `${redacted.map((item) => JSON.stringify(item)).join('\n')}\n`, 'utf8')
  fs.writeFileSync(`${OUT}.summary.json`, `${JSON.stringify(payload, null, 2)}\n`, 'utf8')

  console.log('\n=== 第四轮 Agent 评测 ===')
  console.log(`hard=${summary.passed}/${summary.hard} passRate=${summary.passRate}% `
    + `p50=${summary.durationP50}ms p95=${summary.durationP95}ms tokensTotal=${summary.tokensTotal}`)
  console.log(`raw: ${OUT}`)
  console.log(`summary: ${OUT}.summary.json`)
  if (summary.passed !== summary.hard) {
    process.exitCode = 1
  }
}

main().catch((error) => {
  console.error(`\nEVAL FAILED: ${error.message}`)
  process.exitCode = 1
})
