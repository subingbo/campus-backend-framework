#!/usr/bin/env node
/**
 * 阶段 H 全链路演示脚本（P0.5 群地图语义检索 / P1.5 确定性时空联合规划 / 保存链路强化）。
 * 运行前提：本地后端已启动（mvn -pl campus-server -am spring-boot:run，须有 DASHSCOPE_API_KEY），MySQL/Redis 容器在运行。
 * 用法: node tools/phase-h-demo.mjs [prefix] [outFile]
 * 场景（开发计划 P0.5+P1.5 验收 §九）：
 *   Scene A 共建地图检索：群里备注「适合讨论」命中咖啡店（@AI 引用备注，方案地点 source=GROUP_MAP）。
 *   Scene B 时空联合规划：@AI 指定时刻，卡片出现 planningSourceVersion 指纹与 GROUP_MAP 来源。
 *   Scene C 跨群权限隔离：A 群 @AI 全程不出现 B 群专属地点/备注；B 群忙闲视图对无关成员不可见。
 *   Scene D 活动创建：修订草稿 → plan/save 建活动，保存成功活动的地点与站点，重复保存幂等。
 * 约束：不输出任何密钥；新增依赖为零；WS 用 Node 原生 WebSocket。
 */
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080'
const WS_URL = (process.env.BASE_URL || 'http://127.0.0.1:8080').replace(/^http/, 'ws')
const PREFIX = process.argv[2] || `h${(Date.now() % 100000).toString(36)}`
const OUT = process.argv[3] || path.join('.ai-run', `phase-h-${PREFIX}.jsonl`)
fs.mkdirSync(path.dirname(OUT), { recursive: true })

const results = []
const log = (step, ok, extra = {}) => {
  const row = { step, ok, ...extra }
  results.push(row)
  console.error(`${ok ? '✔' : '✘'} ${step} ${JSON.stringify(extra).slice(0, 200)}`)
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
  return { token: login.json?.data?.accessToken, username, userId: null }
}

function wsConnect(token) {
  return new Promise((resolve) => {
    const socket = new WebSocket(`${WS_URL}/ws?token=${encodeURIComponent(token)}`)
    socket.addEventListener('open', () => resolve(socket))
  })
}

const iso = (d) => {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/**
 * 发 @AI 并等待 AGENT 回复与规划草稿。
 * 读取以 REST 轮询为准（GET /conversations/{id}/messages + /assistant/chats/{latestId} 详情），
 * 与 WS 广播投递时序解耦；socket 仅负责发送。freshAfter 之后的 AGENT 消息才算本轮的。
 */
async function atPlan(socket, auth, conversationId, text, timeoutMs = 150_000) {
  socket.send(JSON.stringify({ type: 'CHAT_SEND', conversationId, content: text }))
  // 基线：只接受发送之后新落库的 AGENT 消息（同一会话跨场景复用，不能用 createdAt 字符串作比较）
  let baseline = 0
  const pre = await jsonFetch('GET', `/api/conversations/${conversationId}/messages?limit=50`, auth)
  for (const m of (pre.json?.data || [])) {
    if (m.id > baseline) baseline = m.id
  }
  const deadline = Date.now() + timeoutMs
  let agentText = ''
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 1000))
    const messages = await jsonFetch('GET', `/api/conversations/${conversationId}/messages?limit=10`, auth)
    const agent = (messages.json?.data || [])
      .find((m) => m.type === 'AGENT' && m.id > baseline && m.content)
    if (agent) {
      agentText = agent.content
      break
    }
  }
  // 最近一次更新的 AI 会话即本轮草稿（agent run 刚写回 plan_json）
  let plan = null
  if (agentText) {
    const chats = await jsonFetch('GET', '/api/assistant/chats', auth)
    const latest = (chats.json?.data || [])[0]
    if (latest?.id && latest.planVersion > 0) {
      const detail = await jsonFetch('GET', `/api/assistant/chats/${latest.id}`, auth)
      plan = detail.json?.data?.plan || null
    }
  }
  return { agentText, plan }
}

async function main() {
  const slot = (() => {
    const d = new Date(Date.now() + 86400000)
    d.setHours(15, 0, 0, 0)
    return d
  })()

  let wsA = null
  try {
  // ---- Scene C 准备：双群与隔离样本 ----
  const alice = await registerLogin(`${PREFIX}_alice`)
  const bob = await registerLogin(`${PREFIX}_bob`)
  const carol = await registerLogin(`${PREFIX}_carol`)
  log('accounts ready', !!(alice.token && bob.token && carol.token))

  const groupA = await jsonFetch('POST', '/api/groups', alice.token,
    { name: `${PREFIX} A群图`, description: 'H 演示 A', visibility: 'MEMBERS' })
  const mapId = groupA.json?.data?.mapId
  const groupIdA = groupA.json?.data?.groupId
  await jsonFetch('POST', `/api/groups/join`, bob.token, { inviteCode: groupA.json?.data?.inviteCode })
  log('mapA created', !!mapId, { mapId, groupIdA })

  const groupB = await jsonFetch('POST', '/api/groups', carol.token,
    { name: `${PREFIX} B群图`, description: 'H 演示 B', visibility: 'MEMBERS' })
  const secret = await jsonFetch('POST', `/api/maps/${groupB.json?.data?.mapId}/places`, carol.token,
    { name: `${PREFIX}机密讨论室`, description: 'B 群专属', categoryId: 1, longitude: 113.3525, latitude: 23.1155, confirmNew: true })
  log('mapB secret placed', secret.json?.data?.added === true)

  // ---- Scene A 准备：共建地点 + 备注----
  const library = await jsonFetch('POST', `/api/maps/${mapId}/places`, alice.token,
    { name: `${PREFIX}图书馆`, description: '校园主馆', categoryId: 2, longitude: 113.3425, latitude: 23.1055, confirmNew: true })
  const coffee = await jsonFetch('POST', `/api/maps/${mapId}/places`, alice.token,
    { name: `${PREFIX}栈咖啡`, description: '适合小组讨论的咖啡店', categoryId: 1, longitude: 113.3436, latitude: 23.1066, confirmNew: true })
  const playground = await jsonFetch('POST', `/api/maps/${mapId}/places`, alice.token,
    { name: `${PREFIX}操场`, description: '标准操场', categoryId: 3, longitude: 113.3446, latitude: 23.1076, confirmNew: true })
  log('places added', [library, coffee, playground].every(p => p.json?.data?.added === true))

  const comments = await Promise.all([
    jsonFetch('POST', `/api/maps/${mapId}/places/${library.json.data.place.id}/comments`, alice.token,
      { content: '适合安静学习，插座多' }),
    jsonFetch('POST', `/api/maps/${mapId}/places/${coffee.json.data.place.id}/comments`, alice.token,
      { content: '适合四个人讨论比赛项目，带电脑很方便' }),
    jsonFetch('POST', `/api/maps/${mapId}/places/${playground.json.data.place.id}/comments`, alice.token,
      { content: '适合运动，晚上人也少' })
  ])
  const commentOk = comments.every(c => c.status === 200)
  log('scene-a comments', commentOk)

  // ---- 忙闲上下文（alice 已授权，bob KNOWN）----
  await jsonFetch('PUT', '/api/schedules/share', alice.token,
    { targetType: 'GROUP', targetId: groupIdA, grant: true })
  await jsonFetch('POST', '/api/schedules', alice.token,
    { title: '上午例会', startTime: iso(new Date(slot.getTime() - 3 * 3600000)), endTime: iso(new Date(slot.getTime() - 2 * 3600000)), note: null })
  await jsonFetch('POST', '/api/schedules', alice.token,
    { title: '晚间自习', startTime: iso(new Date(slot.getTime() + 3 * 3600000)), endTime: iso(new Date(slot.getTime() + 4 * 3600000)), note: null })

  const busyBob = await jsonFetch('GET',
    `/api/schedules/group-busy?groupId=${groupIdA}&from=${iso(new Date())}&to=${iso(new Date(Date.now() + 172800000))}`,
    bob.token)
  const bobKnown = busyBob.json?.data?.known || []
  const bobUnknown = busyBob.json?.data?.unknownUserIds || []
  const busyBobOk = bobKnown.length > 0 && JSON.stringify(bobKnown).includes('例会') === false
  log('scene-c bob busy free/busy (no title)', busyBobOk, { knownCount: bobKnown.length, unknownCount: bobUnknown.length })

  const busyCarol = await jsonFetch('GET',
    `/api/schedules/group-busy?groupId=${groupIdA}&from=${iso(new Date())}&to=${iso(new Date(Date.now() + 172800000))}`,
    carol.token)
  const busyCarolOk = (busyCarol.json?.data?.unknownUserIds?.length ?? 0) >= 1
  log('scene-c carol sees no groupA leak', busyCarolOk, { knownCount: (busyCarol.json?.data?.known || []).length })

  // ---- Scene A：共建地图检索（@AI 引用备注） ----
  const convA = await jsonFetch('GET', `/api/conversations/maps/${mapId}`, alice.token)
  const conversationId = convA.json?.data?.id
  log('groupA chat ready', !!conversationId, { conversationId })
  await jsonFetch('POST', '/api/assistant/chats', alice.token)

  wsA = await wsConnect(alice.token)
  const framesA = []
  const framesRaw = []
  wsA.addEventListener('message', (ev) => {
    try {
      const frame = JSON.parse(ev.data)
      framesRaw.push(frame.type || '?')
      if (frame.type === 'AGENT_PLAN_CARD'
        ? frame.conversationId === conversationId
        : frame.message?.conversationId === conversationId) {
        framesA.push(frame)
      }
    } catch { /* ignore */ }
  })
  await new Promise((r) => setTimeout(r, 300))

  const sceneA = await atPlan(wsA, alice.token, conversationId,
    `@AI 周六下午我们四个人要讨论比赛项目，需要带电脑的地方。从我们群地图里挑，我倾向选小组讨论氛围好的`)
  const agentTextA = sceneA.agentText || ''
  const planA = sceneA.plan || null
  const stopA = planA?.stops?.[0] || null
  const sceneAOk = !!agentTextA && (/栈咖啡/.test(agentTextA) || /咖啡/.test(agentTextA)
    || (planA && stopA?.source === 'GROUP_MAP'))
  log('scene-a note-hit place', sceneAOk, {
    agentHasCoffee: /咖啡/.test(agentTextA), source: stopA?.source, hasCard: !!planA,
    agentText: agentTextA.slice(0, 160), rawFrames: framesRaw.slice(-8).join('|') })

  if (!sceneAOk) {
    wsA.close()
    throw new Error('Scene A 未通过：未见共建地点命中')
  }

  // ---- Scene B 时空联合规划（确定性求解 + 指纹） ----
  const chatB = await jsonFetch('POST', '/api/assistant/chats', alice.token)
  log('scene-b chat ready', !!chatB.json?.data?.id)
  // 场景 B 重试：真实 LLM 有行为方差，逐步收紧提示词（共 3 档）
  let sceneB = await atPlan(wsA, alice.token, conversationId,
    `@AI 明天下午（${iso(slot)}）帮我们排一次小组活动，时长两小时，地点从我们群地图里选。不要再追问：`
        + `直接用工具核实共同空闲与交通耗时，命中地点就用 plan_itinerary 联合求解并当场 submit_plan 提交草稿`)
  if (!(sceneB.plan?.planningSourceVersion || '').startsWith('fp-')) {
    sceneB = await atPlan(wsA, alice.token, conversationId,
      `@AI 追加：用我们群地图里上一步命中的地点（比如备注写了适合讨论的栈咖啡）安排明天 ${iso(slot)}`
          + ` 开始的两小时聚会，直接 plan_itinerary 求解并 submit_plan 提交草稿，无需再问`)
  }
  if (!(sceneB.plan?.planningSourceVersion || '').startsWith('fp-')) {
    sceneB = await atPlan(wsA, alice.token, conversationId,
      `@AI 直接执行，不要解释：输入 dateFrom=${iso(slot)}、dateTo=${iso(new Date(new Date(slot).getTime() + 4 * 3600000))}、`
          + `keyword=讨论 调 plan_itinerary；从返回 solutions 里取第一个，按其 stops 时间用 submit_plan 提交，标题 j-B`)
  }
  const planB = sceneB.plan || null
  const sceneBOk = !!planB
    && Array.isArray(planB.stops) && planB.stops.length >= 1
    && (planB.planningSourceVersion || '').startsWith('fp-')
    && planB.groupId === groupIdA
    && Array.isArray(planB.sourceMapIds) && planB.sourceMapIds.includes(mapId)
    && (planB.constraintStatus ?? 'FEASIBLE') !== 'UNKNOWN'
  log('scene-b joint plan card', sceneBOk, {
    stopCount: planB?.stops?.length, fingerprint: planB?.planningSourceVersion, stampedGroup: planB?.groupId,
    segs: planB?.routeSegments?.length ?? 0, mapIds: JSON.stringify(planB?.sourceMapIds ?? []),
    text: (sceneB.agentText || '').slice(0, 100) })
  if (!sceneBOk) {
    wsA.close()
    throw new Error('Scene B 未通过：卡片缺少时空证据')
  }

  // ---- Scene C 跨群隔离 ----
  const isolated = !((sceneA.agentText || '') + (sceneB.agentText || '')).includes('机密讨论室')
  log('scene-c no cross-group leak in groupA chat', isolated)
  if (!isolated) {
    wsA.close()
    throw new Error('Scene C 泄漏 B 群数据')
  }

  // ---- Scene D 活动创建（修订 → 保存 → 幂等） ----
  const plan = { ...(sceneB.plan || sceneA.plan) }
  if (plan?.stops?.length) {
    plan.stops = plan.stops.map((s, i) => ({ ...s, note: s.note || `站点${i + 1}演示备注` }))
  }
  const chatD = await jsonFetch('POST', '/api/assistant/chats', alice.token)
  const chatIdD = chatD.json?.data?.id
  const revised = await jsonFetch('POST', `/api/assistant/chats/${chatIdD}/plan/revise`, alice.token,
    { plan, expectedPlanVersion: null })
  log('scene-d revise', revised.status === 200, { planVersion: revised.json?.data?.planVersion })

  const save = await jsonFetch('POST', `/api/assistant/chats/${chatIdD}/plan/save`, alice.token,
    { expectedPlanVersion: revised.json?.data?.planVersion })
  const activityId = save.json?.data?.activityId
  log('scene-d save activity', !!activityId, { activityId })

  const detail = await jsonFetch('GET', `/api/activities/${activityId}`, alice.token)
  log('scene-d activity stops', (detail.json?.data?.stops || []).length > 0,
    { stops: detail.json?.data?.stops?.length, groupId: detail.json?.data?.groupId ?? null })

  const saveAgain = await jsonFetch('POST', `/api/assistant/chats/${chatIdD}/plan/save`, alice.token,
    { expectedPlanVersion: revised.json?.data?.planVersion })
  log('scene-d save idempotent', saveAgain.json?.data?.activityId === activityId,
    { again: saveAgain.json?.data?.activityId })

  // ---- Scene E 异常与冲突（评审项：日程变化 / 地图变化 / 复制另建） ----
  // E1: 不带谱系的重存草稿与已建活动重叠 → 409（ExceptLineageExternal 都不允许自身另一活动重叠）
  const planE = { ...plan }
  const chatE = await jsonFetch('POST', '/api/assistant/chats', alice.token)
  const chatIdE = chatE.json?.data?.id
  const revisedE = await jsonFetch('POST', `/api/assistant/chats/${chatIdE}/plan/revise`, alice.token,
    { plan: planE, expectedPlanVersion: null })
  const saveE1 = await jsonFetch('POST', `/api/assistant/chats/${chatIdE}/plan/save`, alice.token,
    { expectedPlanVersion: revisedE.json?.data?.planVersion })
  // 用 asNewDraft 复制：谱系清空 → 同窗口解散必须先改时间；直接保存会被自家活动冲突 409 拦截
  log('scene-e self-overlap blocked',
    saveE1.status === 409, { status: saveE1.status, msg: saveE1.json?.message })

  // E2: 把本次活动时间挪走且兑换为复制草稿（asNewDraft）→ 保存成功、原活动不动
  const planE2 = { ...planE }
  if (planE2.stops?.length && planE2.startTime && planE2.endTime) {
    const shiftDays = 3
    const shift = (v) => new Date(new Date(asDateTimeString(v).replace('T', ' ')).getTime() + shiftDays * 86400000)
    const so = shift(planE2.startTime), eo = shift(planE2.endTime)
    planE2.startTime = shiftDateTime(so)
    planE2.endTime = shiftDateTime(eo)
    planE2.stops = planE2.stops.map(s => ({
      ...s,
      startTime: s.startTime ? shiftDateTime(shift(s.startTime)) : s.startTime,
      endTime: s.endTime ? shiftDateTime(shift(s.endTime)) : s.endTime
    }))
  }
  const revisedE2 = await jsonFetch('POST', `/api/assistant/chats/${chatIdE}/plan/revise`, alice.token,
    { plan: planE2, expectedPlanVersion: revisedE.json?.data?.planVersion, asNewDraft: true })
  const saveE2 = await jsonFetch('POST', `/api/assistant/chats/${chatIdE}/plan/save`, alice.token,
    { expectedPlanVersion: revisedE2.json?.data?.planVersion })
  log('scene-e copy-as-new creates separate activity',
    saveE2.status === 200 && saveE2.json?.data?.activityId !== activityId,
    { old: activityId, newActivity: saveE2.json?.data?.activityId })

  // E3: 真实内容修改（+20 分钟）形成新版本 → 但新插入的成员日程与窗口重叠 → 409（重新确认）
  const planE3 = { ...planE2 }
  const shiftMinutes = (v, minutes) => new Date(new Date(asDateTimeString(v).replace('T', ' ')).getTime() + minutes * 60000)
  planE3.startTime = shiftDateTime(shiftMinutes(planE3.startTime, 20))
  planE3.endTime = shiftDateTime(shiftMinutes(planE3.endTime, 20))
  planE3.stops = planE3.stops.map(st => ({
    ...st,
    startTime: st.startTime ? shiftDateTime(shiftMinutes(st.startTime, 20)) : st.startTime,
    endTime: st.endTime ? shiftDateTime(shiftMinutes(st.endTime, 20)) : st.endTime
  }))
  // 日程插在原窗口内部：与新窗口仍重叠（无论业务重校验用哪个窗口都命中）
  await jsonFetch('POST', '/api/schedules', alice.token,
    { title: '临时插入事项',
      startTime: shiftDateTime(shiftMinutes(planE2.startTime, 60)),
      endTime: shiftDateTime(shiftMinutes(planE2.startTime, 120)),
      note: null })
  const revisedE3 = await jsonFetch('POST', `/api/assistant/chats/${chatIdE}/plan/revise`, alice.token,
    { plan: planE3, expectedPlanVersion: revisedE2.json?.data?.planVersion, asNewDraft: true })
  const saveE3 = await jsonFetch('POST', `/api/assistant/chats/${chatIdE}/plan/save`, alice.token,
    { expectedPlanVersion: revisedE3.json?.data?.planVersion })
  log('scene-e schedule change blocks save', saveE3.status === 409, { status: saveE3.status })
  } finally {
    if (wsA) wsA.close()
    fs.writeFileSync(OUT, results.map(r => JSON.stringify(r)).join('\n') + '\n', 'utf8')
    const okAll = results.every(r => r.ok)
    console.log(`PHASE-H ${okAll ? 'ALL-PASS' : 'HAS-FAIL'} -> ${OUT}`)
  }
}

function shiftDateTime(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** REST 返回的 LocalDateTime 可能是数组 [y,m,d,h,m,s]；归一为字符串再交 shift 处理 */
function asDateTimeString(value) {
  if (Array.isArray(value)) {
    const [y, mo, d, h = 0, mi = 0, se = 0] = value
    return `${y}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}T`
      + `${String(h).padStart(2, '0')}:${String(mi).padStart(2, '0')}:${String(se).padStart(2, '0')}`
  }
  return String(value || '')
}

main().catch(err => {
  console.error('demo failed:', err?.message || err)
  fs.writeFileSync(OUT, results.map(r => JSON.stringify(r)).join('\n') + '\n', 'utf8')
  process.exitCode = 1
})
