#!/usr/bin/env node
/**
 * 演示数据一键造数（非破坏性、可重复执行）。
 *
 * 前置：后端 8080、MySQL、Redis 已启动；Node 22+（原生 fetch/WebSocket）。
 * 用法：
 *   node tools/seed-demo.mjs              # 创建/补齐演示数据
 *   node tools/seed-demo.mjs --verify-only # 只检查，不写入
 *
 * 本脚本不会删除任何现有用户/地图/群/活动；只操作 demo01–demo20 与 [DEMO] 命名空间。
 * 群聊项目里是地图的 1:1 会话，因此 3 个群聊对应 3 张共享地图。
 */
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080'
const WS_BASE = BASE.replace(/^http/, 'ws')
const PASSWORD = process.env.DEMO_PASSWORD || 'demo1234'
const VERIFY_ONLY = process.argv.includes('--verify-only')
const STATE_FILE = path.join('.ai-run', 'demo-seed-state.json')

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
const iso = (date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T`
  + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
const dayLabel = (date) => `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

function nextSaturday(hour, minute) {
  const now = new Date()
  const result = new Date(now)
  const delta = (6 - now.getDay() + 7) % 7
  result.setDate(now.getDate() + delta)
  result.setHours(hour, minute, 0, 0)
  if (result <= now) result.setDate(result.getDate() + 7)
  return result
}

function addDays(days, hour, minute) {
  const result = new Date()
  result.setDate(result.getDate() + days)
  result.setHours(hour, minute, 0, 0)
  return result
}

function yesterdayAt(hour, minute) {
  const result = new Date(Date.now() - 86400000)
  result.setHours(hour, minute, 0, 0)
  return result
}

function addMinutes(minutes) {
  return new Date(Date.now() + minutes * 60000)
}

function buildSpecs() {
  const sat = nextSaturday(15, 0)
  const satLabel = dayLabel(sat)
  const ongoingStart = addMinutes(-5)
  const ongoingEnd = addMinutes(55)
  const endedStart = yesterdayAt(19, 0)
  const endedEnd = yesterdayAt(20, 0)

  return [
    {
      key: 'A',
      name: '[DEMO] 周末去哪儿',
      description: '周六下午的校园小聚：图书馆、咖啡、运动场和南门集合点。',
      visibility: 'MEMBERS',
      owner: 'demo01',
      members: ['demo02', 'demo03', 'demo04', 'demo05', 'demo06', 'demo07'],
      viewer: 'demo07',
      places: [
        {
          name: '东区图书馆', description: '二楼静音区插座多，适合复习和小组讨论。',
          categoryId: 2, longitude: 113.384500, latitude: 23.045900, address: '校园中心区',
          comments: [
            { user: 'demo01', content: '二楼静音区插座很多，适合复习' },
            { user: 'demo03', content: '晚上人少，讨论也不错' }
          ]
        },
        {
          name: '南门咖啡', description: '南门旁的咖啡店，桂花拿铁和靠窗座位很受欢迎。',
          categoryId: 1, longitude: 113.385100, latitude: 23.045200, address: '南门商业街',
          comments: [
            { user: 'demo02', content: '桂花拿铁不错，适合聊天' },
            { user: 'demo04', content: '下午三点后靠窗位置安静' }
          ]
        },
        {
          name: '西区运动场', description: '标准跑道和篮球场，晚上运动的人很多。',
          categoryId: 3, longitude: 113.383500, latitude: 23.045000, address: '西区',
          comments: [{ user: 'demo05', content: '晚上跑步的人多' }]
        },
        {
          name: '一饭·二楼', description: '烧腊窗口出餐快，适合活动前快速吃饭。',
          categoryId: 1, longitude: 113.384900, latitude: 23.046400, address: '第一饭堂',
          comments: [{ user: 'demo06', content: '烧腊窗口排队快' }]
        },
        {
          name: '快递驿站', description: '取快递和寄件点，中午是高峰。',
          categoryId: 5, longitude: 113.383900, latitude: 23.046700, address: '生活区',
          comments: [{ user: 'demo07', content: '取件高峰在中午' }]
        },
        {
          name: '校车站', description: '周末班次较少，出门前先看时刻表。',
          categoryId: 6, longitude: 113.385900, latitude: 23.044800, address: '南门',
          comments: [{ user: 'demo02', content: '周末班次少，注意时间' }]
        }
      ],
      messages: [
        { user: 'demo01', content: '周六下午大家有空吗？想去南门那边转转' },
        { user: 'demo02', content: '有空，南门咖啡新出的桂花拿铁听说不错' },
        { user: 'demo03', content: '我三点前要交作业，三点后可以' },
        { user: 'demo04', content: '我可以带桌游' },
        { user: 'demo05', content: '那先定周六 15:00，南门咖啡见？' },
        { user: 'demo01', content: '好，我把地点加到群地图了' },
        { user: 'demo02', content: '我提前去占座' },
        { user: 'demo03', content: '桌游带 uno 和阿瓦隆' },
        { user: 'demo04', content: '收到，我带牌' },
        { user: 'demo05', content: '晚上要不要一起吃饭' },
        { user: 'demo01', content: '活动结束再定，先去咖啡店' },
        { user: 'demo02', content: '没问题' }
      ],
      activities: [
        {
          title: `周六桌游局 · ${satLabel}`, place: '南门咖啡', start: sat,
          end: new Date(sat.getTime() + 2 * 3600000), maxMembers: 6, category: 'OTHER',
          joins: ['demo02', 'demo03', 'demo04']
        },
        {
          title: `图书馆自习打卡 · ${dayLabel(ongoingStart)}`, place: '东区图书馆',
          start: ongoingStart, end: ongoingEnd, maxMembers: 4, category: 'MEETING',
          joins: ['demo02', 'demo03']
        },
        {
          title: `上周羽毛球 · ${dayLabel(endedStart)}`, place: '西区运动场',
          start: endedStart, end: endedEnd, maxMembers: 10, category: 'COMPETITION',
          joins: []
        }
      ],
      posts: [
        {
          user: 'demo02', place: '南门咖啡',
          content: '周末在这里赶完小组作业，插座很足，桂花拿铁推荐。',
          likes: ['demo01', 'demo03', 'demo04'], comments: [{ user: 'demo03', content: '下次一起去' }]
        },
        {
          user: 'demo03', place: '东区图书馆',
          content: '二楼新开的静音区很适合复习，期末周有救了。',
          likes: ['demo01', 'demo02'], comments: [{ user: 'demo01', content: '期末周约' }]
        },
        {
          user: 'demo06', place: '一饭·二楼',
          content: '烧腊窗口今天不用排队，叉烧饭稳定发挥。',
          likes: ['demo05', 'demo07'], comments: [{ user: 'demo02', content: '明天试试' }]
        }
      ],
      schedules: [
        { user: 'demo01', title: `上午例会 · ${satLabel}`, start: nextSaturday(10, 0), end: nextSaturday(11, 0) },
        { user: 'demo01', title: `晚间自习 · ${satLabel}`, start: nextSaturday(20, 0), end: nextSaturday(21, 0) },
        { user: 'demo02', title: `社团值班 · ${satLabel}`, start: nextSaturday(9, 0), end: nextSaturday(10, 0) },
        { user: 'demo02', title: `运动时间 · ${satLabel}`, start: nextSaturday(17, 0), end: nextSaturday(18, 0) }
      ]
    },
    {
      key: 'B',
      name: '[DEMO] 社团活动策划',
      description: '社团招新、海报打印和活动中心场地协调。',
      visibility: 'MEMBERS',
      owner: 'demo08',
      members: ['demo09', 'demo10', 'demo11', 'demo12', 'demo13', 'demo14'],
      viewer: null,
      places: [
        {
          name: '学生活动中心', description: '招新和社团活动主场，晚上可以借教室。',
          categoryId: 7, longitude: 113.386200, latitude: 23.046100, address: '东区',
          comments: [{ user: 'demo08', content: '场地已经预约过' }, { user: 'demo09', content: '音响可以自带' }]
        },
        {
          name: '社团会议室', description: '小组讨论和策划会，白板笔需要自备。',
          categoryId: 2, longitude: 113.386500, latitude: 23.046300, address: '活动中心二楼',
          comments: [{ user: 'demo10', content: '白板笔要自己带' }]
        },
        {
          name: '打印店', description: '海报和传单打印，晚上九点前可取。',
          categoryId: 5, longitude: 113.385800, latitude: 23.046600, address: '生活区',
          comments: [{ user: 'demo12', content: '海报九点前能取' }]
        },
        {
          name: '北区超市', description: '活动物资采购点，晚上九点后有折扣。',
          categoryId: 4, longitude: 113.386800, latitude: 23.045700, address: '北区',
          comments: [{ user: 'demo11', content: '晚上九点后打折' }]
        },
        {
          name: '体育馆', description: '社团联赛场地，周三闭馆维护。',
          categoryId: 3, longitude: 113.387200, latitude: 23.045200, address: '北区',
          comments: [{ user: 'demo13', content: '周三闭馆维护' }]
        }
      ],
      messages: [
        { user: 'demo08', content: '社团招新碰头定在周几？' },
        { user: 'demo09', content: '周三晚上可以' },
        { user: 'demo10', content: '我周四有课，周三更好' },
        { user: 'demo11', content: '活动中心晚上能借到教室吗' },
        { user: 'demo08', content: '已经预约了学生活动中心' },
        { user: 'demo12', content: '海报我今晚出第一版' },
        { user: 'demo13', content: '打印店几点关门？' },
        { user: 'demo14', content: '九点前都能打' },
        { user: 'demo08', content: '那周三 19:00 活动中心见' },
        { user: 'demo09', content: '收到' }
      ],
      activities: [
        {
          title: `社团招新碰头 · ${dayLabel(addDays(2, 19, 0))}`, place: '学生活动中心',
          start: addDays(2, 19, 0), end: addDays(2, 21, 0), maxMembers: 20, category: 'COMPETITION',
          joins: ['demo09', 'demo10']
        }
      ],
      posts: [],
      schedules: [
        { user: 'demo08', title: `课程设计讨论 · ${dayLabel(addDays(2, 15, 0))}`, start: addDays(2, 15, 0), end: addDays(2, 16, 0) },
        { user: 'demo09', title: `实验课 · ${dayLabel(addDays(2, 14, 0))}`, start: addDays(2, 14, 0), end: addDays(2, 16, 0) }
      ]
    },
    {
      key: 'C',
      name: '[DEMO] 校园美食地图',
      description: '南门奶茶、夜市小吃和食堂窗口，周末觅食集合。',
      visibility: 'PUBLIC',
      owner: 'demo15',
      members: ['demo16', 'demo17', 'demo18', 'demo19', 'demo20', 'demo01', 'demo02', 'demo03'],
      viewer: 'demo20',
      places: [
        {
          name: '一饭烧腊窗口', description: '烧腊饭分量足，饭点排队快。',
          categoryId: 1, longitude: 113.385000, latitude: 23.046500, address: '第一饭堂',
          comments: [{ user: 'demo20', content: '烧腊饭分量足' }]
        },
        {
          name: '南门奶茶街', description: '新开的奶茶街，桂花乌龙少糖很受欢迎。',
          categoryId: 1, longitude: 113.385600, latitude: 23.044900, address: '南门商业街',
          comments: [{ user: 'demo16', content: '桂花乌龙少糖推荐' }, { user: 'demo01', content: '排队大概十分钟' }]
        },
        {
          name: '夜市小吃摊', description: '十点后还有烤冷面和关东煮。',
          categoryId: 1, longitude: 113.386000, latitude: 23.044500, address: '南门夜市',
          comments: [{ user: 'demo17', content: '十点后烤冷面还在' }]
        },
        {
          name: '面包房', description: '早上八点出炉的牛角包很香。',
          categoryId: 1, longitude: 113.385300, latitude: 23.045800, address: '生活区',
          comments: [{ user: 'demo18', content: '八点出炉最香' }]
        },
        {
          name: '水果店', description: '应季水果和切盒，西瓜很甜。',
          categoryId: 4, longitude: 113.384700, latitude: 23.045500, address: '生活区',
          comments: [{ user: 'demo19', content: '西瓜很甜' }]
        }
      ],
      messages: [
        { user: 'demo15', content: '南门新开了奶茶街，有人去过吗' },
        { user: 'demo16', content: '桂花乌龙不错，少糖刚好' },
        { user: 'demo17', content: '夜市十点后还有烤冷面' },
        { user: 'demo18', content: '面包房早上八点出炉' },
        { user: 'demo01', content: '那我周末去打卡' },
        { user: 'demo02', content: '求带' },
        { user: 'demo15', content: '我把这几家都加到美食地图了' },
        { user: 'demo16', content: '水果店也加一下，西瓜很甜' },
        { user: 'demo17', content: '晚上一起去？' },
        { user: 'demo15', content: '可以，六点南门见' }
      ],
      activities: [
        {
          title: `南门夜宵探店 · ${dayLabel(addDays(1, 18, 0))}`, place: '南门奶茶街',
          start: addDays(1, 18, 0), end: addDays(1, 20, 0), maxMembers: 8, category: 'OTHER',
          joins: ['demo16', 'demo17']
        }
      ],
      posts: [
        {
          user: 'demo16', place: '南门奶茶街', content: '新出的桂花乌龙不错，少糖刚好。',
          likes: ['demo15', 'demo17', 'demo01'], comments: [{ user: 'demo17', content: '下次试试' }]
        },
        {
          user: 'demo17', place: '夜市小吃摊', content: '十点后还有烤冷面，夜宵首选。',
          likes: ['demo15', 'demo16'], comments: [{ user: 'demo18', content: '十点还有吗' }]
        },
        {
          user: 'demo18', place: '面包房', content: '早上八点出炉的牛角包很香。',
          likes: ['demo15', 'demo19'], comments: [{ user: 'demo20', content: '早起去买' }]
        }
      ],
      schedules: []
    }
  ]
}

const PRIVATE_MESSAGES = [
  { user: 'demo01', content: '周六桌游局你来吗？' },
  { user: 'demo02', content: '来，我带桌游' },
  { user: 'demo01', content: '好，下午三点南门咖啡' },
  { user: 'demo02', content: '收到，我提前占座' },
  { user: 'demo01', content: '顺便把奶茶店也加进美食地图了' },
  { user: 'demo02', content: '看到了，晚上可以去' }
]

const state = { generatedAt: new Date().toISOString(), users: {}, maps: {} }
const summary = { created: 0, skipped: 0, messagesSent: 0 }

function log(message) {
  console.log(`[demo] ${message}`)
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

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function loadAccounts(allowCreate) {
  const users = {}
  for (let index = 0; index < ACCOUNTS.length; index += 1) {
    const account = ACCOUNTS[index]
    const xff = `10.250.0.${index + 1}`
    if (allowCreate) {
      const registered = await jsonFetch('POST', '/api/auth/register', null, {
        username: account.username,
        password: PASSWORD,
        nickname: account.nickname
      })
      const existed = registered.status === 400
        && /已存在/.test(registered.json?.message || '')
      if (registered.status !== 200 && !existed) {
        throw new Error(`register ${account.username} -> HTTP ${registered.status} ${registered.json?.message || ''}`)
      }
      if (registered.status === 200) summary.created += 1
      else summary.skipped += 1
    }
    const login = await api('POST', '/api/auth/login', null, {
      username: account.username,
      password: PASSWORD
    }, xff)
    const me = await api('GET', '/api/users/me', login.accessToken)
    users[account.username] = {
      ...account,
      id: me.id,
      token: login.accessToken
    }
    state.users[account.username] = { id: me.id, nickname: account.nickname }
  }
  log(`accounts ready: ${Object.keys(users).length}`)
  return users
}

function mapMatches(maps, name) {
  return (maps || []).filter((map) => map.name === name)
}

async function ensureMap(spec, users, allowCreate) {
  const owner = users[spec.owner]
  const matches = mapMatches(await api('GET', '/api/maps', owner.token), spec.name)
  if (matches.length > 1) {
    throw new Error(`地图名重复：${spec.name} -> ${matches.map((item) => item.id).join(', ')}`)
  }
  if (matches.length === 1) {
    const detail = await api('GET', `/api/maps/${matches[0].id}`, owner.token)
    if (detail.myRole !== 'OWNER') {
      throw new Error(`地图 ${spec.name} 已存在，但 ${spec.owner} 不是 OWNER；请人工处理`)
    }
    summary.skipped += 1
    return {
      mapId: detail.id,
      groupId: detail.groupId,
      inviteCode: detail.inviteCode,
      created: false
    }
  }
  if (!allowCreate) {
    throw new Error(`缺少地图 ${spec.name}`)
  }
  const created = await api('POST', '/api/groups', owner.token, {
    name: spec.name,
    description: spec.description,
    mapName: spec.name,
    visibility: spec.visibility
  })
  summary.created += 1
  return {
    mapId: created.mapId,
    groupId: created.groupId,
    inviteCode: created.inviteCode,
    created: true
  }
}

async function ensureMembers(spec, map, users, allowCreate) {
  const owner = users[spec.owner]
  const detail = await api('GET', `/api/maps/${map.mapId}`, owner.token)
  const memberIds = new Set((detail.members || []).map((member) => member.userId))
  for (const username of spec.members) {
    const user = users[username]
    if (!memberIds.has(user.id)) {
      if (!allowCreate) throw new Error(`地图 ${spec.name} 缺少成员 ${username}`)
      await api('POST', '/api/groups/join', user.token, { inviteCode: map.inviteCode })
      memberIds.add(user.id)
      summary.created += 1
    } else {
      summary.skipped += 1
    }
  }
  if (spec.viewer) {
    if (!allowCreate) {
      // verify-only 下只检查角色；真实角色读取见地图详情
      const viewer = (detail.members || []).find((member) => member.userId === users[spec.viewer].id)
      if (viewer && viewer.role !== 'VIEWER') {
        throw new Error(`地图 ${spec.name} 成员 ${spec.viewer} 不是 VIEWER`)
      }
    } else {
      await api('PUT', `/api/maps/${map.mapId}/members/${users[spec.viewer].id}`,
        owner.token, { role: 'VIEWER' })
    }
  }
}

async function ensurePlaces(spec, map, users, allowCreate) {
  const owner = users[spec.owner]
  const places = await api('GET', `/api/maps/${map.mapId}/places`, owner.token)
  const result = {}
  for (const place of spec.places) {
    const existing = places.find((item) => item.name === place.name)
    let placeId
    if (existing) {
      placeId = existing.id
      summary.skipped += 1
    } else {
      if (!allowCreate) throw new Error(`地图 ${spec.name} 缺少地点 ${place.name}`)
      const added = await api('POST', `/api/maps/${map.mapId}/places`, owner.token, {
        name: place.name,
        description: place.description,
        categoryId: place.categoryId,
        longitude: place.longitude,
        latitude: place.latitude,
        address: place.address,
        confirmNew: true
      })
      if (!added.added || !added.place?.id) {
        throw new Error(`添加地点失败：${place.name} ${JSON.stringify(added).slice(0, 200)}`)
      }
      placeId = added.place.id
      summary.created += 1
    }
    result[place.name] = placeId
    await ensurePlaceComments(spec, map, place, placeId, users, allowCreate)
  }
  return result
}

async function ensurePlaceComments(spec, map, place, placeId, users, allowCreate) {
  const comments = await api('GET',
    `/api/maps/${map.mapId}/places/${placeId}/comments`, users[spec.owner].token)
  const existing = new Set((comments || []).map((comment) => comment.content))
  for (const comment of place.comments || []) {
    if (existing.has(comment.content)) {
      summary.skipped += 1
      continue
    }
    if (!allowCreate) throw new Error(`地点 ${place.name} 缺少评论：${comment.content}`)
    await api('POST', `/api/maps/${map.mapId}/places/${placeId}/comments`,
      users[comment.user].token, { content: comment.content })
    existing.add(comment.content)
    summary.created += 1
  }
}

async function ensureConversation(spec, map, users, allowCreate) {
  const owner = users[spec.owner]
  if (allowCreate) {
    const conversation = await api('GET', `/api/conversations/maps/${map.mapId}`, owner.token)
    return conversation.id
  }
  const conversations = await api('GET', '/api/conversations', owner.token)
  const existing = (conversations || []).find((item) =>
    item.type === 'GROUP' && item.mapId === map.mapId)
  if (!existing) throw new Error(`缺少群聊会话：${spec.name}`)
  return existing.id
}

async function ensureMessages(conversationId, messages, users, allowCreate, readerToken) {
  const existing = await api('GET',
    `/api/conversations/${conversationId}/messages?limit=100`, readerToken)
  const contents = new Set((existing || []).map((message) => message.content))
  for (const message of messages) {
    if (contents.has(message.content)) {
      summary.skipped += 1
      continue
    }
    if (!allowCreate) throw new Error(`会话 ${conversationId} 缺少消息：${message.content}`)
    await sendWsMessage(users[message.user].token, conversationId, message.content)
    contents.add(message.content)
    summary.messagesSent += 1
    summary.created += 1
  }
  if (allowCreate && summary.messagesSent > 0) {
    const deadline = Date.now() + 5000
    while (Date.now() < deadline) {
      await sleep(200)
      const latest = await api('GET',
        `/api/conversations/${conversationId}/messages?limit=100`, readerToken)
      const latestContents = new Set((latest || []).map((message) => message.content))
      if (messages.every((message) => latestContents.has(message.content))) {
        return
      }
    }
    throw new Error(`消息落库确认超时：conversation=${conversationId}`)
  }
}

async function sendWsMessage(token, conversationId, content) {
  const socket = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(token)}`)
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.close()
      reject(new Error('WebSocket 连接超时'))
    }, 5000)
    socket.addEventListener('open', () => {
      clearTimeout(timer)
      resolve()
    })
    socket.addEventListener('error', () => {
      clearTimeout(timer)
      reject(new Error('WebSocket 连接失败'))
    })
  })
  socket.send(JSON.stringify({ type: 'CHAT_SEND', conversationId, content }))
  await sleep(120)
  socket.close()
}

async function ensureFriends(users, allowCreate) {
  await ensureFriend('demo01', 'demo02', users, allowCreate)
  await ensureFriend('demo01', 'demo03', users, allowCreate)
  await ensurePendingFriend('demo04', 'demo01', users, allowCreate)
}

async function ensureFriend(a, b, users, allowCreate) {
  const userA = users[a]
  const userB = users[b]
  const friends = await api('GET', '/api/friends', userA.token)
  if ((friends || []).some((friend) => friend.userId === userB.id)) {
    summary.skipped += 2
    return
  }
  let requests = await api('GET', '/api/friends/requests', userA.token)
  let pending = (requests.received || []).find((request) =>
    request.fromUserId === userB.id && request.status === 0)
  if (pending) {
    if (!allowCreate) throw new Error(`${a} 与 ${b} 尚未成为好友`)
    await api('POST', `/api/friends/requests/${pending.id}/accept`, userA.token)
    summary.created += 2
    return
  }
  pending = (requests.sent || []).find((request) =>
    request.toUserId === userB.id && request.status === 0)
  if (pending) {
    if (!allowCreate) throw new Error(`${a} 与 ${b} 尚未成为好友`)
    await api('POST', `/api/friends/requests/${pending.id}/accept`, userB.token)
    summary.created += 2
    return
  }
  if (!allowCreate) throw new Error(`${a} 与 ${b} 缺少好友关系/请求`)
  await api('POST', '/api/friends/requests', userA.token, {
    userId: userB.id,
    message: '演示数据好友申请'
  })
  requests = await api('GET', '/api/friends/requests', userB.token)
  const created = (requests.received || []).find((request) =>
    request.fromUserId === userA.id && request.status === 0)
  if (!created) throw new Error(`好友申请未创建：${a} -> ${b}`)
  await api('POST', `/api/friends/requests/${created.id}/accept`, userB.token)
  summary.created += 2
}

async function ensurePendingFriend(requester, target, users, allowCreate) {
  const from = users[requester]
  const to = users[target]
  const friends = await api('GET', '/api/friends', from.token)
  if ((friends || []).some((friend) => friend.userId === to.id)) {
    summary.skipped += 1
    return
  }
  const toRequests = await api('GET', '/api/friends/requests', to.token)
  const pending = (toRequests.received || []).find((request) =>
    request.fromUserId === from.id && request.status === 0)
  if (pending) {
    summary.skipped += 1
    return
  }
  if (!allowCreate) throw new Error(`缺少待处理好友申请：${requester} -> ${target}`)
  await api('POST', '/api/friends/requests', from.token, {
    userId: to.id,
    message: '演示待处理好友申请'
  })
  summary.created += 1
}

async function ensurePrivateConversation(users, allowCreate) {
  const demo01 = users.demo01
  const demo02 = users.demo02
  let conversationId
  if (allowCreate) {
    const conversation = await api('POST', '/api/conversations/private', demo01.token, {
      userId: demo02.id
    })
    conversationId = conversation.id
  } else {
    const conversations = await api('GET', '/api/conversations', demo01.token)
    const existing = (conversations || []).find((item) =>
      item.type === 'PRIVATE' && item.peerUserId === demo02.id)
    if (!existing) throw new Error('缺少 demo01–demo02 私聊会话')
    conversationId = existing.id
  }
  await ensureMessages(conversationId, PRIVATE_MESSAGES, users, allowCreate, demo01.token)
  return conversationId
}

async function ensureActivities(spec, map, placeIds, users, allowCreate) {
  const owner = users[spec.owner]
  const existing = await api('GET', '/api/activities/mine?role=created', owner.token)
  const result = {}
  for (const activity of spec.activities) {
    const found = (existing || []).find((item) => item.title === activity.title)
    let id
    if (found) {
      id = found.id
      summary.skipped += 1
    } else {
      if (!allowCreate) throw new Error(`缺少活动：${activity.title}`)
      const created = await api('POST', '/api/activities', owner.token, {
        placeId: placeIds[activity.place],
        title: activity.title,
        description: '演示活动',
        startTime: iso(activity.start),
        endTime: iso(activity.end),
        maxMembers: activity.maxMembers,
        category: activity.category,
        stops: []
      })
      id = created.id
      summary.created += 1
    }
    result[activity.title] = id
    for (const username of activity.joins || []) {
      const detail = await api('GET', `/api/activities/${id}`, users[username].token)
      if (detail.phase === 'ENDED') continue
      if (detail.joined) {
        summary.skipped += 1
        continue
      }
      if (!allowCreate) throw new Error(`活动 ${activity.title} 缺少报名：${username}`)
      await api('POST', `/api/activities/${id}/join`, users[username].token)
      summary.created += 1
    }
  }
  return result
}

async function ensurePosts(spec, placeIds, users, allowCreate) {
  const result = {}
  for (const post of spec.posts || []) {
    const placeId = placeIds[post.place]
    const posts = await api('GET', `/api/places/${placeId}/posts?size=50`, users[spec.owner].token)
    const found = (posts.list || []).find((item) => item.content === post.content)
    let postId
    if (found) {
      postId = found.id
      summary.skipped += 1
    } else {
      if (!allowCreate) throw new Error(`地点 ${post.place} 缺少动态：${post.content}`)
      const created = await api('POST', '/api/posts', users[post.user].token, {
        placeId,
        content: post.content
      })
      postId = created.id
      summary.created += 1
    }
    result[post.content] = postId
    for (const username of post.likes || []) {
      const status = await api('GET', `/api/posts/${postId}/like`, users[username].token)
      if (status.liked) {
        summary.skipped += 1
        continue
      }
      if (!allowCreate) throw new Error(`动态未被点赞：${username} -> ${post.content}`)
      await api('POST', `/api/posts/${postId}/like`, users[username].token)
      summary.created += 1
    }
    const comments = await api('GET', `/api/posts/${postId}/comments?size=50`, users[spec.owner].token)
    const commentContents = new Set((comments.list || []).map((item) => item.content))
    for (const comment of post.comments || []) {
      if (commentContents.has(comment.content)) {
        summary.skipped += 1
        continue
      }
      if (!allowCreate) throw new Error(`动态缺少评论：${comment.content}`)
      await api('POST', `/api/posts/${postId}/comments`, users[comment.user].token, {
        content: comment.content
      })
      commentContents.add(comment.content)
      summary.created += 1
    }
  }
  return result
}

async function ensureSchedules(spec, map, users, allowCreate) {
  const result = {}
  for (const schedule of spec.schedules || []) {
    const user = users[schedule.user]
    const list = await api('GET', '/api/schedules', user.token)
    const found = (list || []).find((item) => item.title === schedule.title)
    let id
    if (found) {
      id = found.id
      summary.skipped += 1
    } else {
      if (!allowCreate) throw new Error(`缺少日程：${schedule.title}`)
      const created = await api('POST', '/api/schedules', user.token, {
        title: schedule.title,
        startTime: iso(schedule.start),
        endTime: iso(schedule.end),
        note: null
      })
      id = created.id
      summary.created += 1
    }
    result[schedule.title] = id
    if (!allowCreate) continue
    await api('PUT', '/api/schedules/share', user.token, {
      targetType: 'GROUP',
      targetId: map.groupId,
      grant: true
    })
  }
  return result
}

async function main() {
  log(`mode=${VERIFY_ONLY ? 'verify-only' : 'seed'} base=${BASE}`)
  const users = await loadAccounts(!VERIFY_ONLY)
  const specs = buildSpecs()

  for (const spec of specs) {
    const map = await ensureMap(spec, users, !VERIFY_ONLY)
    await ensureMembers(spec, map, users, !VERIFY_ONLY)
    const placeIds = await ensurePlaces(spec, map, users, !VERIFY_ONLY)
    const conversationId = await ensureConversation(spec, map, users, !VERIFY_ONLY)
    await ensureMessages(conversationId, spec.messages, users, !VERIFY_ONLY,
      users[spec.owner].token)
    const activityIds = await ensureActivities(spec, map, placeIds, users, !VERIFY_ONLY)
    const postIds = await ensurePosts(spec, placeIds, users, !VERIFY_ONLY)
    const scheduleIds = await ensureSchedules(spec, map, users, !VERIFY_ONLY)

    state.maps[spec.key] = {
      name: spec.name,
      mapId: map.mapId,
      groupId: map.groupId,
      conversationId,
      places: placeIds,
      activities: activityIds,
      posts: postIds,
      schedules: scheduleIds,
      members: [spec.owner, ...spec.members]
    }
    log(`${spec.key} ${spec.name}: places=${Object.keys(placeIds).length} `
      + `messages=${spec.messages.length} activities=${Object.keys(activityIds).length} `
      + `posts=${Object.keys(postIds).length}`)
  }

  await ensureFriends(users, !VERIFY_ONLY)
  const privateConversationId = await ensurePrivateConversation(users, !VERIFY_ONLY)

  if (!VERIFY_ONLY) {
    state.privateConversationId = privateConversationId
    state.created = summary.created
    state.skipped = summary.skipped
    fs.mkdirSync(path.dirname(STATE_FILE), { recursive: true })
    fs.writeFileSync(STATE_FILE, `${JSON.stringify(state, null, 2)}\n`, 'utf8')
  }

  const accountLines = ACCOUNTS.map((account) => {
    const maps = specs.filter((spec) => spec.owner === account.username
      || spec.members.includes(account.username)).map((spec) => spec.key)
    return `${account.username} ${account.nickname} maps=${maps.join(',') || '-'}`
  })
  console.log('\n演示账号（密码统一 demo1234）：')
  for (const line of accountLines) console.log(`  ${line}`)
  console.log(`\n${VERIFY_ONLY ? 'VERIFY OK' : 'SEED OK'} `
    + `created=${summary.created} skipped=${summary.skipped} messagesSent=${summary.messagesSent}`)
  console.log('推荐演示：demo01（A）、demo08（B）、demo15（C）；admin/admin123 保留用于管理端。')
  if (!VERIFY_ONLY) console.log(`state: ${STATE_FILE}`)
}

main().catch((error) => {
  console.error(`[demo] FAILED: ${error.message}`)
  process.exitCode = 1
})
