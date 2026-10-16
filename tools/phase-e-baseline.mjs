#!/usr/bin/env node
/**
 * 阶段 E 固定场景基线采集器：真实 Qwen(qwen-plus)+高德 smoke，去标识化输出。
 * 用法: node tools/phase-e-baseline.mjs <profilePrefix> [outFile]
 *   例: node tools/phase-e-baseline.mjs e1a .ai-run/baseline-e1a.jsonl
 * 约束：
 * - 不读取/输出任何密钥；本脚本只与本地后端 API 交互（Authorization 为用户 JWT）。
 * - 每场景独立注册用户+新会话；AI_RUN 观测行由后端控制台日志单独采集。
 * - 场景文案与本文件冻结版本一致；优化前后两次采集仅换 profilePrefix 保证用户独立。
 */
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080'
const PREFIX = process.argv[2]
if (!PREFIX) {
  console.error('usage: node tools/phase-e-baseline.mjs <profilePrefix> [outFile]')
  process.exit(2)
}
const OUT = process.argv[3] || path.join('.ai-run', `baseline-${PREFIX}.jsonl`)
fs.mkdirSync(path.dirname(OUT), { recursive: true })

const MAP_SEEDS = [
  { name: '东九草坪角', description: '场地开阔适合集合', longitude: 113.3425, latitude: 23.1055 },
  { name: '南门奶茶街口', description: '奶茶店聚集街口', longitude: 113.3436, latitude: 23.1066 },
]

/** 固定 10 场景（冻结文案，计划 E1 覆盖 1站/3站/多站、指定共享地图、无地图、模糊日期、不可用地点、跨日、纯聊天、外部失败） */
const scenarios = [
  { id: 's01', look: '单站例会（系统地点库）', expect: '草稿或合理追问', seed: false,
    content: '下周三上午九点半到十一点半，学生会要开 12 人的例会，教室最好能放投影。地点用系统地点库里合适的一处，帮我排一版时间轴草稿。' },
  { id: 's02', look: '三站打卡（系统地点库）', expect: '草稿', seed: false,
    content: '这周六下午两点到五点半，5 个同学想做一个三站打卡联游：先图书馆，再体育场，最后找地方聚餐。全部步行，给出各站时间和路线耗时依据，最后提交草稿。' },
  { id: 's03', look: '跨日多站', expect: '草稿或如实说明', seed: false,
    content: '活动周想排一条跨日多站行程：周一上午协同写材料，周二下午户外报名宣传（要适合发传单的场地），周三傍晚复盘。给每段时间和地点，能排就出草稿。' },
  { id: 's04', look: '指定共享地图优先', expect: '草稿（以地图地点为主）', seed: true,
    contentTpl: (mapName) => `（请优先参考我共建的共享地图「${mapName}」里的地点）\n这周日下午三点开始，3 个人按我们一起收藏的地图逛校园，最后到地图里那家奶茶街口收尾，帮我排成草稿。` },
  { id: 's05', look: '系统库没有，走高德 POI', expect: '草稿（外部 POI）', seed: false,
    content: '学校南门外沿路想找两家连锁甜品店当活动打卡点（系统地点库里可能没有，用高德搜）。时间定这周六上午十点到十二点，4 人，先探店再汇总成一份草稿。' },
  { id: 's06', look: '模糊日期', expect: '合理追问或低风险草稿', seed: false,
    content: '我只想说"这周末天气最好的那天"，帮我和 3 个朋友安排操场跑道边 1 小时的跳绳快闪，时间你来定，尽快给草稿。' },
  { id: 's07', look: '不可用生僻地名', expect: '追问/说明不会编造', seed: false,
    content: '下周四想带 5 个人去"绯雨轩·玄世界工作室—幻景石灯"这个三层小馆办分享会，系统里应该没有这个地点，帮我们排出具体安排。' },
  { id: 's08', look: '纯聊天（无成形需求）', expect: '纯答复不产草稿', seed: false,
    content: '我就是闲聊下：办一场校园弹唱会，人数未知、地点想户外，先帮我分析有哪些准备工作就好。' },
  { id: 's09', look: '高德 0 结果', expect: '回复查不到并询问换词', seed: false,
    content: '帮我在学校围墙尽头搜一个叫"星陨石灯笼铺"的神秘小店，下周二去打卡，找不到也别硬编。' },
  { id: 's10', look: '综合：地图+高德新点+路线', expect: '草稿', seed: true,
    contentTpl: (mapName) => `（请优先参考我共建的共享地图「${mapName}」里的地点）\n用地图里两个收藏点，再用高德找一家新的口袋公园作第三站，这周日上午定 4 小时的三站小团建，6 人步行，直接给我草稿。` },
]

async function jsonFetch(method, url, token, body) {
  const resp = await fetch(BASE + url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const text = await resp.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* noop */ }
  return { status: resp.status, json, text }
}

/** 流式接收 SSE（复用 aiStream.js 同规格解析：\n\n 与 \r\n\r\n 均可），返回事件与区间耗时 */
async function sseFetch(urlPath, token, body) {
  const resp = await fetch(BASE + urlPath, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  })
  if (!resp.ok || !resp.body) {
    throw new Error(`HTTP ${resp.status}`)
  }
  const events = []
  const t0 = Date.now()
  const decoder = new TextDecoder()
  let buffer = ''
  const emit = (event, payload) => events.push({ name: event, texture: JSON.stringify(payload).slice(0, 140), atMs: Date.now() - t0 })
  function handleBlock(block) {
    let name = 'message'
    const lines = []
    for (const raw of block.replace(/\r/g, '').split('\n')) {
      if (raw.startsWith('event:')) name = raw.slice(6).trim()
      else if (raw.startsWith('data:')) lines.push(raw.slice(5).replace(/^ /, ''))
    }
    if (!lines.length) return
    const data = lines.join('\n')
    let payload = data
    try { payload = JSON.parse(data) } catch { /* 文本事件 */ }
    emit(name, payload)
  }
  const reader = resp.body.getReader()
  let consumed = 0
  const sepRe = /\r?\n\r?\n/g
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    sepRe.lastIndex = consumed
    let m
    while ((m = sepRe.exec(buffer)) !== null) {
      const block = buffer.slice(consumed, m.index)
      consumed = m.index + m[0].length
      if (block.trim()) handleBlock(block)
    }
  }
  if (buffer.slice(consumed).trim()) handleBlock(buffer.slice(consumed))
  return { events, durMs: Date.now() - t0, firstEventMs: events[0]?.atMs ?? null }
}

function summarize(events) {
  const counts = {}
  for (const e of events) counts[e.name] = (counts[e.name] || 0) + 1
  return counts
}

async function runScenario(s, prefix) {
  const user = `${prefix}_${s.id}`
  const reg = await jsonFetch('POST', '/api/auth/register', null, { username: user, password: 'password1', nickname: user })
  if (reg.status !== 200) throw new Error(`register ${reg.status}`)
  const login = await jsonFetch('POST', '/api/auth/login', null, { username: user, password: 'password1' })
  const token = login.json?.data?.accessToken
  if (!token) throw new Error('no token')
  const headers = { Authorization: `Bearer ${token}` }

  let mapName = null
  if (s.seed) {
    mapName = `共创收藏地图（${prefix}）`
    const created = await jsonFetch('POST', '/api/groups', token, {
      name: mapName, description: 'E 阶段基线共享地图', visibility: 'MEMBERS',
    })
    const mapId = created.json?.data?.mapId
    if (!mapId) throw new Error('map create failed')
    for (const seed of MAP_SEEDS) {
      const added = await jsonFetch('POST', `/api/maps/${mapId}/places`, token, { ...seed, categoryId: 1, confirmNew: true })
      if (!added.json?.data?.added) throw new Error('seed place failed: ' + JSON.stringify(added.json).slice(0, 120))
    }
  }

  const chat = await jsonFetch('POST', '/api/assistant/chats', token)
  const chatId = chat.json?.data?.id
  const content = s.contentTpl ? s.contentTpl(mapName) : s.content
  let stream
  try {
    stream = await sseFetch(`/api/assistant/chats/${chatId}/messages`, token, { content })
  } catch (err) {
    return { id: s.id, look: s.look, expect: s.expect, outcome: 'TRANSPORT_ERROR', error: String(err.message || err) }
  }
  const detail = await jsonFetch('GET', `/api/assistant/chats/${chatId}`, token)
  const conv = detail.json?.data?.conversation || {}
  const plan = detail.json?.data?.plan || null
  return {
    id: s.id, look: s.look, expect: s.expect,
    durMs: stream.durMs, firstEventMs: stream.firstEventMs,
    events: summarize(stream.events),
    lastStatusText: [...stream.events].reverse().find(e => e.name === 'status')?.texture?.slice(0, 80) || null,
    conversationStatus: conv.status, planVersion: conv.planVersion,
    draftStops: plan?.stops?.length ?? 0,
    draftTitle: plan?.title ?? null,
    messagesCount: (detail.json?.data?.messages || []).length,
  }
}

/** 预热（不计场景）：建立连接与各项冷缓存 */
async function warmup(prefix) {
  const user = `${prefix}_warm`
  await jsonFetch('POST', '/api/auth/register', null, { username: user, password: 'password1', nickname: user })
  const login = await jsonFetch('POST', '/api/auth/login', null, { username: user, password: 'password1' })
  const token = login.json?.data?.accessToken
  const chat = await jsonFetch('POST', '/api/assistant/chats', token)
  const stream = await sseFetch(`/api/assistant/chats/${chat.json?.data?.id}/messages`, token,
    { content: '你好，在吗？（预热不计入场景）' })
  return { id: 'warm', durMs: stream.durMs, events: summarize(stream.events) }
}

const results = []
results.push(await warmup(PREFIX))
for (const s of scenarios) {
  try {
    const r = await runScenario(s, PREFIX)
    results.push(r)
    console.error(`done ${s.id} durMs=${r.durMs} outcome=${r.conversationStatus || r.outcome}`)
  } catch (err) {
    results.push({ id: s.id, look: s.look, expect: s.expect, outcome: 'SCRIPT_ERROR', error: String(err?.message || err) })
    console.error(`err ${s.id}: ${err?.message || err}`)
  }
}
fs.writeFileSync(OUT, results.map(r => JSON.stringify(r)).join('\n') + '\n', 'utf8')
console.log(`WROTE ${OUT} (${results.length} lines)`)
