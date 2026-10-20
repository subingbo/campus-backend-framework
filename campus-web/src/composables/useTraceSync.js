import { computed, ref } from 'vue'

const DEFAULT_PAGE_SIZE = 20
const DEFAULT_EVENT_PAGE_SIZE = 200

function errorMessage(error) {
  return error?.message || '请求失败'
}

function asList(value) {
  return Array.isArray(value) ? value : []
}

function mergeBySeq(base, incoming) {
  const seen = new Set(asList(base).map((event) => event?.seq).filter((seq) => seq != null))
  const merged = [...asList(base)]
  for (const event of asList(incoming)) {
    if (event?.seq == null || seen.has(event.seq)) continue
    seen.add(event.seq)
    merged.push(event)
  }
  return merged.sort((a, b) => Number(a.seq) - Number(b.seq))
}

function maxSeq(events) {
  return asList(events).reduce((max, event) => {
    const seq = Number(event?.seq)
    return Number.isFinite(seq) ? Math.max(max, seq) : max
  }, 0)
}

function defaultVisibility() {
  return {
    isHidden: () => typeof document !== 'undefined' && document.visibilityState === 'hidden',
    subscribe: (listener) => {
      if (typeof document === 'undefined') return () => {}
      document.addEventListener('visibilitychange', listener)
      return () => document.removeEventListener('visibilitychange', listener)
    }
  }
}

/**
 * Trace Center 的同步状态机。
 *
 * 关键不变量：
 * - historyExhausted 只由历史分页响应更新；syncCursor 只由成功的增量响应（或完整历史）推进。
 * - 历史未加载完时不自动追加增量，避免 1–50 已加载、51–100 被增量插入造成缺口。
 * - 会话终态后继续补拉，直到事件总数和 lastSeq 水位都追平；追不平则明确停止并提示。
 * - 所有响应都校验 epoch + sessionId + filterSignature，旧请求不能覆盖新页面。
 */
export function useTraceSync(options = {}) {
  const traceApi = options.traceApi
  if (!traceApi) {
    throw new Error('useTraceSync requires traceApi')
  }
  const intervalMs = options.intervalMs ?? 8000
  const drainIntervalMs = options.drainIntervalMs ?? 1000
  const pageSize = options.pageSize ?? DEFAULT_PAGE_SIZE
  const eventPageSize = options.eventPageSize ?? DEFAULT_EVENT_PAGE_SIZE
  const maxDrainPages = options.maxDrainPagesPerTick ?? 3
  const maxDrainAttempts = options.maxDrainAttempts ?? 15
  const now = options.now ?? (() => Date.now())
  const setTimeoutImpl = options.setTimeoutImpl
    ?? ((fn, delay) => setTimeout(fn, delay))
  const clearTimeoutImpl = options.clearTimeoutImpl
    ?? ((timer) => clearTimeout(timer))
  const visibility = options.visibility ?? defaultVisibility()

  // 列表
  const sessions = ref([])
  const total = ref(0)
  const page = ref(1)
  const statusFilter = ref('')
  const loading = ref(false)
  const listError = ref('')
  const listPollError = ref('')
  const listUpdatedAt = ref(null)

  // 详情
  const selected = ref(null)
  const detailLoading = ref(false)
  const detailError = ref('')
  const detailUpdatedAt = ref(null)
  const refreshing = ref(false)

  // 事件
  const events = ref([])
  const eventTotal = ref(0)
  const eventPage = ref(1)
  const eventsLoading = ref(false)
  const eventsError = ref('')
  const eventsUpdatedAt = ref(null)
  const typeFilter = ref('')
  const callIdFilter = ref('')
  const stepFilter = ref('')
  const viewMode = ref('timeline')

  // 两套游标与补拉状态
  const historyExhausted = ref(false)
  const syncCursor = ref(0)
  const drainPending = ref(false)
  const drainStalled = ref(false)

  const unloadedCount = computed(() => {
    if (historyExhausted.value) return 0
    return Math.max(0, Number(eventTotal.value || 0) - events.value.length)
  })

  let sessionEpoch = 0
  let listEpoch = 0
  let timer = null
  let pollInFlight = false
  let manualPending = false
  let disposed = false
  let drainAttempts = 0
  let listPollInFlight = false

  const unsubscribeVisibility = visibility.subscribe(() => {
    if (disposed) return
    if (visibility.isHidden()) {
      stopTimer()
      return
    }
    if (selected.value) {
      runRefresh({ manual: false })
    } else if (sessions.value.some((item) => item.status === 'RUNNING')) {
      pollList()
    }
  })

  function normalizedStep() {
    if (stepFilter.value === '' || stepFilter.value == null) return null
    const value = Number(stepFilter.value)
    return Number.isInteger(value) && value >= 0 ? value : null
  }

  function filterSignature() {
    return JSON.stringify({
      type: typeFilter.value || '',
      callId: (callIdFilter.value || '').trim(),
      step: normalizedStep()
    })
  }

  function hasActiveFilters() {
    return !!typeFilter.value
      || !!(callIdFilter.value || '').trim()
      || normalizedStep() != null
  }

  function currentSessionId() {
    return selected.value?.sessionId || null
  }

  function isCurrent(epoch, sessionId, signature) {
    return !disposed
      && epoch === sessionEpoch
      && currentSessionId() === sessionId
      && signature === filterSignature()
  }

  function stopTimer() {
    if (timer != null) {
      clearTimeoutImpl(timer)
      timer = null
    }
  }

  function isCaughtUp() {
    if (!selected.value) return false
    const loadedAll = historyExhausted.value && events.value.length >= eventTotal.value
    const pending = Number(selected.value.pendingEvents || 0)
    if (pending > 0) return false
    const watermark = Number(selected.value.lastSeq || 0)
    if (watermark === 0) return loadedAll
    if (hasActiveFilters()) {
      // 筛选视图无法要求 seq 连续；以“增量扫描已到服务端水位”为准
      return loadedAll && syncCursor.value >= watermark
    }
    // 未筛选时 event_total 与 last_seq 应一致；不一致说明尾部还没落完，继续补拉
    const total = Number(eventTotal.value || 0)
    return loadedAll && total >= watermark && syncCursor.value >= watermark
  }

  const syncState = computed(() => {
    if (!selected.value) return 'idle'
    if (drainStalled.value) return 'stalled'
    if (selected.value.status && selected.value.status !== 'RUNNING') {
      if (!historyExhausted.value) return 'partial'
      return isCaughtUp() ? 'caughtUp' : 'draining'
    }
    return 'running'
  })

  function scheduleNext(delay = intervalMs) {
    stopTimer()
    if (disposed || visibility.isHidden()) return
    if (selected.value) {
      const terminal = selected.value.status && selected.value.status !== 'RUNNING'
      if (terminal && (!historyExhausted.value || isCaughtUp() || drainStalled.value)) {
        return
      }
      timer = setTimeoutImpl(() => {
        timer = null
        tick()
      }, delay)
      return
    }
    if (!sessions.value.some((item) => item.status === 'RUNNING')) return
    timer = setTimeoutImpl(() => {
      timer = null
      tick()
    }, delay)
  }

  function tick() {
    if (disposed || visibility.isHidden()) return
    if (selected.value) {
      runRefresh({ manual: false })
    } else {
      pollList()
    }
  }

  function resetEventState() {
    events.value = []
    eventTotal.value = 0
    eventPage.value = 1
    eventsError.value = ''
    eventsUpdatedAt.value = null
    historyExhausted.value = false
    syncCursor.value = 0
    drainPending.value = false
    drainStalled.value = false
    drainAttempts = 0
  }

  async function loadSessions(targetPage = 1, { silent = false, skipSchedule = false } = {}) {
    const epoch = ++listEpoch
    page.value = Math.max(1, Number(targetPage) || 1)
    if (!silent) {
      loading.value = true
      listError.value = ''
    }
    try {
      const params = { page: page.value, size: pageSize }
      if (statusFilter.value) params.status = statusFilter.value
      const result = await traceApi.list(params)
      if (disposed || epoch !== listEpoch) return
      sessions.value = asList(result?.list)
      total.value = Number(result?.total || 0)
      listUpdatedAt.value = now()
      listError.value = ''
      listPollError.value = ''
    } catch (error) {
      if (disposed || epoch !== listEpoch) return
      if (silent) {
        listPollError.value = errorMessage(error)
      } else {
        listError.value = errorMessage(error)
      }
    } finally {
      if (epoch === listEpoch) loading.value = false
    }
    if (!skipSchedule) scheduleNext(intervalMs)
  }

  async function pollList() {
    if (disposed || visibility.isHidden() || selected.value || listPollInFlight) return
    listPollInFlight = true
    try {
      await loadSessions(page.value, { silent: true, skipSchedule: true })
    } finally {
      listPollInFlight = false
      scheduleNext(intervalMs)
    }
  }

  async function openSession(row) {
    if (!row?.sessionId) return
    stopTimer()
    sessionEpoch += 1
    const epoch = sessionEpoch
    const sessionId = row.sessionId
    selected.value = { ...row }
    detailLoading.value = true
    detailError.value = ''
    detailUpdatedAt.value = null
    typeFilter.value = ''
    callIdFilter.value = ''
    stepFilter.value = ''
    resetEventState()
    eventsLoading.value = true
    try {
      const detail = await traceApi.detail(sessionId)
      if (!isCurrent(epoch, sessionId, filterSignature())) return
      selected.value = detail
      detailUpdatedAt.value = now()
      detailError.value = ''
    } catch (error) {
      if (!isCurrent(epoch, sessionId, filterSignature())) return
      detailError.value = errorMessage(error)
      detailLoading.value = false
      eventsLoading.value = false
      return
    } finally {
      if (epoch === sessionEpoch) detailLoading.value = false
    }
    await loadEvents(1, { replace: true })
    scheduleNext(intervalMs)
  }

  async function loadEvents(targetPage = 1, { replace = true, silent = false } = {}) {
    if (!selected.value) return
    const epoch = sessionEpoch
    const sessionId = selected.value.sessionId
    const signature = filterSignature()
    if (!silent) {
      eventsLoading.value = true
      eventsError.value = ''
    }
    try {
      const params = { page: Math.max(1, Number(targetPage) || 1), size: eventPageSize }
      if (typeFilter.value) params.type = typeFilter.value
      if (callIdFilter.value?.trim()) params.callId = callIdFilter.value.trim()
      const step = normalizedStep()
      if (step != null) params.step = step
      const result = await traceApi.events(sessionId, params)
      if (!isCurrent(epoch, sessionId, signature)) return
      const list = asList(result?.list)
      events.value = replace ? mergeBySeq([], list) : mergeBySeq(events.value, list)
      eventTotal.value = Number(result?.total ?? events.value.length)
      eventPage.value = Math.max(1, Number(targetPage) || 1)
      historyExhausted.value = events.value.length >= eventTotal.value
      if (historyExhausted.value) {
        syncCursor.value = Math.max(syncCursor.value, maxSeq(events.value))
      }
      eventsUpdatedAt.value = now()
      eventsError.value = ''
    } catch (error) {
      if (!isCurrent(epoch, sessionId, signature)) return
      eventsError.value = errorMessage(error)
    } finally {
      if (epoch === sessionEpoch) eventsLoading.value = false
    }
  }

  async function loadMoreEvents() {
    if (!selected.value || eventsLoading.value || historyExhausted.value) return
    await loadEvents(eventPage.value + 1, { replace: false })
    scheduleNext(intervalMs)
  }

  async function applyFilters() {
    if (!selected.value) return
    stopTimer()
    sessionEpoch += 1
    resetEventState()
    await loadEvents(1, { replace: true })
    scheduleNext(intervalMs)
  }

  async function drainIncremental(epoch, sessionId, signature) {
    for (let index = 0; index < maxDrainPages; index += 1) {
      const params = { afterSeq: syncCursor.value, size: eventPageSize }
      if (typeFilter.value) params.type = typeFilter.value
      if (callIdFilter.value?.trim()) params.callId = callIdFilter.value.trim()
      const step = normalizedStep()
      if (step != null) params.step = step
      let result
      try {
        result = await traceApi.events(sessionId, params)
      } catch (error) {
        if (isCurrent(epoch, sessionId, signature)) {
          eventsError.value = errorMessage(error)
        }
        return false
      }
      if (!isCurrent(epoch, sessionId, signature)) return false
      const list = asList(result?.list)
      if (result?.total != null) {
        eventTotal.value = Number(result.total)
      }
      if (list.length) {
        events.value = mergeBySeq(events.value, list)
        syncCursor.value = Math.max(syncCursor.value, maxSeq(list))
      }
      eventsUpdatedAt.value = now()
      eventsError.value = ''
      if (list.length < eventPageSize) {
        // 增量查询已经扫到服务端当前末尾；筛选时最后一条匹配事件可能早于 lastSeq，仍可推进游标。
        // 未筛选时若 eventTotal 仍小于 lastSeq，说明尾部还没落完，不能推进到水位。
        const watermark = Number(selected.value?.lastSeq || 0)
        const total = Number(eventTotal.value || 0)
        if (hasActiveFilters() || total >= watermark) {
          syncCursor.value = Math.max(syncCursor.value, watermark)
        }
        return false
      }
    }
    return true
  }

  async function runRefresh({ manual = false } = {}) {
    if (!selected.value) return
    if (pollInFlight) {
      if (manual) manualPending = true
      return
    }
    pollInFlight = true
    refreshing.value = manual
    stopTimer()
    const epoch = sessionEpoch
    const sessionId = selected.value.sessionId
    const signature = filterSignature()

    try {
      try {
        const detail = await traceApi.detail(sessionId)
        if (isCurrent(epoch, sessionId, signature)) {
          selected.value = detail
          detailUpdatedAt.value = now()
          detailError.value = ''
        }
      } catch (error) {
        if (isCurrent(epoch, sessionId, signature)) {
          detailError.value = errorMessage(error)
        }
      }

      if (!isCurrent(epoch, sessionId, signature)) return

      let more = false
      if (historyExhausted.value) {
        more = await drainIncremental(epoch, sessionId, signature)
      }
      drainPending.value = more
    } finally {
      pollInFlight = false
      refreshing.value = false
      if (manualPending) {
        manualPending = false
        runRefresh({ manual: true })
      } else if (!disposed) {
        const terminal = selected.value?.status && selected.value.status !== 'RUNNING'
        if (terminal && !historyExhausted.value) {
          // 历史还没加载完，自动增量不能越过缺口；等用户继续加载
          stopTimer()
        } else if (terminal && isCaughtUp()) {
          drainPending.value = false
          drainAttempts = 0
          drainStalled.value = false
          stopTimer()
        } else if (terminal) {
          drainAttempts += 1
          if (drainAttempts > maxDrainAttempts) {
            drainStalled.value = true
            stopTimer()
          } else {
            scheduleNext(drainIntervalMs)
          }
        } else {
          drainAttempts = 0
          drainPending.value = false
          drainStalled.value = false
          scheduleNext(intervalMs)
        }
      }
    }
  }

  async function refreshDetail(manual = true) {
    if (!selected.value) {
      return loadSessions(page.value)
    }
    return runRefresh({ manual })
  }

  function backToList() {
    stopTimer()
    sessionEpoch += 1
    selected.value = null
    detailError.value = ''
    detailLoading.value = false
    loadSessions(page.value)
  }

  function clearStatusFilter() {
    statusFilter.value = ''
    loadSessions(1)
  }

  function dispose() {
    disposed = true
    sessionEpoch += 1
    listEpoch += 1
    stopTimer()
    if (typeof unsubscribeVisibility === 'function') unsubscribeVisibility()
  }

  return {
    traceApi,
    sessions,
    total,
    page,
    pageSize,
    statusFilter,
    loading,
    listError,
    listPollError,
    listUpdatedAt,
    selected,
    detailLoading,
    detailError,
    detailUpdatedAt,
    refreshing,
    events,
    eventTotal,
    eventPage,
    eventsLoading,
    eventsError,
    eventsUpdatedAt,
    typeFilter,
    callIdFilter,
    stepFilter,
    viewMode,
    historyExhausted,
    syncCursor,
    drainPending,
    drainStalled,
    syncState,
    unloadedCount,
    loadSessions,
    openSession,
    loadMoreEvents,
    applyFilters,
    refreshDetail,
    backToList,
    clearStatusFilter,
    dispose
  }
}
