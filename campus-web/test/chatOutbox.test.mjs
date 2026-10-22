import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MAX_OUTBOX,
  clearOutbox,
  dequeue,
  enqueue,
  loadOutbox,
  mergeMessages,
  newClientMsgId,
  saveOutbox,
  takeFailed
} from '../src/composables/chatOutbox.js'

function memoryStorage() {
  const map = new Map()
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: (key) => map.delete(key)
  }
}

function entry(id, conversationId = 1, content = 'hi') {
  return { clientMsgId: id, conversationId, content, createdAt: Date.now() }
}

test('入队、重复 clientMsgId 去重、出队', () => {
  let entries = enqueue([], entry('a'))
  entries = enqueue(entries, entry('b'))
  assert.deepEqual(entries.map((e) => e.clientMsgId), ['a', 'b'])

  const duplicate = enqueue(entries, entry('a'))
  assert.equal(duplicate.length, 2)

  const afterAck = dequeue(entries, 'a')
  assert.deepEqual(afterAck.map((e) => e.clientMsgId), ['b'])
})

test('队列达到上限后拒绝新消息', () => {
  let entries = []
  for (let i = 0; i < MAX_OUTBOX; i++) {
    entries = enqueue(entries, entry(`id-${i}`))
  }
  assert.equal(entries.length, MAX_OUTBOX)
  assert.equal(enqueue(entries, entry('overflow')), null)
})

test('按用户隔离持久化，刷新后能恢复', () => {
  const storage = memoryStorage()
  saveOutbox(7, [entry('user7', 3)], storage)
  saveOutbox(8, [entry('user8', 4)], storage)

  assert.deepEqual(loadOutbox(7, storage).map((e) => e.clientMsgId), ['user7'])
  assert.deepEqual(loadOutbox(8, storage).map((e) => e.clientMsgId), ['user8'])
  assert.deepEqual(loadOutbox(9, storage), [])

  clearOutbox(7, storage)
  assert.deepEqual(loadOutbox(7, storage), [])
  assert.deepEqual(loadOutbox(8, storage).map((e) => e.clientMsgId), ['user8'])
})

test('损坏的存储内容不会导致崩溃', () => {
  const storage = memoryStorage()
  storage.setItem('campus.chat.outbox.1', '{not-json')
  assert.deepEqual(loadOutbox(1, storage), [])
})

test('服务端拒绝时取出内容并出队，供输入框恢复', () => {
  const entries = [entry('a'), entry('b')]
  const result = takeFailed(entries, 'a')
  assert.equal(result.entry.content, 'hi')
  assert.deepEqual(result.entries.map((e) => e.clientMsgId), ['b'])
  assert.equal(takeFailed(entries, 'missing').entry, null)
})

test('重连补拉按消息 id 合并去重并排序', () => {
  const merged = mergeMessages(
    [{ id: 2, content: 'old-2' }, { id: 1, content: 'old-1' }],
    [{ id: 2, content: 'new-2' }, { id: 3, content: 'new-3' }]
  )
  assert.deepEqual(merged.map((m) => m.id), [1, 2, 3])
  assert.equal(merged.find((m) => m.id === 2).content, 'new-2')
})

test('clientMsgId 稳定且互不重复', () => {
  const ids = new Set(Array.from({ length: 50 }, () => newClientMsgId()))
  assert.equal(ids.size, 50)
})
