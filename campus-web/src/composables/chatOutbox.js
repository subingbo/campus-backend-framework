const KEY_PREFIX = 'campus.chat.outbox.'
export const MAX_OUTBOX = 50

export function outboxKey(userId) {
  return `${KEY_PREFIX}${userId}`
}

export function newClientMsgId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `cm-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function isValidEntry(entry) {
  return !!entry
    && typeof entry.clientMsgId === 'string'
    && Number.isFinite(Number(entry.conversationId))
    && typeof entry.content === 'string'
}

export function loadOutbox(userId, storage = sessionStorage) {
  if (userId == null) return []
  try {
    const raw = storage.getItem(outboxKey(userId))
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed)
      ? parsed.filter(isValidEntry).map((e) => ({ ...e, conversationId: Number(e.conversationId) }))
      : []
  } catch {
    return []
  }
}

export function saveOutbox(userId, entries, storage = sessionStorage) {
  if (userId == null) return
  try {
    if (!entries.length) {
      storage.removeItem(outboxKey(userId))
    } else {
      storage.setItem(outboxKey(userId), JSON.stringify(entries))
    }
  } catch {
    // sessionStorage 不可用时退化为内存队列
  }
}

export function enqueue(entries, entry, limit = MAX_OUTBOX) {
  if (!isValidEntry(entry)) return null
  if (entries.some((e) => e.clientMsgId === entry.clientMsgId)) return entries
  if (entries.length >= limit) return null
  return [...entries, entry]
}

export function dequeue(entries, clientMsgId) {
  return entries.filter((e) => e.clientMsgId !== clientMsgId)
}

export function takeFailed(entries, clientMsgId) {
  const entry = entries.find((e) => e.clientMsgId === clientMsgId) || null
  return { entry, entries: dequeue(entries, clientMsgId) }
}

export function clearOutbox(userId, storage = sessionStorage) {
  if (userId == null) return
  try {
    storage.removeItem(outboxKey(userId))
  } catch {
    // ignore
  }
}

/** 重连补拉历史时按 id 合并，避免覆盖刚发出的消息 */
export function mergeMessages(existing, incoming) {
  const byId = new Map(existing.map((m) => [m.id, m]))
  for (const m of incoming) byId.set(m.id, m)
  return [...byId.values()].sort((a, b) => a.id - b.id)
}
