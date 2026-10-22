#!/usr/bin/env node
/**
 * 演示数据只读导出：逐账号登录，汇总账号/地图群聊/个人日程/忙闲授权/好友，输出 Markdown 表。
 *
 * 前置：后端 8080、MySQL、Redis 已启动；Node 22+（原生 fetch）。
 * 用法：
 *   node tools/demo-report.mjs                       # 打印 Markdown 到 stdout
 *   node tools/demo-report.mjs --verify              # 只校验（每人至少 1 张地图、2 条未来日程、1 个群授权）
 *   node tools/demo-report.mjs --out docs/xxx.md     # 写入文件
 *
 * 本脚本只读，不会创建或修改任何数据。
 */
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080'
const PASSWORD = process.env.DEMO_PASSWORD || 'demo1234'
const args = process.argv.slice(2)
const VERIFY = args.includes('--verify')
const OUT_INDEX = args.indexOf('--out')
const OUT_FILE = OUT_INDEX >= 0 ? args[OUT_INDEX + 1] : null

const ACCOUNTS = [
  { username: 'demo01', nickname: '林晓' },
  { username: 'demo02', nickname: '陈晨' },
  { username: 'demo03', nickname: '王宁' },
  { username: 'demo04', nickname: '赵可' },
  { username: 'demo05', nickname: '周予' },
  { username: 'demo06', nickname: '刘畅' },
  { username: 'demo07', nickname: '孙悦' },
  { username: 'demo08', nickname: '何川' },
  { username: 'demo09', nickname: '郭然' },
  { username: 'demo10', nickname: '马骁' },
  { username: 'demo11', nickname: '高远' },
  { username: 'demo12', nickname: '罗晴' },
  { username: 'demo13', nickname: '唐果' },
  { username: 'demo14', nickname: '许安' },
  { username: 'demo15', nickname: '沈知' },
  { username: 'demo16', nickname: '方圆' },
  { username: 'demo17', nickname: '韩笑' },
  { username: 'demo18', nickname: '郑一' },
  { username: 'demo19', nickname: '吴桐' },
  { username: 'demo20', nickname: '谢雨' }
]

const pad = (value) => String(value).padStart(2, '0')
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function formatLocal(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 后端把 LocalDateTime 序列化成数组或 ISO 字符串，这里统一成 MM-DD HH:mm。 */
function formatTime(value) {
  if (Array.isArray(value)) {
    const [, month, day, hour, minute] = value
    return `${pad(month)}-${pad(day)} ${pad(hour)}:${pad(minute)}`
  }
  if (typeof value === 'string' && value.length >= 16) {
    return `${value.slice(5, 10)} ${value.slice(11, 16)}`
  }
  return '-'
}

async function jsonFetch(method, url, token, body, xff) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (xff) headers['X-Forwarded-For'] = xff
  const response = await fetch(BASE + url, {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  })
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = null
  }
  return { status: response.status, json, text }
}

async function api(method, url, token, body, xff) {
  let result = await jsonFetch(method, url, token, body, xff)
  if (result.status === 429) {
    await sleep(1100)
    result = await jsonFetch(method, url, token, body, xff)
  }
  if (result.status !== 200 || result.json?.code !== 200) {
    const message = result.json?.message || result.text?.slice(0, 160) || ''
    throw new Error(`${method} ${url} -> HTTP ${result.status} ${message}`)
  }
  return result.json.data
}

async function collect(account, index) {
  // 登录限流 10 次/分钟/IP，逐账号使用不同来源地址
  const xff = `10.252.0.${index + 1}`
  const login = await api('POST', '/api/auth/login', null, {
    username: account.username,
    password: PASSWORD
  }, xff)
  const token = login.accessToken

  const me = await api('GET', '/api/users/me', token)
  const maps = (await api('GET', '/api/maps', token)) || []
  const schedules = (await api('GET', '/api/schedules', token)) || []
  const shareOptions = (await api('GET', '/api/schedules/share', token)) || []
  const friends = (await api('GET', '/api/friends', token)) || []
  const conversations = (await api('GET', '/api/conversations', token)) || []

  const mapDetails = []
  for (const map of maps) {
    mapDetails.push(await api('GET', `/api/maps/${map.id}`, token))
  }

  return { account, me, maps, mapDetails, schedules, shareOptions, friends, conversations }
}

function mapText(user) {
  return user.maps
    .map((map) => `${map.name}（${map.myRole}）`)
    .join('；') || '-'
}

function grantedGroupText(user) {
  const granted = user.shareOptions.filter((option) => option.granted)
  return granted.map((option) => option.groupName || option.mapName).join('、') || '-'
}

function friendText(user) {
  return user.friends.map((friend) => friend.nickname || friend.username).join('、') || '-'
}

function render(users, mapIndex, generatedAt) {
  const lines = []
  lines.push('# 演示账号与数据总表（自动生成）')
  lines.push('')
  lines.push(`> 生成时间：${generatedAt}`)
  lines.push('> 数据来源：逐账号调用 `/api/users/me`、`/api/maps`、`/api/maps/{id}`、`/api/schedules`、')
  lines.push('> `/api/schedules/share`、`/api/friends`、`/api/conversations`。')
  lines.push(`> 演示账号密码统一为 \`${PASSWORD}\`（可用环境变量 DEMO_PASSWORD 覆盖）；管理端为 \`admin/admin123\`。`)
  lines.push('> 日程统计的是“未来 30 天”（接口默认窗口），跨周重跑造数脚本会生成新的未来日程。')
  lines.push('')

  lines.push('## 1. 账号总表')
  lines.push('')
  lines.push('| 账号 | 密码 | 昵称 | 地图/群聊（角色） | 未来日程 | 忙闲授权群 | 好友 |')
  lines.push('|---|---|---|---|---|---|---|')
  for (const user of users) {
    lines.push(`| ${user.account.username} | ${PASSWORD} | ${user.me.nickname || user.account.nickname} `
      + `| ${mapText(user)} | ${user.schedules.length} 条 | ${grantedGroupText(user)} | ${friendText(user)} |`)
  }
  lines.push('')

  lines.push('## 2. 每人日程明细')
  lines.push('')
  lines.push('| 账号 | 日程标题 | 开始 | 结束 | 共享群 |')
  lines.push('|---|---|---|---|---|')
  for (const user of users) {
    if (user.schedules.length === 0) {
      lines.push(`| ${user.account.username} | - | - | - | ${grantedGroupText(user)} |`)
      continue
    }
    for (const schedule of user.schedules) {
      lines.push(`| ${user.account.username} | ${schedule.title} | ${formatTime(schedule.startTime)} `
        + `| ${formatTime(schedule.endTime)} | ${grantedGroupText(user)} |`)
    }
  }
  lines.push('')

  lines.push('## 3. 群聊/地图总表')
  lines.push('')
  lines.push('| 群聊/地图 | 可见性 | 成员 | 成员（角色） | GROUP 会话 ID |')
  lines.push('|---|---|---|---|---|')
  for (const item of mapIndex.values()) {
    const members = (item.detail.members || [])
      .map((member) => `${member.username}(${member.role})`)
      .join('、') || '-'
    lines.push(`| ${item.detail.name} | ${item.detail.visibility} | ${item.detail.memberCount} `
      + `| ${members} | ${item.conversationId || '-'} |`)
  }
  lines.push('')
  return `${lines.join('\n')}\n`
}

async function main() {
  const users = []
  for (let index = 0; index < ACCOUNTS.length; index += 1) {
    users.push(await collect(ACCOUNTS[index], index))
  }

  const mapIndex = new Map()
  for (const user of users) {
    for (const detail of user.mapDetails) {
      if (!mapIndex.has(detail.id)) {
        mapIndex.set(detail.id, { detail, conversationId: null })
      }
    }
    for (const conversation of user.conversations) {
      if (conversation.type === 'GROUP' && conversation.mapId) {
        const item = mapIndex.get(conversation.mapId)
        if (item && !item.conversationId) item.conversationId = conversation.id
      }
    }
  }

  const problems = []
  for (const user of users) {
    const name = user.account.username
    if (user.maps.length === 0) problems.push(`${name} 没有地图/群聊`)
    if (user.schedules.length < 2) {
      problems.push(`${name} 未来日程只有 ${user.schedules.length} 条（期望 ≥2）`)
    }
    if (!user.shareOptions.some((option) => option.granted)) {
      problems.push(`${name} 没有 FREE_BUSY 群授权`)
    }
  }
  for (const item of mapIndex.values()) {
    if (!item.conversationId) problems.push(`地图 ${item.detail.name} 没有 GROUP 会话`)
  }

  if (VERIFY) {
    if (problems.length > 0) {
      console.error('[report] VERIFY FAILED:')
      for (const problem of problems) console.error(`  - ${problem}`)
      process.exitCode = 1
      return
    }
    console.log(`[report] VERIFY OK: ${users.length}/${users.length} 账号均有地图/群聊、≥2 条未来日程和群忙闲授权；`
      + `${mapIndex.size} 个群聊会话齐全`)
    return
  }

  const markdown = render(users, mapIndex, formatLocal(new Date()))
  if (OUT_FILE) {
    fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true })
    fs.writeFileSync(OUT_FILE, markdown, 'utf8')
    console.log(`[report] 已写入 ${OUT_FILE}`)
  } else {
    process.stdout.write(markdown)
  }

  if (problems.length > 0) {
    console.error('[report] 发现数据缺口：')
    for (const problem of problems) console.error(`  - ${problem}`)
    process.exitCode = 1
  }
}

main().catch((error) => {
  console.error(`[report] FAILED: ${error.message}`)
  process.exit(1)
})
