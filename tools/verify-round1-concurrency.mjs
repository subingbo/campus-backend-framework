#!/usr/bin/env node
/**
 * 第一轮真实后端并发验收（Testcontainers 不可用时的等价替代）。
 *
 * 覆盖：
 *  A. 直接报名 + 接受邀请混合抢名额 → 成员数恰好等于上限
 *  B. 同一成员“签到 + 取消报名”同时发生 → 恰好一方成功
 *  C. 同一 clientMsgId 连发两次 → 会话历史只落一条
 *
 * 用法：
 *   node tools/verify-round1-concurrency.mjs
 *   CAMPUS_API=http://localhost:8080/api DEMO_PASSWORD=demo1234 node tools/verify-round1-concurrency.mjs
 */
const BASE = (process.env.CAMPUS_API || 'http://localhost:8080/api').replace(/\/$/, '')
const WS_BASE = BASE.replace(/^http/, 'ws').replace(/\/api$/, '')
const PASSWORD = process.env.DEMO_PASSWORD || 'demo1234'
let ipSeq = 1

function ip() {
  return `10.250.${Math.floor(ipSeq / 250)}.${(ipSeq++ % 250) + 1}`
}

async function call(path, { method = 'GET', token, body, headers = {} } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-Forwarded-For': ip(),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers
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
    const err = new Error(`${method} ${path} 失败（${res.status}）：${payload?.message || text}`)
    err.status = res.status
    err.payload = payload
    throw err
  }
  return payload?.data
}

async function login(username) {
  const data = await call('/auth/login', {
    method: 'POST',
    body: { username, password: PASSWORD }
  })
  const me = await call('/users/me', { token: data.accessToken })
  return { username, token: data.accessToken, userId: me.id }
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function firstPlace(user) {
  const maps = await call('/maps', { token: user.token })
  for (const map of maps || []) {
    const places = await call(`/maps/${map.id}/places`, { token: user.token })
    if (places?.length) return places[0].id
  }
  throw new Error('演示账号没有可用地图地点，请先运行 tools/seed-demo.mjs')
}

async function createActivity(user, placeId, maxMembers, minutesFromNow = 60) {
  const start = new Date(Date.now() + minutesFromNow * 60_000)
  const end = new Date(start.getTime() + 2 * 60 * 60_000)
  return call('/activities', {
    method: 'POST',
    token: user.token,
    body: {
      placeId,
      title: `[R1-VERIFY] 并发验收 ${Date.now()}`,
      description: 'round1 concurrency verification',
      startTime: toLocalIso(start),
      endTime: toLocalIso(end),
      maxMembers,
      category: 'OTHER'
    }
  })
}

function toLocalIso(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

async function scenarioCapacity(users) {
  const creator = users[0]
  const placeId = await firstPlace(creator)
  const activity = await createActivity(creator, placeId, 5)

  const joiners = users.slice(1, 11)
  const invitees = users.slice(11, 16)
  const invites = []
  for (const invitee of invitees) {
    const invite = await call(`/activities/${activity.id}/invites`, {
      method: 'POST',
      token: creator.token,
      body: { userId: invitee.userId }
    })
    invites.push({ invite, invitee })
  }

  const tasks = [
    ...joiners.map((user) => call(`/activities/${activity.id}/join`, { method: 'POST', token: user.token })),
    ...invites.map(({ invite, invitee }) =>
      call(`/activity-invites/${invite.id}/accept`, { method: 'POST', token: invitee.token }))
  ]
  const results = await Promise.allSettled(tasks)
  const successes = results.filter((r) => r.status === 'fulfilled').length

  const members = await call(`/activities/${activity.id}/members`, { token: creator.token })
  const distinct = new Set(members.map((m) => m.userId))
  assert(successes === 4, `容量场景成功次数应为 4，实际 ${successes}`)
  assert(members.length === 5, `成员数应为 5，实际 ${members.length}`)
  assert(distinct.size === 5, `成员应有 5 个不同用户，实际 ${distinct.size}`)

  await call(`/activities/${activity.id}`, { method: 'DELETE', token: creator.token })
  return `容量混合并发：4 个名额成功、成员 ${members.length}/5`
}

async function scenarioCheckinLeave(users) {
  const creator = users[0]
  const member = users[1]
  const placeId = await firstPlace(creator)
  const activity = await createActivity(creator, placeId, 8, 10)
  await call(`/activities/${activity.id}/join`, { method: 'POST', token: member.token })

  const [checkin, leave] = await Promise.allSettled([
    call(`/activities/${activity.id}/checkin`, {
      method: 'POST',
      token: member.token,
      body: { code: activity.checkinCode }
    }),
    call(`/activities/${activity.id}/join`, { method: 'DELETE', token: member.token })
  ])
  const successes = [checkin, leave].filter((r) => r.status === 'fulfilled').length
  assert(successes === 1, `签到/取消应恰好一方成功，实际 ${successes}`)

  const members = await call(`/activities/${activity.id}/members`, { token: creator.token })
  const row = members.find((m) => m.userId === member.userId)
  if (checkin.status === 'fulfilled') {
    assert(row && row.checkedIn === true, '签到成功后成员行应仍存在且已签到')
  } else {
    assert(!row, '取消报名成功后成员行应不存在')
  }

  await call(`/activities/${activity.id}`, { method: 'DELETE', token: creator.token })
  return `签到/取消竞态：${checkin.status === 'fulfilled' ? '签到' : '取消'}胜出，状态一致`
}

async function scenarioChatIdempotency(users) {
  if (typeof WebSocket === 'undefined') {
    return '聊天幂等：跳过（当前 Node 无全局 WebSocket）'
  }
  const [a, b] = users
  const conversation = await call('/conversations/private', {
    method: 'POST',
    token: a.token,
    body: { userId: b.userId }
  })

  const clientMsgId = `verify-${crypto.randomUUID()}`
  const marker = `幂等验收 ${Date.now()}`
  const ws = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(a.token)}`)
  const acks = []
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('等待 WS ACK 超时')), 15_000)
    ws.onopen = () => {
      const frame = (id) => JSON.stringify({
        type: 'CHAT_SEND',
        conversationId: conversation.id,
        content: marker,
        clientMsgId
      })
      ws.send(frame())
      ws.send(frame())
    }
    ws.onmessage = (event) => {
      const frame = JSON.parse(event.data)
      if (frame.type === 'CHAT_ACK' && frame.clientMsgId === clientMsgId) {
        acks.push(frame)
        if (acks.length === 2) {
          clearTimeout(timer)
          resolve()
        }
      }
      if (frame.type === 'ERROR' && frame.clientMsgId === clientMsgId) {
        clearTimeout(timer)
        reject(new Error(`服务端拒绝消息：${frame.message}`))
      }
    }
    ws.onerror = () => {
      clearTimeout(timer)
      reject(new Error('WS 连接失败'))
    }
  })
  ws.close()

  assert(acks.filter((x) => x.duplicate === false).length === 1,
    `应恰好一次首次写入，实际 ACK：${JSON.stringify(acks)}`)
  assert(acks.filter((x) => x.duplicate === true).length === 1,
    `应恰好一次幂等命中，实际 ACK：${JSON.stringify(acks)}`)

  const messages = await call(`/conversations/${conversation.id}/messages?limit=100`, { token: a.token })
  const matched = messages.filter((m) => m.content === marker)
  assert(matched.length === 1, `同一 clientMsgId 应只落一条，实际 ${matched.length}`)
  return '聊天幂等：两次发送只落一条，ACK 一真一重'
}

async function main() {
  const users = []
  for (let i = 1; i <= 20; i++) {
    const username = `demo${String(i).padStart(2, '0')}`
    try {
      users.push(await login(username))
    } catch (e) {
      console.warn(`跳过 ${username}：${e.message}`)
    }
    if (users.length >= 16) break
  }
  assert(users.length >= 16, `至少需要 16 个演示账号，实际 ${users.length}；请先运行 tools/seed-demo.mjs`)

  const results = []
  results.push(await scenarioCapacity(users))
  results.push(await scenarioCheckinLeave(users))
  results.push(await scenarioChatIdempotency(users))

  console.log('\n=== 第一轮并发验收 ===')
  for (const line of results) console.log(`PASS  ${line}`)
  console.log('ALL PASS')
}

main().catch((e) => {
  console.error(`\nFAIL  ${e.message}`)
  process.exitCode = 1
})
