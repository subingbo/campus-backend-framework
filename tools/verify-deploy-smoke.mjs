#!/usr/bin/env node
/**
 * 部署后 smoke（不依赖 demo 数据）：
 *   L0 只读：登录 + 地图/通知汇总/日程/Trace 列表均可读，结构正确。
 *   L1 可选：设置 SMOKE_CONVERSATION_ID 后，WS 同 clientMsgId 双发，必须一真一重且历史仅一条。
 *
 * 用法：
 *   CAMPUS_API=http://localhost:8080/api SMOKE_USERNAME=... SMOKE_PASSWORD=... \
 *     node tools/verify-deploy-smoke.mjs
 *
 * 可选：
 *   SMOKE_CONVERSATION_ID=123   # 开启 L1；建议使用专用 smoke 群/账号
 *
 * 注意：L1 会写入一条 `[SMOKE] ...` 消息；生产环境请使用专用 smoke 账号/群。
 */
const BASE = (process.env.CAMPUS_API || 'http://localhost:8080/api').replace(/\/$/, '')
const WS_BASE = BASE.replace(/^http/, 'ws').replace(/\/api$/, '')
const USERNAME = process.env.SMOKE_USERNAME
const PASSWORD = process.env.SMOKE_PASSWORD
const CONVERSATION_ID = Number(process.env.SMOKE_CONVERSATION_ID || 0)
const PREFIX = `[SMOKE ${new Date().toISOString()}]`

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function call(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-Forwarded-For': `10.255.0.${Math.floor(Math.random() * 200) + 1}`,
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

async function login() {
  const data = await call('/auth/login', {
    method: 'POST',
    body: { username: USERNAME, password: PASSWORD }
  })
  assert(data?.accessToken, '登录响应缺少 accessToken')
  return data.accessToken
}

function checkSummary(summary) {
  assert(summary && typeof summary === 'object', 'summary 不是对象')
  for (const key of ['unreadNotifications', 'unreadChatConversations', 'pendingFriendRequests', 'pendingActivityInvites', 'total']) {
    assert(Number.isFinite(Number(summary[key])), `summary.${key} 不是数字`)
    assert(Number(summary[key]) >= 0, `summary.${key} 为负数`)
  }
  assert(
    Number(summary.total) === Number(summary.unreadNotifications) + Number(summary.unreadChatConversations),
    `summary.total 不等于未读通知 + 有未读会话：${JSON.stringify(summary)}`
  )
}

async function level0(token) {
  const maps = await call('/maps', { token })
  assert(Array.isArray(maps), '/maps 不是数组')

  const summary = await call('/notifications/summary', { token })
  checkSummary(summary)

  const schedules = await call('/schedules', { token })
  assert(Array.isArray(schedules), '/schedules 不是数组')

  const traces = await call('/agent/traces?page=1&size=5', { token })
  assert(traces && Array.isArray(traces.list), '/agent/traces 缺少 list')
  console.log(`PASS  L0 只读：maps=${maps.length} schedules=${schedules.length} traces=${traces.list.length} total=${summary.total}`)
}

async function level1(token) {
  const marker = `${PREFIX} ws-idempotency`
  const clientMsgId = `smoke-${Date.now()}-${Math.random().toString(16).slice(2)}`
  const acks = []
  const ws = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(token)}`)
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('WS 连接超时')), 5000)
    ws.onopen = () => { clearTimeout(timer); resolve() }
    ws.onerror = () => { clearTimeout(timer); reject(new Error('WS 连接失败')) }
  })
  ws.onmessage = (event) => {
    const frame = JSON.parse(event.data)
    if (frame.type === 'CHAT_ACK' && frame.clientMsgId === clientMsgId) acks.push(frame)
  }
  const frame = { type: 'CHAT_SEND', conversationId: CONVERSATION_ID, content: marker, clientMsgId }
  ws.send(JSON.stringify(frame))
  ws.send(JSON.stringify(frame))
  await new Promise((resolve) => setTimeout(resolve, 1500))
  ws.close()

  assert(acks.length === 2, `期望 2 个 ACK，实际 ${acks.length}`)
  assert(acks.filter((a) => a.duplicate === false).length === 1, `首次 ACK 数量异常：${JSON.stringify(acks)}`)
  assert(acks.filter((a) => a.duplicate === true).length === 1, `幂等 ACK 数量异常：${JSON.stringify(acks)}`)

  const messages = await call(`/conversations/${CONVERSATION_ID}/messages?limit=100`, { token })
  assert(Array.isArray(messages), '消息列表不是数组')
  const matched = messages.filter((m) => m.content === marker)
  assert(matched.length === 1, `同一 clientMsgId 应只落一条，实际 ${matched.length}`)
  console.log('PASS  L1 WS 幂等：双发一真一重，历史仅一条')
}

async function main() {
  if (!USERNAME || !PASSWORD) {
    throw new Error('缺少 SMOKE_USERNAME / SMOKE_PASSWORD；请使用专用 smoke 账号，不要默认 demo 账号')
  }
  console.log(`[smoke] base=${BASE} user=${USERNAME}${CONVERSATION_ID ? ` conversation=${CONVERSATION_ID}` : ''}`)
  const token = await login()
  await level0(token)
  if (CONVERSATION_ID) {
    await level1(token)
  } else {
    console.log('SKIP  L1：未设置 SMOKE_CONVERSATION_ID')
  }
  console.log('\nDEPLOY SMOKE OK')
}

main().catch((error) => {
  console.error(`\nDEPLOY SMOKE FAILED: ${error.message}`)
  process.exitCode = 1
})
