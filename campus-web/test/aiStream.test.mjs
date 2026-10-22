import test from 'node:test'
import assert from 'node:assert/strict'
import { createStreamAssistantChat } from '../src/api/sseStream.js'

function sseResponse(text) {
  return new Response(text, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' }
  })
}

test('401 时刷新一次并重试，事件继续处理', async () => {
  const auths = []
  let refreshCalls = 0
  let authLost = 0
  const events = []
  const stream = createStreamAssistantChat({
    fetchImpl: async (_url, options) => {
      auths.push(options.headers.Authorization)
      if (auths.length === 1) return new Response('', { status: 401 })
      return sseResponse('event: status\ndata: 正在思考\n\nevent: delta\ndata: 你好\n\n')
    },
    getToken: () => (auths.length === 0 ? 'old-token' : 'new-token'),
    refresh: async () => { refreshCalls += 1 },
    onAuthLost: () => { authLost += 1 }
  })

  await stream(1, 'hello', (event, payload) => events.push([event, payload]))

  assert.equal(auths.length, 2)
  assert.equal(auths[0], 'Bearer old-token')
  assert.equal(auths[1], 'Bearer new-token')
  assert.equal(refreshCalls, 1)
  assert.equal(authLost, 0)
  assert.deepEqual(events[0], ['status', '正在思考'])
  assert.deepEqual(events[1], ['delta', '你好'])
})

test('刷新失败时触发登出且不重试请求', async () => {
  let fetchCalls = 0
  let authLost = 0
  const events = []
  const stream = createStreamAssistantChat({
    fetchImpl: async () => {
      fetchCalls += 1
      return new Response('', { status: 401 })
    },
    getToken: () => 'expired',
    refresh: async () => { throw new Error('refresh failed') },
    onAuthLost: () => { authLost += 1 }
  })

  await stream(1, 'hello', (event, payload) => events.push([event, payload]))

  assert.equal(fetchCalls, 1)
  assert.equal(authLost, 1)
  assert.deepEqual(events.at(-1), ['error', { message: '登录已过期，请重新登录' }])
})

test('已开始消费事件后流中断不会整体重发', async () => {
  let fetchCalls = 0
  let refreshCalls = 0
  const events = []
  const encoder = new TextEncoder()
  const stream = createStreamAssistantChat({
    fetchImpl: async () => {
      fetchCalls += 1
      const body = new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('event: delta\ndata: 部分内容\n\n'))
          setTimeout(() => controller.error(new Error('boom')), 0)
        }
      })
      return new Response(body, { status: 200 })
    },
    getToken: () => 'token',
    refresh: async () => { refreshCalls += 1 },
    onAuthLost: () => {}
  })

  await stream(1, 'hello', (event, payload) => events.push([event, payload]))

  assert.equal(fetchCalls, 1)
  assert.equal(refreshCalls, 0)
  assert.deepEqual(events[0], ['delta', '部分内容'])
  assert.deepEqual(events.at(-1), ['error', { message: '连接中断' }])
})

test('非 401 的 HTTP 错误直接上报，不刷新', async () => {
  let refreshCalls = 0
  const events = []
  const stream = createStreamAssistantChat({
    fetchImpl: async () => new Response('', { status: 500 }),
    getToken: () => 'token',
    refresh: async () => { refreshCalls += 1 },
    onAuthLost: () => {}
  })

  await stream(1, 'hello', (event, payload) => events.push([event, payload]))

  assert.equal(refreshCalls, 0)
  assert.deepEqual(events.at(-1), ['error', { message: '请求失败（HTTP 500）' }])
})
