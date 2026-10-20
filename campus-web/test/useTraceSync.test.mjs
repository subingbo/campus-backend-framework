import test from 'node:test'
import assert from 'node:assert/strict'
import { useTraceSync } from '../src/composables/useTraceSync.js'
import { costHint, formatCostAmount } from '../src/utils/traceFormat.js'

function createDeferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function createApi() {
  const calls = { list: [], detail: [], events: [] }
  const make = (method) => (...args) => {
    const deferred = createDeferred()
    calls[method].push({ args, deferred })
    return deferred.promise
  }
  return {
    calls,
    api: {
      list: make('list'),
      detail: make('detail'),
      events: make('events')
    },
    resolve(method, value, index) {
      const call = index == null
        ? calls[method].find((item) => !item.settled)
        : calls[method][index]
      assert.ok(call, `no pending ${method} call`)
      call.settled = true
      call.deferred.resolve(value)
    },
    reject(method, error, index) {
      const call = index == null
        ? calls[method].find((item) => !item.settled)
        : calls[method][index]
      assert.ok(call, `no pending ${method} call`)
      call.settled = true
      call.deferred.reject(error)
    }
  }
}

function createScheduler() {
  let nextId = 1
  const tasks = new Map()
  return {
    setTimeout(fn, delay) {
      const id = nextId++
      tasks.set(id, { fn, delay })
      return id
    },
    clearTimeout(id) {
      tasks.delete(id)
    },
    count() {
      return tasks.size
    },
    async runNext() {
      const entry = tasks.entries().next()
      if (entry.done) return false
      const [id, task] = entry.value
      tasks.delete(id)
      task.fn()
      await flush()
      return true
    }
  }
}

function createVisibility() {
  let hidden = false
  const listeners = new Set()
  return {
    isHidden: () => hidden,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    setHidden(value) {
      hidden = value
      for (const listener of [...listeners]) listener()
    },
    listenerCount() {
      return listeners.size
    }
  }
}

async function flush() {
  for (let index = 0; index < 4; index += 1) {
    await new Promise((resolve) => setImmediate(resolve))
  }
}

function createHarness(options = {}) {
  const api = createApi()
  const scheduler = createScheduler()
  const visibility = createVisibility()
  let clock = 0
  const sync = useTraceSync({
    traceApi: api.api,
    intervalMs: 8000,
    drainIntervalMs: 1000,
    eventPageSize: options.eventPageSize ?? 200,
    maxDrainPagesPerTick: options.maxDrainPagesPerTick ?? 3,
    maxDrainAttempts: options.maxDrainAttempts ?? 15,
    visibility,
    setTimeoutImpl: scheduler.setTimeout,
    clearTimeoutImpl: scheduler.clearTimeout,
    now: () => ++clock
  })
  return { sync, api, scheduler, visibility }
}

function session(sessionId, overrides = {}) {
  return {
    sessionId,
    status: 'RUNNING',
    lastSeq: 0,
    pendingEvents: 0,
    eventCount: 0,
    droppedCount: 0,
    ...overrides
  }
}

function event(seq, overrides = {}) {
  return {
    seq,
    type: 'llm/response',
    time: seq * 1000,
    status: 'SUCCESS',
    ...overrides
  }
}

function eventsResponse(seqs, total = seqs.length) {
  return { list: seqs.map((item) => (typeof item === 'number' ? event(item) : item)), total }
}

async function openSession(harness, { detail, events, row } = {}) {
  const opening = harness.sync.openSession(row || { sessionId: 's1', status: 'RUNNING' })
  await flush()
  assert.equal(harness.api.calls.detail.length, 1)
  harness.api.resolve('detail', detail || session('s1'))
  await flush()
  assert.equal(harness.api.calls.events.length, 1)
  harness.api.resolve('events', events || eventsResponse([], 0))
  await flush()
  await opening
  await flush()
}

test('terminal session keeps draining until the tail watermark is caught up', async () => {
  const harness = createHarness()
  await openSession(harness, {
    detail: session('s1', { lastSeq: 2 }),
    events: eventsResponse([1, 2], 2)
  })
  assert.equal(harness.sync.historyExhausted.value, true)
  assert.equal(harness.sync.syncCursor.value, 2)

  const refreshing = harness.sync.refreshDetail(true)
  await flush()
  harness.api.resolve('detail', session('s1', { status: 'COMPLETED', lastSeq: 5, complete: true }))
  await flush()
  assert.equal(harness.api.calls.events.length, 2)
  assert.equal(harness.api.calls.events[1].args[1].afterSeq, 2)
  harness.api.resolve('events', eventsResponse([3, 4, 5], 5))
  await flush()
  await refreshing

  assert.deepEqual(harness.sync.events.value.map((item) => item.seq), [1, 2, 3, 4, 5])
  assert.equal(harness.sync.syncState.value, 'caughtUp')
  assert.equal(harness.scheduler.count(), 0)
})

test('terminal session does not claim complete when eventTotal lags lastSeq', async () => {
  const harness = createHarness()
  await openSession(harness, {
    detail: session('s1', { lastSeq: 2 }),
    events: eventsResponse([1, 2], 2)
  })

  const refreshing = harness.sync.refreshDetail(true)
  await flush()
  harness.api.resolve('detail', session('s1', { status: 'COMPLETED', lastSeq: 5 }))
  await flush()
  harness.api.resolve('events', eventsResponse([3], 3))
  await flush()
  await refreshing

  assert.equal(harness.sync.syncCursor.value, 3)
  assert.equal(harness.sync.syncState.value, 'draining')
  assert.equal(harness.scheduler.count(), 1)

  await harness.scheduler.runNext()
  harness.api.resolve('detail', session('s1', { status: 'COMPLETED', lastSeq: 5 }), 2)
  await flush()
  harness.api.resolve('events', eventsResponse([4, 5], 5), 2)
  await flush()

  assert.deepEqual(harness.sync.events.value.map((item) => item.seq), [1, 2, 3, 4, 5])
  assert.equal(harness.sync.syncState.value, 'caughtUp')
  assert.equal(harness.scheduler.count(), 0)
})

test('terminal session with writer pending events keeps draining', async () => {
  const harness = createHarness()
  await openSession(harness, {
    detail: session('s1', { lastSeq: 2 }),
    events: eventsResponse([1, 2], 2)
  })

  const refreshing = harness.sync.refreshDetail(true)
  await flush()
  harness.api.resolve('detail', session('s1', {
    status: 'COMPLETED', lastSeq: 3, pendingEvents: 1
  }))
  await flush()
  harness.api.resolve('events', eventsResponse([], 2))
  await flush()
  await refreshing

  assert.equal(harness.sync.syncState.value, 'draining')
  assert.equal(harness.scheduler.count(), 1)

  await harness.scheduler.runNext()
  await flush()
  harness.api.resolve('detail', session('s1', {
    status: 'COMPLETED', lastSeq: 3, pendingEvents: 0
  }))
  await flush()
  harness.api.resolve('events', eventsResponse([3], 3))
  await flush()

  assert.deepEqual(harness.sync.events.value.map((item) => item.seq), [1, 2, 3])
  assert.equal(harness.sync.syncState.value, 'caughtUp')
  assert.equal(harness.scheduler.count(), 0)
})

test('more than three pages in one tick keeps drainPending and continues next tick', async () => {
  const harness = createHarness()
  const initial = Array.from({ length: 1000 }, (_, index) => event(index + 1))
  await openSession(harness, {
    detail: session('s1', { lastSeq: 1000 }),
    events: { list: initial, total: 1000 }
  })
  assert.equal(harness.sync.historyExhausted.value, true)

  const refreshing = harness.sync.refreshDetail(true)
  await flush()
  harness.api.resolve('detail', session('s1', { status: 'COMPLETED', lastSeq: 2000 }))
  await flush()
  for (let page = 0; page < 3; page += 1) {
    await flush()
    const start = 1001 + page * 200
    harness.api.resolve('events', eventsResponse(
      Array.from({ length: 200 }, (_, index) => event(start + index)), 2000
    ), page + 1)
  }
  await flush()
  await refreshing

  assert.equal(harness.sync.events.value.length, 1600)
  assert.equal(harness.sync.drainPending.value, true)
  assert.equal(harness.scheduler.count(), 1)

  await harness.scheduler.runNext()
  await flush()
  harness.api.resolve('detail', session('s1', { status: 'COMPLETED', lastSeq: 2000 }))
  await flush()
  harness.api.resolve('events', eventsResponse(
    Array.from({ length: 200 }, (_, index) => event(1601 + index)), 2000
  ))
  await flush()
  harness.api.resolve('events', eventsResponse(
    Array.from({ length: 200 }, (_, index) => event(1801 + index)), 2000
  ))
  await flush()
  harness.api.resolve('events', eventsResponse([], 2000))
  await flush()
  await flush()

  assert.equal(harness.sync.events.value.length, 2000)
  assert.equal(harness.sync.syncState.value, 'caughtUp')
  assert.equal(harness.scheduler.count(), 0)
})

test('late response from a previous session cannot overwrite the new session', async () => {
  const harness = createHarness()
  const openingA = harness.sync.openSession({ sessionId: 's1', status: 'RUNNING' })
  await flush()
  const openingB = harness.sync.openSession({ sessionId: 's2', status: 'RUNNING' })
  await flush()
  assert.equal(harness.sync.selected.value.sessionId, 's2')

  harness.api.resolve('detail', session('s1'), 0)
  await flush()
  assert.equal(harness.sync.selected.value.sessionId, 's2')

  harness.api.resolve('detail', session('s2'), 1)
  await flush()
  harness.api.resolve('events', eventsResponse([1], 1), 0)
  await flush()
  await openingA
  await openingB

  assert.equal(harness.sync.selected.value.sessionId, 's2')
  assert.deepEqual(harness.sync.events.value.map((item) => item.seq), [1])
})

test('stale incremental response is ignored after filters change', async () => {
  const harness = createHarness()
  await openSession(harness, {
    detail: session('s1', { lastSeq: 2 }),
    events: eventsResponse([1, 2], 2)
  })

  const refreshing = harness.sync.refreshDetail(true)
  await flush()
  harness.api.resolve('detail', session('s1', { lastSeq: 3 }))
  await flush()
  assert.equal(harness.api.calls.events.length, 2)

  harness.sync.typeFilter.value = 'tool/result'
  const applying = harness.sync.applyFilters()
  await flush()
  assert.equal(harness.api.calls.events.length, 3)

  harness.api.resolve('events', eventsResponse([3]), 1)
  await flush()
  assert.equal(harness.sync.events.value.length, 0)

  harness.api.resolve('events', eventsResponse([event(4, { type: 'tool/result' })], 1), 2)
  await flush()
  await applying
  await refreshing

  assert.deepEqual(harness.sync.events.value.map((item) => item.seq), [4])
  assert.equal(harness.sync.eventTotal.value, 1)
  assert.equal(harness.sync.historyExhausted.value, true)
  assert.equal(harness.sync.syncCursor.value, 4)
})

test('manual refresh queues behind an in-flight auto refresh', async () => {
  const harness = createHarness()
  await openSession(harness, {
    detail: session('s1', { lastSeq: 2 }),
    events: eventsResponse([1, 2], 2)
  })

  const auto = harness.sync.refreshDetail(false)
  await flush()
  assert.equal(harness.api.calls.detail.length, 2)
  harness.sync.refreshDetail(true)
  await flush()
  assert.equal(harness.api.calls.detail.length, 2)

  harness.api.resolve('detail', session('s1', { lastSeq: 2 }), 1)
  await flush()
  harness.api.resolve('events', eventsResponse([], 2), 1)
  await flush()
  await auto
  await flush()

  assert.equal(harness.api.calls.detail.length, 3)
  harness.api.resolve('detail', session('s1', { lastSeq: 2 }), 2)
  await flush()
  harness.api.resolve('events', eventsResponse([], 2), 2)
  await flush()
  assert.equal(harness.sync.events.value.length, 2)
})

test('visibility pause/resume and dispose clean up timers and listeners', async () => {
  const harness = createHarness()
  await openSession(harness, {
    detail: session('s1', { lastSeq: 2 }),
    events: eventsResponse([1, 2], 2)
  })
  assert.equal(harness.scheduler.count(), 1)
  assert.equal(harness.visibility.listenerCount(), 1)

  harness.visibility.setHidden(true)
  assert.equal(harness.scheduler.count(), 0)

  harness.visibility.setHidden(false)
  await flush()
  assert.equal(harness.api.calls.detail.length, 2)
  harness.api.resolve('detail', session('s1', { lastSeq: 2 }), 1)
  await flush()
  harness.api.resolve('events', eventsResponse([], 2), 1)
  await flush()

  harness.sync.dispose()
  assert.equal(harness.visibility.listenerCount(), 0)
  assert.equal(harness.scheduler.count(), 0)
})

test('list errors, refresh errors and true empty are distinguishable', async () => {
  const harness = createHarness()
  const loading = harness.sync.loadSessions(1)
  await flush()
  harness.api.reject('list', new Error('network down'))
  await loading
  assert.equal(harness.sync.listError.value, 'network down')
  assert.equal(harness.sync.sessions.value.length, 0)

  await openSession(harness, {
    detail: session('s1', { lastSeq: 2 }),
    events: eventsResponse([1, 2], 2)
  })
  const refreshing = harness.sync.refreshDetail(true)
  await flush()
  harness.api.reject('detail', new Error('detail down'), 1)
  await flush()
  harness.api.resolve('events', eventsResponse([], 2))
  await flush()
  await refreshing
  assert.equal(harness.sync.selected.value.sessionId, 's1')
  assert.equal(harness.sync.detailError.value, 'detail down')
  assert.equal(harness.sync.events.value.length, 2)

  const emptyHarness = createHarness()
  const emptyLoading = emptyHarness.sync.loadSessions(1)
  await flush()
  emptyHarness.api.resolve('list', { list: [], total: 0 })
  await emptyLoading
  assert.equal(emptyHarness.sync.listError.value, '')
  assert.equal(emptyHarness.sync.sessions.value.length, 0)
})

test('history pages and incremental cursor do not duplicate or skip events', async () => {
  const harness = createHarness()
  await openSession(harness, {
    detail: session('s1', { lastSeq: 5 }),
    events: eventsResponse([1, 2], 5)
  })
  assert.equal(harness.sync.historyExhausted.value, false)
  assert.equal(harness.sync.unloadedCount.value, 3)
  assert.equal(harness.sync.syncCursor.value, 0)

  const more1 = harness.sync.loadMoreEvents()
  await flush()
  harness.api.resolve('events', eventsResponse([3, 4], 5), 1)
  await more1
  assert.equal(harness.sync.historyExhausted.value, false)

  const more2 = harness.sync.loadMoreEvents()
  await flush()
  harness.api.resolve('events', eventsResponse([5], 5), 2)
  await more2
  assert.equal(harness.sync.historyExhausted.value, true)
  assert.equal(harness.sync.syncCursor.value, 5)

  const refreshing = harness.sync.refreshDetail(true)
  await flush()
  harness.api.resolve('detail', session('s1', { status: 'COMPLETED', lastSeq: 7 }), 1)
  await flush()
  harness.api.resolve('events', eventsResponse([6, 7], 7), 3)
  await flush()
  await refreshing

  assert.deepEqual(harness.sync.events.value.map((item) => item.seq), [1, 2, 3, 4, 5, 6, 7])
  assert.equal(harness.sync.syncState.value, 'caughtUp')
})

test('cost formatting distinguishes known zero, tiny positive and unknown', () => {
  assert.equal(formatCostAmount(0, 'CNY'), '¥0.00')
  assert.equal(formatCostAmount(0.00001, 'CNY'), '<¥0.0001')
  assert.equal(formatCostAmount(0.00092, 'CNY'), '¥0.0009')
  assert.equal(formatCostAmount(null, 'CNY'), '不可估算')
  assert.equal(costHint({ costStatus: 'UNKNOWN_RATE' }), '费率未配置')
  assert.equal(costHint({ costStatus: 'PARTIAL' }), '存在丢失事件，成本不可估算')
  assert.equal(costHint({ costStatus: 'KNOWN', costCurrency: 'CNY', costPricingVersion: 'demo' }),
    '按 CNY · 演示费率 demo 估算')
})
