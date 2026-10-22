import { computed, ref } from 'vue'
import {
  clearOutbox,
  dequeue,
  enqueue,
  loadOutbox,
  newClientMsgId,
  saveOutbox,
  takeFailed
} from './chatOutbox'

/**
 * 全局单例 WebSocket：登录后 connect()，断线指数退避重连。
 * 协议：上行 { type, ... }（CHAT_SEND/TYPING/READ/PING），下行帧通过 on(type, cb) 订阅。
 *
 * 消息可靠性：CHAT_SEND 先进入按用户隔离的待发送队列（sessionStorage），
 * 收到服务端 CHAT_ACK 才出队；断线重连后带同一 clientMsgId 重发，服务端唯一键去重。
 */
export const connected = ref(false)
const outbox = ref([])
const pendingCount = computed(() => outbox.value.length)
let socket = null
let reconnectTimer = null
let reconnectDelay = 1000
let manuallyClosed = false
let outboxUserId = null
const handlers = new Map()

function tokenPayload() {
  const token = localStorage.getItem('accessToken')
  if (!token) return null
  try {
    const part = token.split('.')[1]
    return JSON.parse(atob(part.replace(/-/g, '+').replace(/_/g, '/')))
  } catch {
    return null
  }
}

export function myUserId() {
  const payload = tokenPayload()
  return payload ? Number(payload.userId) : null
}

function scheduleReconnect() {
  if (manuallyClosed || reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connect()
  }, reconnectDelay)
  reconnectDelay = Math.min(reconnectDelay * 2, 15000)
}

function ensureOutbox() {
  const userId = myUserId()
  if (userId === outboxUserId) return
  outboxUserId = userId
  outbox.value = loadOutbox(userId)
}

/** 供页面挂载时同步 sessionStorage 中的待发送队列（不必等 WS 重连） */
export function syncOutbox() {
  ensureOutbox()
}

function persistOutbox() {
  saveOutbox(outboxUserId, outbox.value)
}

function dispatch(frame) {
  const set = handlers.get(frame.type)
  if (set) {
    for (const cb of set) {
      try {
        cb(frame)
      } catch (e) {
        console.error('ws handler error', e)
      }
    }
  }
}

function safeSend(payload) {
  if (!socket || socket.readyState !== WebSocket.OPEN) return false
  try {
    socket.send(JSON.stringify(payload))
    return true
  } catch {
    return false
  }
}

function flushOutbox() {
  if (!socket || socket.readyState !== WebSocket.OPEN) return
  for (const entry of outbox.value) {
    safeSend({
      type: 'CHAT_SEND',
      conversationId: entry.conversationId,
      content: entry.content,
      clientMsgId: entry.clientMsgId
    })
  }
}

export function connect() {
  const token = localStorage.getItem('accessToken')
  if (!token || (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING))) {
    return
  }
  manuallyClosed = false
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  socket = new WebSocket(`${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`)
  socket.onopen = () => {
    connected.value = true
    reconnectDelay = 1000
    ensureOutbox()
    // 重发未 ACK 的消息（同一 clientMsgId，服务端幂等）
    flushOutbox()
    dispatch({ type: 'SOCKET_OPEN' })
  }
  socket.onmessage = (event) => {
    try {
      const frame = JSON.parse(event.data)
      if (frame.type === 'CHAT_ACK' && frame.clientMsgId) {
        outbox.value = dequeue(outbox.value, frame.clientMsgId)
        persistOutbox()
      }
      dispatch(frame)
    } catch {
      // 忽略无法解析的帧
    }
  }
  socket.onclose = () => {
    connected.value = false
    socket = null
    dispatch({ type: 'SOCKET_CLOSE' })
    scheduleReconnect()
  }
  socket.onerror = () => {
    try {
      socket?.close()
    } catch {
      // 忽略
    }
  }
  // 应用层心跳，防止代理空闲断连
  const ping = setInterval(() => {
    if (socket && socket.readyState === WebSocket.OPEN) {
      send({ type: 'PING' })
    } else {
      clearInterval(ping)
    }
  }, 30000)
}

/** 退出登录/换账号时主动断开，避免旧 token 的 WS 会话继续在线 */
export function disconnect() {
  manuallyClosed = true
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (socket) {
    try {
      socket.close()
    } catch {
      // 忽略
    }
    socket = null
  }
  connected.value = false
  dispatch({ type: 'SOCKET_CLOSE' })
}

/** 订阅下行帧，返回取消函数 */
export function on(type, cb) {
  if (!handlers.has(type)) {
    handlers.set(type, new Set())
  }
  handlers.get(type).add(cb)
  return () => handlers.get(type)?.delete(cb)
}

/**
 * 发送一帧。
 * CHAT_SEND 返回 sent（已交给 WS，等待 ACK）/queued（已入队，重连后发）/failed（队列满或参数非法）；
 * 其他控制帧返回 sent/dropped。
 */
export function send(payload) {
  if (!payload) return 'failed'
  if (payload.type !== 'CHAT_SEND') {
    return safeSend(payload) ? 'sent' : 'dropped'
  }
  ensureOutbox()
  const entry = {
    clientMsgId: payload.clientMsgId || newClientMsgId(),
    conversationId: Number(payload.conversationId),
    content: payload.content,
    createdAt: Date.now()
  }
  if (!entry.content || !Number.isFinite(entry.conversationId)) return 'failed'
  const next = enqueue(outbox.value, entry)
  if (!next) return 'failed'
  outbox.value = next
  persistOutbox()
  return safeSend({
    type: 'CHAT_SEND',
    conversationId: entry.conversationId,
    content: entry.content,
    clientMsgId: entry.clientMsgId
  }) ? 'sent' : 'queued'
}

/** 当前会话待发送数量（供 ChatPanel 展示；不在 computed 中触发存储加载） */
export function pendingForConversation(conversationId) {
  const id = Number(conversationId)
  return outbox.value.filter((entry) => entry.conversationId === id).length
}

/** 服务端明确拒绝某条消息：出队并返回内容，供输入框恢复草稿 */
export function takeFailedMessage(clientMsgId) {
  ensureOutbox()
  const result = takeFailed(outbox.value, clientMsgId)
  if (!result.entry) return null
  outbox.value = result.entries
  persistOutbox()
  return result.entry
}

/** 退出登录时清空当前用户的待发送队列 */
export function clearOutboxForCurrentUser() {
  const userId = outboxUserId ?? myUserId()
  clearOutbox(userId)
  outbox.value = []
  outboxUserId = null
}

export function useChatSocket() {
  return {
    connected,
    pendingCount,
    connect,
    disconnect,
    on,
    send,
    myUserId,
    pendingForConversation,
    takeFailedMessage,
    clearOutboxForCurrentUser
  }
}
