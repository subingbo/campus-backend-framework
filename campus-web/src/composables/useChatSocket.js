import { ref } from 'vue'

/**
 * 全局单例 WebSocket：登录后 connect()，断线指数退避重连。
 * 协议：上行 { type, ... }（CHAT_SEND/TYPING/READ/PING），下行帧通过 on(type, cb) 订阅。
 */
const connected = ref(false)
let socket = null
let reconnectTimer = null
let reconnectDelay = 1000
let manuallyClosed = false
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
  }
  socket.onmessage = (event) => {
    try {
      dispatch(JSON.parse(event.data))
    } catch {
      // 忽略无法解析的帧
    }
  }
  socket.onclose = () => {
    connected.value = false
    socket = null
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

/** 订阅下行帧，返回取消函数 */
export function on(type, cb) {
  if (!handlers.has(type)) {
    handlers.set(type, new Set())
  }
  handlers.get(type).add(cb)
  return () => handlers.get(type)?.delete(cb)
}

export function send(payload) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload))
  }
}

export function useChatSocket() {
  return { connected, connect, on, send, myUserId }
}
