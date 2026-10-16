#!/usr/bin/env node
/**
 * 阶段 G 全链路演示脚本（双账号 + WebSocket @AI + 草稿修订保存幂等）。
 * 运行前提：本地后端已启动（mvn -pl campus-server -am spring-boot:run），MySQL/Redis 容器在运行。
 * 用法: node tools/phase-g-demo.mjs [prefix] [outFile]
 * 场景（对应新计划 §17 三幕 + §12 接口）：
 *   Act0 准备：三账号注册，建共享图+两处地点，bob 加入，alice 授予 GROUP 忙闲，alice 建个人日程块。
 *   Act1 理解地点：GET /api/maps/{id}/places 只含授权地点。
 *   Act2 理解时间：GET /api/schedules/group-busy——bob 视图能看到 alice 的忙闲时段（FREE_BUSY，无标题）；
 *        未入群 carol 只能看到自己的空忙闲、绝不泄露 alice 内容。
 *   Act3 协同规划：WS @AI → AGENT_PLAN_CARD → 后端 revise 改一版 → plan/save（带 expectedVersion 幂等）→
 *        README 日历可查。最后输出 JSONL 验收结论。
 * 约束：不输出任何密钥；新增依赖为零；WS 用 Node 22 原生 WebSocket。
 */
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080'
const WS_URL = (process.env.BASE_URL || 'http://127.0.0.1:8080').replace(/^http/, 'ws')
const PREFIX = process.argv[2] || `g${new Date().toISOString().slice(5, 10).replaceAll('-', '')}`
const OUT = process.argv[3] || path.join('.ai-run', `phase-g-${PREFIX}.jsonl`)
fs.mkdirSync(path.dirname(OUT), { recursive: true })

const results = []
const log = (step, ok, extra = {}) => {
  const row = { step, ok, ...extra }
  results.push(row)
  console.error(`${ok ? '✔' : '✘'} ${step} ${JSON.stringify(extra).slice(0, 180)}`)
}

async function jsonFetch(method, url, token, body) {
  const resp = await fetch(BASE + url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {})
  })
  const text = await resp.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* ignore */ }
  return { status: resp.status, json }
}

async function registerLogin(username) {
  await jsonFetch('POST', '/api/auth/register', null, { username, password: 'password1', nickname: username })
  const login = await jsonFetch('POST', '/api/auth/login', null, { username, password: 'password1' })
  return { token: login.json?.data?.accessToken, userId: null, username }
}

function wsConnect(token) {
  return new Promise((resolve) => {
    const socket = new WebSocket(`${WS_URL.replace('http', 'ws').replace('https', 'wss')}/ws?token=${encodeURIComponent(token)}`)
    socket.addEventListener('open', () => resolve(socket))
  })
}

const slot = (() => {
  const d = new Date(Date.now() + 86400000)
  d.setHours(14, 0, 0, 0)
  return d
})()
const iso = (d) => {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

async function main() {
  // ACT0 准备
  const alice = await registerLogin(`${PREFIX}_alice`)
  const bob = await registerLogin(`${PREFIX}_bob`)
  const carol = await registerLogin(`${PREFIX}_carol`)
  log('ldap ready', !!(alice.token && bob.token && carol.token))

  const created = await jsonFetch('POST', '/api/groups', alice.token,
    { name: `${PREFIX} 共享图`, description: 'G 演示', visibility: 'MEMBERS' })
  const mapId = created.json?.data?.mapId
  const groupId = created.json?.data?.groupId
  const invite = created.json?.data?.inviteCode
  log('map created', !!mapId, { mapId, groupId })

  await jsonFetch('POST', `/api/groups/join`, bob.token, { inviteCode: invite })

  const placeA = await jsonFetch('POST', `/api/maps/${mapId}/places`, alice.token,
    { name: '东九草坪角', description: '集合开阔地', categoryId: 4, longitude: 113.3425, latitude: 23.1055, confirmNew: true })
  const placeB = await jsonFetch('POST', `/api/maps/${mapId}/places`, alice.token,
    { name: '南门奶茶街口', description: '七家奶茶店', categoryId: 1, longitude: 113.3436, latitude: 23.1066, confirmNew: true })
  log('places added', placeA.json?.data?.added === true && placeB.json?.data?.added === true)

  await jsonFetch('PUT', '/api/schedules/share', alice.token,
    { targetType: 'GROUP', targetId: groupId, grant: true })
  await jsonFetch('POST', '/api/schedules', alice.token,
    { title: '组会占用', startTime: iso(new Date(slot.getTime() - 3600000)), endTime: iso(slot), note: null })

  // ACT1 理解地点
  const places = await jsonFetch('GET', `/api/maps/${mapId}/places`, alice.token)
  const placeNames = (places.json?.data || []).map(p => p.name)
  log('act1 places', placeNames.includes('东九草坪角') && placeNames.includes('南门奶茶街口'), { count: places.json?.data?.length })

  // ACT2 理解时间（bob 视角：知道 alice 忙，但看不到标题；carol 无授权不泄露）
  const busyBob = await jsonFetch('GET',
    `/api/schedules/group-busy?groupId=${groupId}&from=${iso(new Date(Date.now()))}&to=${iso(new Date(Date.now() + 172800000))}`,
    bob.token)
  const bobKnown = busyBob.json?.data?.known || []
  const bobUnknown = busyBob.json?.data?.unknownUserIds || []
  const bobViewOk = bobKnown.some(k => k.sourceType === 'SCHEDULE') && JSON.stringify(bobKnown).includes('组会') === false
  log('act2 bob busy free/busy', bobViewOk, { knownCount: bobKnown.length, unknownCount: bobUnknown.length })

  const busyCarol = await jsonFetch('GET', `/api/schedules/group-busy?groupId=${groupId}&from=${iso(new Date(Date.now()))}&to=${iso(new Date(Date.now() + 172800000))}`, carol.token)
  const carolKnown = busyCarol.json?.data?.known || []
  log('act2 carol sees no clash', !JSON.stringify(carolKnown).includes('组会占用') &&
    (busyCarol.json?.data?.unknownUserIds?.length ?? 0) >= 1, { carolKnown: carolKnown.length })

  // ACT3 协同规划（WS @AI，Node 原生 WebSocket）
  const chat = await jsonFetch('POST', '/api/assistant/chats', alice.token)
  const chatId = chat.json?.data?.id
  const conv = await jsonFetch('GET', `/api/conversations/maps/${mapId}`, alice.token)
  const conversationId = conv.json?.data?.id
  log('act3 chats ready', !!chatId && !!conversationId, { chatId, conversationId })

  const ws2 = await wsConnect(alice.token)
  const frames2 = []
  ws2.addEventListener('message', (ev) => {
    try {
      const frame = JSON.parse(ev.data)
      if (frame.conversationId === conversationId) frames2.push(frame)
    } catch { /* ignore */ }
  })
  await new Promise(r => setTimeout(r, 300))
  ws2.send(JSON.stringify({ type: 'CHAT_SEND', conversationId, content: '@AI 周六下午我们什么时候都有空？帮我排一个两小时的小活动，地点优先用共享地图。' }))
  let card = null
  let agentMessage = null
  for (let i = 0; i < 120 && !card; i++) {
    await new Promise(r => setTimeout(r, 500))
    card = frames2.find(f => f.type === 'AGENT_PLAN_CARD') || null
    agentMessage = frames2.find(f => f.type === 'CHAT_MESSAGE' && f.message?.type === 'AGENT') || null
  }
  log('act3 @AI plan card', !!card, { stops: card?.plan?.stops?.length ?? 0 })

  // 编卡上线：从卡片取 plan 修正后 revise → save
  if (card?.plan) {
    const plan = card.plan
    if (plan.stops?.length) {
      plan.stops[0].note = '演示修订备注'
    }
    const revised = await jsonFetch('POST', `/api/assistant/chats/${chatId}/plan/revise`, alice.token,
      { plan, expectedPlanVersion: null })
    log('act3 revise', revised.json?.data?.planVersion > 0, { planVersion: revised.json?.data?.planVersion })
    const save = await jsonFetch('POST', `/api/assistant/chats/${chatId}/plan/save`, alice.token,
      { expectedPlanVersion: revised.json?.data?.planVersion })
    const activityId = save.json?.data?.activityId
    log('act3 save activity', !!activityId, { activityId })

    const detail = await jsonFetch('GET', `/api/activities/${activityId}`, alice.token)
    log('act3 activity stops', (detail.json?.data?.stops || []).length > 0, { stops: detail.json?.data?.stops?.length })
    const saved = await jsonFetch('POST', `/api/assistant/chats/${chatId}/plan/save`, alice.token,
      { expectedPlanVersion: revised.json?.data?.planVersion })
    log('act3 save idempotent', saved.json?.data?.activityId === activityId, { again: saved.json?.data?.activityId })
  }

  ws2.close()
  fs.writeFileSync(OUT, results.map(r => JSON.stringify(r)).join('\n') + '\n', 'utf8')
  const okAll = results.every(r => r.ok)
  console.log(`PHASE-G ${okAll ? 'ALL-PASS' : 'HAS-FAIL'} -> ${OUT}`)
}

main().catch(err => {
  console.error('demo failed:', err?.message || err)
  fs.writeFileSync(OUT, results.map(r => JSON.stringify(r)).join('\n') + '\n', 'utf8')
  process.exitCode = 1
})
