#!/usr/bin/env node
/**
 * 第二轮真实 LLM 群 @AI 验收：
 * 群聊发送 @AI → AGENT_GEO/AGENT 文本/AGENT_PLAN_CARD → 保存为活动 → 活动可读 → Trace 有模型与工具记录。
 *
 * 必检：触发、真实草稿、站点/约束状态、保存、活动可读、Trace。
 * 场景必检：本脚本明确要求“两地点步行路线”，因此 AGENT_GEO 必须至少一条。
 *
 * 用法：node tools/verify-round2-real-llm.mjs
 */
const BASE = (process.env.CAMPUS_API || 'http://localhost:8080/api').replace(/\/$/, '')
const WS_BASE = BASE.replace(/^http/, 'ws').replace(/\/api$/, '')
const PASSWORD = process.env.DEMO_PASSWORD || 'demo1234'
const MAP_NAME = process.env.DEMO_MAP_NAME || '周末去哪儿'
const TIMEOUT_MS = Number(process.env.REAL_LLM_TIMEOUT_MS || 240000)
let ipSeq = 1

function ip() {
  return `10.252.${Math.floor(ipSeq / 250)}.${(ipSeq++ % 250) + 1}`
}

async function call(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${BASE}${path}`, {
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
    throw new Error(`${method} ${path} 返回非 JSON（${res.status}）：${text.slice(0, 200)}`)
  }
  if (!res.ok || (payload && payload.code !== 200)) {
    throw new Error(`${method} ${path} 失败（${res.status}）：${payload?.message || text}`)
  }
  return payload?.data
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function login(username) {
  const tokens = await call('/auth/login', { method: 'POST', body: { username, password: PASSWORD } })
  const me = await call('/users/me', { token: tokens.accessToken })
  return { username, token: tokens.accessToken, userId: me.id }
}

function waitForGroupPlan(conversationId, prompt, token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(token)}`)
    const state = { geoCount: 0, agentText: '', plan: null, error: '' }
    const timer = setTimeout(() => {
      try { ws.close() } catch { /* ignore */ }
      reject(new Error(`等待群规划卡超时（${TIMEOUT_MS}ms）；AGENT 文本=${state.agentText.slice(0, 120)}`))
    }, TIMEOUT_MS)
    const finish = () => {
      if (state.plan && state.agentText) {
        clearTimeout(timer)
        try { ws.close() } catch { /* ignore */ }
        resolve(state)
      }
    }
    ws.onopen = () => {
      ws.send(JSON.stringify({
        type: 'CHAT_SEND',
        conversationId,
        content: prompt,
        clientMsgId: `real-llm-${crypto.randomUUID()}`
      }))
    }
    ws.onmessage = (event) => {
      let frame
      try {
        frame = JSON.parse(event.data)
      } catch {
        return
      }
      if (frame.type === 'AGENT_GEO') {
        state.geoCount += 1
      } else if (frame.type === 'CHAT_MESSAGE' && frame.message?.type === 'AGENT') {
        state.agentText = frame.message.content || state.agentText
        finish()
      } else if (frame.type === 'AGENT_PLAN_CARD') {
        state.plan = frame.plan
        finish()
      } else if (frame.type === 'ERROR') {
        state.error = frame.message || '未知错误'
        clearTimeout(timer)
        try { ws.close() } catch { /* ignore */ }
        reject(new Error(`群 Agent 返回错误：${state.error}`))
      }
    }
    ws.onerror = () => {
      clearTimeout(timer)
      reject(new Error('WS 连接失败'))
    }
  })
}

async function main() {
  const user = await login('demo01')
  const maps = await call('/maps', { token: user.token })
  const map = (maps || []).find((m) => m.name.includes(MAP_NAME))
  assert(map, `找不到演示地图「${MAP_NAME}」`)
  const conversation = await call(`/conversations/maps/${map.id}`, { token: user.token })
  assert(conversation?.id, '群会话不存在')
  const mapDetail = await call(`/maps/${map.id}`, { token: user.token })
  const groupId = mapDetail?.groupId
  assert(groupId, '地图缺少 groupId')

  const beforeChats = await call('/assistant/chats', { token: user.token })
  const beforeIds = new Set((beforeChats || []).map((c) => c.id))

  const prompt = '@AI 这次请安排明天上午10点到12点，从东区图书馆步行到南门咖啡的两站活动，'
    + '必须使用群地图里的地点，并按步行真实路线规划；不要沿用上一轮下午3点的时间，给出可保存的草稿。'
  console.log(`[1/5] 群 ${conversation.id} 发送 @AI，等待真实 LLM…`)
  const state = await waitForGroupPlan(conversation.id, prompt, user.token)
  const plan = state.plan
  assert(plan, '未收到 AGENT_PLAN_CARD')
  assert(plan.title, '草稿缺少标题')
  assert(Array.isArray(plan.stops) && plan.stops.length >= 1, '草稿缺少站点')
  assert(plan.startTime && plan.endTime, '草稿缺少起止时间')
  assert(Number(plan.groupId) === Number(groupId), `草稿 groupId 不匹配：${plan.groupId} != ${groupId}`)
  assert(Array.isArray(plan.sourceMapIds) && plan.sourceMapIds.includes(map.id),
    `草稿 sourceMapIds 未包含群地图 ${map.id}`)
  assert(['FEASIBLE', 'TENTATIVE', 'CONFLICT'].includes(plan.constraintStatus),
    `草稿约束状态异常：${plan.constraintStatus}`)
  assert(Array.isArray(plan.members) && plan.members.length > 0, '草稿缺少群成员忙闲摘要')
  assert(state.geoCount >= 1, '明确要求路线规划但未收到 AGENT_GEO')
  console.log(`[2/5] 草稿通过：${plan.title}，${plan.stops.length} 站，${plan.constraintStatus}，`
    + `Geo 事件 ${state.geoCount}`)

  const afterChats = await call('/assistant/chats', { token: user.token })
  const created = (afterChats || []).find((c) => !beforeIds.has(c.id))
  assert(created, '未找到本次群 @AI 新建的 AI 会话')
  const detail = await call(`/assistant/chats/${created.id}`, { token: user.token })
  assert(detail?.plan, 'AI 会话详情缺少草稿')
  assert(Number(detail.plan.groupId) === Number(groupId), 'AI 会话草稿 groupId 不匹配')
  console.log(`[3/5] AI 会话 ${created.id} 草稿可读`)

  const saved = await call(`/assistant/chats/${created.id}/plan/save`, {
    method: 'POST',
    token: user.token,
    body: {}
  })
  assert(saved?.activityId, '保存未返回 activityId')
  const activity = await call(`/activities/${saved.activityId}`, { token: user.token })
  assert(activity?.title, '保存后的活动不可读')
  assert(Array.isArray(activity.stops) && activity.stops.length >= 1, '保存后的活动缺少站点')
  console.log(`[4/5] 保存成功：activityId=${saved.activityId}，${activity.stops.length} 站`)

  await new Promise((resolve) => setTimeout(resolve, 1500))
  const traces = await call('/agent/traces?size=20', { token: user.token })
  const session = (traces?.list || []).find((t) => Number(t.conversationId) === Number(created.id))
    || (traces?.list || []).find((t) => t.triggerSource === 'group_chat')
  assert(session, '未找到本次群 @AI 的 Trace 会话')
  assert(session.model, 'Trace 缺少模型名')
  assert((session.toolCallCount || 0) > 0, `Trace 没有工具调用记录：${session.toolCallCount}`)
  assert((session.eventCount || 0) > 0, 'Trace 没有事件记录')
  console.log(`[5/5] Trace 通过：model=${session.model}，tools=${session.toolCallCount}，events=${session.eventCount}`)

  console.log('\n=== 第二轮真实 LLM 群 @AI ===')
  console.log(`PASS  草稿：${plan.title}（${plan.stops.length} 站，${plan.constraintStatus}）`)
  console.log(`PASS  保存：activityId=${saved.activityId}`)
  console.log(`PASS  Trace：${session.sessionId}`)
  console.log('ALL PASS')
}

main().catch((e) => {
  console.error(`\nFAIL  ${e.message}`)
  process.exitCode = 1
})
