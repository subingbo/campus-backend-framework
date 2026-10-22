#!/usr/bin/env node
/**
 * 第三轮演示场景校验（不调用 LLM）：
 *  1. Map A 全员忙闲授权保持完好（保护第二轮 FEASIBLE 基线，不做永久撤销）；
 *  2. Map B 临时撤销 demo09 授权 → group-busy 出现 UNKNOWN → 恢复授权；
 *  3. Map B 恢复后 UNKNOWN 清空。
 *
 * 用法：node tools/verify-round3-scenarios.mjs
 */
const BASE = (process.env.CAMPUS_API || 'http://localhost:8080/api').replace(/\/$/, '')
const PASSWORD = process.env.DEMO_PASSWORD || 'demo1234'
let ipSeq = 1

function ip() {
  return `10.253.${Math.floor(ipSeq / 250)}.${(ipSeq++ % 250) + 1}`
}

function pad(value) {
  return String(value).padStart(2, '0')
}

function iso(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T`
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
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
  const data = await call('/auth/login', {
    method: 'POST',
    body: { username, password: PASSWORD }
  })
  return { username, token: data.accessToken }
}

async function groupIdOf(user, mapNamePart) {
  const options = (await call('/schedules/share', { token: user.token })) || []
  const option = options.find((item) => (item.mapName || item.groupName || '').includes(mapNamePart))
  assert(option, `${user.username} 找不到群：${mapNamePart}`)
  return option.groupId
}

async function unknownUsers(user, groupId) {
  const from = new Date()
  const to = new Date(Date.now() + 7 * 86400000)
  const data = await call(
    `/schedules/group-busy?groupId=${groupId}&from=${encodeURIComponent(iso(from))}&to=${encodeURIComponent(iso(to))}`,
    { token: user.token }
  )
  return data.unknownUserIds || []
}

async function setGrant(user, groupId, grant) {
  await call('/schedules/share', {
    method: 'PUT',
    token: user.token,
    body: { targetType: 'GROUP', targetId: groupId, grant }
  })
}

async function main() {
  const demo01 = await login('demo01')
  const demo08 = await login('demo08')
  const demo09 = await login('demo09')

  const mapA = await groupIdOf(demo01, '周末去哪儿')
  const mapB = await groupIdOf(demo09, '社团活动策划')

  const mapAUnknown = await unknownUsers(demo01, mapA)
  assert(mapAUnknown.length === 0, `Map A 应有全员授权，实际 UNKNOWN=${JSON.stringify(mapAUnknown)}`)
  console.log('PASS  Map A 全员忙闲授权完整（未做任何撤销）')

  const demo09Id = (await call('/users/me', { token: demo09.token })).id
  let revoked = false
  try {
    await setGrant(demo09, mapB, false)
    revoked = true
    const afterRevoke = await unknownUsers(demo08, mapB)
    assert(afterRevoke.includes(demo09Id),
      `撤销 demo09 后应出现 UNKNOWN，实际 ${JSON.stringify(afterRevoke)}`)
    console.log('PASS  Map B 临时撤销 demo09 → group-busy 标记 UNKNOWN')
  } finally {
    if (revoked) await setGrant(demo09, mapB, true)
  }

  const afterRestore = await unknownUsers(demo08, mapB)
  assert(!afterRestore.includes(demo09Id),
    `恢复 demo09 后不应再是 UNKNOWN，实际 ${JSON.stringify(afterRestore)}`)
  console.log('PASS  Map B 恢复 demo09 授权 → UNKNOWN 清空')

  console.log('\nALL PASS（Map A 基线未受影响；Map B 临时撤销已恢复）')
}

main().catch((error) => {
  console.error(`\nFAIL  ${error.message}`)
  process.exitCode = 1
})
