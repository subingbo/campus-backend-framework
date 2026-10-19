<template>
  <div class="trace-page">
    <!-- 会话列表 -->
    <template v-if="!selected">
      <header class="trace-header">
        <div>
          <h2 class="trace-title">执行追踪</h2>
          <p class="trace-sub">Agent 每次规划真实发生了什么：模型、工具、地图/路线/忙闲、草稿校验与保存。</p>
        </div>
        <div class="trace-actions">
          <el-select v-model="statusFilter" size="small" clearable placeholder="全部状态" @change="loadSessions(1)">
            <el-option label="运行中" value="RUNNING" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="已中断" value="INTERRUPTED" />
            <el-option label="失败" value="FAILED" />
          </el-select>
          <el-button size="small" :loading="loading" @click="loadSessions(page)">刷新</el-button>
        </div>
      </header>

      <el-empty
        v-if="!loading && !sessions.length"
        description="还没有追踪记录。去「AI 助手」发起一次规划，并确认后端已执行 Trace 迁移。"
      />
      <div v-else class="trace-card">
        <el-table
          v-loading="loading"
          :data="sessions"
          row-key="sessionId"
          class="trace-table"
          @row-click="openSession"
        >
          <el-table-column label="开始时间" min-width="150">
            <template #default="{ row }">{{ fmtTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTagType(row.status)" effect="plain">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="模型" min-width="110">
            <template #default="{ row }">{{ row.model || '—' }}</template>
          </el-table-column>
          <el-table-column label="触发" width="92">
            <template #default="{ row }">{{ triggerLabel(row.triggerSource) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="86">
            <template #default="{ row }">{{ fmtDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column label="Token" width="96">
            <template #default="{ row }">{{ fmtTokens(row.totalTokens) }}</template>
          </el-table-column>
          <el-table-column label="事件 / 工具" width="118">
            <template #default="{ row }">
              <span>{{ row.eventCount ?? 0 }} / {{ row.toolCallCount ?? 0 }}</span>
              <el-tag v-if="row.errorCount" size="small" type="danger" effect="plain" class="ml4">{{ row.errorCount }} 错</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="完整性" width="104">
            <template #default="{ row }">
              <el-tag size="small" :type="completenessType(row)" effect="plain">
                {{ completenessLabel(row) }}
              </el-tag>
              <span v-if="row.droppedCount" class="trace-muted ml4">丢 {{ row.droppedCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="76" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click.stop="openSession(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="trace-pager">
          <el-pagination
            background
            layout="prev, pager, next, total"
            :current-page="page"
            :page-size="pageSize"
            :total="total"
            @current-change="loadSessions"
          />
        </div>
      </div>
    </template>

    <!-- 会话详情 -->
    <template v-else>
      <header class="trace-header trace-header--detail">
        <div class="trace-headline">
          <el-button size="small" text @click="backToList">← 返回列表</el-button>
          <h2 class="trace-title">会话详情</h2>
          <el-tag size="small" :type="statusTagType(selected.status)" effect="dark">{{ statusLabel(selected.status) }}</el-tag>
          <el-tag size="small" :type="completenessType(selected)" effect="plain">
            {{ selected.status === 'RUNNING' ? '进行中' : (selected.complete ? '完整追踪' : '不完整') }}
          </el-tag>
          <span class="trace-muted trace-mono">{{ selected.sessionId }}</span>
        </div>
        <div class="trace-actions">
          <el-button size="small" :loading="eventsLoading" @click="reloadEvents">刷新</el-button>
          <el-button size="small" type="primary" :loading="exporting" @click="exportJsonl">导出 JSONL</el-button>
        </div>
      </header>

      <div class="trace-summary">
        <div class="trace-stat">
          <span class="trace-stat__label">端到端耗时</span>
          <strong>{{ fmtDuration(selected.durationMs) }}</strong>
        </div>
        <div class="trace-stat">
          <span class="trace-stat__label">Token</span>
          <strong>{{ fmtTokens(selected.totalTokens) }}</strong>
          <small v-if="selected.promptTokens != null || selected.completionTokens != null">
            入 {{ fmtTokens(selected.promptTokens) }} / 出 {{ fmtTokens(selected.completionTokens) }}
          </small>
        </div>
        <div class="trace-stat">
          <span class="trace-stat__label">事件</span>
          <strong>{{ selected.eventCount ?? 0 }}</strong>
        </div>
        <div class="trace-stat">
          <span class="trace-stat__label">工具调用</span>
          <strong>{{ selected.toolCallCount ?? 0 }}</strong>
        </div>
        <div class="trace-stat">
          <span class="trace-stat__label">错误</span>
          <strong :class="{ 'trace-danger': selected.errorCount }">{{ selected.errorCount ?? 0 }}</strong>
        </div>
        <div class="trace-stat">
          <span class="trace-stat__label">丢失事件</span>
          <strong :class="{ 'trace-danger': selected.droppedCount }">{{ selected.droppedCount ?? 0 }}</strong>
        </div>
        <div class="trace-stat trace-stat--wide">
          <span class="trace-stat__label">模型 / 来源</span>
          <strong>{{ selected.provider || '—' }} · {{ selected.model || '—' }} · {{ triggerLabel(selected.triggerSource) }}</strong>
        </div>
        <div class="trace-stat trace-stat--wide">
          <span class="trace-stat__label">关联</span>
          <strong>会话 {{ selected.conversationId ?? '—' }} · 草稿 {{ selected.draftId ?? '—' }}</strong>
        </div>
        <div class="trace-stat trace-stat--wide">
          <span class="trace-stat__label">时间</span>
          <strong>{{ fmtTime(selected.startedAt) }} → {{ fmtTime(selected.endedAt) }}</strong>
        </div>
      </div>

      <div v-if="selected.stats?.length" class="trace-card trace-panel">
        <div class="trace-panel__title">耗时指标</div>
        <el-table :data="selected.stats" size="small" class="trace-stats-table">
          <el-table-column label="类型" min-width="150">
            <template #default="{ row }">
              {{ eventLabel(row.type) }}
              <span v-if="row.tool" class="trace-muted trace-mono ml4">· {{ row.tool }}</span>
            </template>
          </el-table-column>
          <el-table-column label="次数" width="70">
            <template #default="{ row }">{{ row.count }}</template>
          </el-table-column>
          <el-table-column label="平均" width="92">
            <template #default="{ row }">{{ fmtDuration(row.avgMs) }}</template>
          </el-table-column>
          <el-table-column label="p50" width="92">
            <template #default="{ row }">{{ fmtDuration(row.p50Ms) }}</template>
          </el-table-column>
          <el-table-column label="p95" width="92">
            <template #default="{ row }">{{ fmtDuration(row.p95Ms) }}</template>
          </el-table-column>
          <el-table-column label="合计" width="100">
            <template #default="{ row }">{{ fmtDuration(row.totalMs) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="selected.draftVersions?.length" class="trace-card trace-panel">
        <div class="trace-panel__title">草稿版本</div>
        <div class="trace-versions">
          <div v-for="(version, index) in selected.draftVersions" :key="version.version" class="trace-version">
            <div class="trace-version__head">
              <span class="trace-version__badge">v{{ version.version }}</span>
              <el-tag v-if="version.validatePassed === true" size="small" type="success" effect="plain">校验通过</el-tag>
              <el-tag v-else-if="version.validatePassed === false" size="small" type="danger" effect="plain">校验拒绝</el-tag>
              <span v-if="version.validatePhase" class="trace-muted">阶段 {{ version.validatePhase }}</span>
              <el-tag v-if="version.reviseChanged === true" size="small" type="warning" effect="plain">修订有变更</el-tag>
              <el-tag v-else-if="version.reviseChanged === false" size="small" type="info" effect="plain">修订无变更</el-tag>
            </div>
            <div class="trace-version__diff">
              <span v-for="line in versionDiff(selected.draftVersions, index)" :key="line" class="trace-version__line">
                {{ line }}
              </span>
            </div>
            <div v-if="version.ruleIds?.length" class="trace-muted">
              规则：{{ version.ruleIds.join('、') }}
            </div>
          </div>
        </div>
      </div>

      <div class="trace-toolbar">
        <el-select v-model="typeFilter" size="small" clearable placeholder="全部事件" @change="reloadEvents">
          <el-option v-for="t in typeOptions" :key="t" :label="eventLabel(t)" :value="t" />
        </el-select>
        <el-input
          v-model="callIdFilter"
          size="small"
          clearable
          placeholder="按 callId 过滤"
          class="trace-call-filter"
          @keyup.enter="reloadEvents"
          @clear="reloadEvents"
        />
        <el-button size="small" @click="reloadEvents">应用</el-button>
        <span class="trace-muted">共 {{ eventTotal }} 条，已加载 {{ events.length }} 条</span>
        <div class="trace-view-switch">
          <el-radio-group v-model="viewMode" size="small">
            <el-radio-button label="timeline">时间线</el-radio-button>
            <el-radio-button label="swimlane">泳道</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <el-empty
        v-if="viewMode === 'timeline' && !eventsLoading && !groups.length"
        description="暂无事件"
      />
      <div v-else-if="viewMode === 'timeline'" v-loading="eventsLoading" class="trace-groups">
        <section v-for="group in groups" :key="group.key" class="trace-group">
          <div class="trace-group__head">
            <span class="trace-group__round">{{ group.turn ? `第 ${group.turn} 轮` : '会话级' }}</span>
            <span v-if="group.step" class="trace-group__step">步骤 {{ group.step }}</span>
            <span class="trace-muted">{{ group.events.length }} 条事件</span>
          </div>
          <el-timeline>
            <el-timeline-item
              v-for="event in group.events"
              :key="event.seq"
              :timestamp="`#${event.seq} · ${fmtTime(event.time)}`"
              :type="timelineType(event.status)"
              :hollow="event.status === 'RUNNING'"
            >
              <div class="trace-event">
                <div class="trace-event__head">
                  <span class="trace-event__icon">{{ eventIcon(event.type) }}</span>
                  <strong>{{ eventLabel(event.type) }}</strong>
                  <el-tag size="small" :type="statusTagType(event.status)" effect="plain">{{ event.status || '—' }}</el-tag>
                  <el-tag v-if="event.tool" size="small" effect="plain">{{ event.tool }}</el-tag>
                  <el-tag v-if="event.callId" size="small" type="info" effect="plain">{{ event.callId }}</el-tag>
                  <el-tag v-if="event.errorCode" size="small" type="danger" effect="plain">{{ event.errorCode }}</el-tag>
                  <span v-if="event.durationMs != null" class="trace-muted">{{ fmtDuration(event.durationMs) }}</span>
                </div>
                <div class="trace-event__summary">{{ eventSummary(event) }}</div>
                <details v-if="hasData(event)" class="trace-data">
                  <summary>查看 data</summary>
                  <pre>{{ pretty(event.data) }}</pre>
                </details>
              </div>
            </el-timeline-item>
          </el-timeline>
        </section>
        <div v-if="events.length < eventTotal" class="trace-more">
          <el-button size="small" :loading="eventsLoading" @click="loadMoreEvents">加载更多</el-button>
        </div>
      </div>
      <el-empty
        v-else-if="!eventsLoading && !swimlanes.some((group) => group.lanes.length)"
        description="暂无可并排展示的调用"
      />
      <div v-else v-loading="eventsLoading" class="trace-groups">
        <section v-for="laneGroup in swimlanes" :key="laneGroup.key" class="trace-group">
          <div class="trace-group__head">
            <span class="trace-group__round">{{ laneGroup.turn ? `第 ${laneGroup.turn} 轮` : '会话级' }}</span>
            <span class="trace-muted">{{ laneGroup.lanes.length }} 条调用</span>
          </div>
          <div class="trace-lanes">
            <div v-for="lane in laneGroup.lanes" :key="lane.key" class="trace-lane">
              <div class="trace-lane__label">
                <span class="trace-lane__name">{{ lane.name }}</span>
                <span class="trace-muted trace-mono">{{ lane.callId || lane.hint || '' }}</span>
              </div>
              <div class="trace-lane__track">
                <div
                  v-for="span in lane.spans"
                  :key="span.key"
                  class="trace-lane__bar"
                  :class="span.error ? 'trace-lane__bar--error' : (span.kind === 'llm' ? 'trace-lane__bar--llm' : 'trace-lane__bar--tool')"
                  :style="span.style"
                  :title="span.title"
                >{{ span.label }}</div>
              </div>
            </div>
          </div>
        </section>
        <div v-if="events.length < eventTotal" class="trace-more">
          <el-button size="small" :loading="eventsLoading" @click="loadMoreEvents">加载更多</el-button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { traceApi } from '../api'

const PAGE_SIZE = 20
const EVENT_PAGE_SIZE = 200

const EVENT_LABELS = {
  'session/start': '会话开始',
  'session/end': '会话结束',
  'turn/start': '轮次开始',
  'turn/end': '轮次结束',
  'step/start': '步骤开始',
  'step/end': '步骤结束',
  'llm/request': '模型请求',
  'llm/response': '模型响应',
  'llm/error': '模型错误',
  'llm/retry': '模型重试',
  'tool/call': '工具调用',
  'tool/result': '工具结果',
  'tool/error': '工具错误',
  'map/search': '地图检索',
  'map/permission-filter': '地图权限过滤',
  'place/semantic-retrieval': '地点语义检索',
  'route/estimate': '路线估算',
  'schedule/free-window': '空闲窗口',
  'plan/draft': '草稿生成',
  'plan/revise': '草稿修订',
  'plan/validate': '草稿校验',
  'plan/confirm': '确认保存',
  'activity/created': '活动已创建',
  'plan/save-error': '保存失败',
  'stream/open': '流开启',
  'stream/close': '流关闭',
  'stream/error': '流错误',
  'trace/dropped': '事件丢弃',
  'trace/partial': '追踪不完整'
}
const typeOptions = Object.keys(EVENT_LABELS)

const sessions = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = PAGE_SIZE
const statusFilter = ref('')
const loading = ref(false)

const selected = ref(null)
const events = ref([])
const eventTotal = ref(0)
const eventPage = ref(1)
const eventsLoading = ref(false)
const typeFilter = ref('')
const callIdFilter = ref('')
const exporting = ref(false)
const viewMode = ref('timeline')

const groups = computed(() => {
  const result = []
  let current = null
  for (const event of events.value) {
    const key = `${event.turn ?? 0}:${event.step ?? 0}`
    if (!current || current.key !== key) {
      current = { key, turn: event.turn, step: event.step, events: [] }
      result.push(current)
    }
    current.events.push(event)
  }
  return result
})

/** 泳道视图：按轮分组，组内 tool/call→result、llm/request→response 按 callId 配对成横条 */
const swimlanes = computed(() => {
  const result = []
  let current = null
  for (const event of events.value) {
    const key = `${event.turn ?? 0}`
    if (!current || current.key !== key) {
      current = { key, turn: event.turn, events: [] }
      result.push(current)
    }
    current.events.push(event)
  }
  return result.map((group) => {
    const laneMap = new Map()
    const openByCall = new Map()
    const pendingLlm = []
    const times = group.events.filter((event) => event.time != null).map((event) => event.time)
    const minTime = times.length ? Math.min(...times) : 0
    const maxTime = times.length ? Math.max(...times) : 1
    const rangeMs = Math.max(1, maxTime - minTime)
    for (const event of group.events) {
      // LLM 事件没有 callId（TraceRun 记 null）：turn 内按顺序 request→response/error 配对
      if (event.type === 'llm/request') {
        pendingLlm.push(event)
        continue
      }
      if (event.type === 'llm/response' || event.type === 'llm/error') {
        const start = pendingLlm.shift()
        const lane = laneOf(laneMap, '__llm__', start, event, '模型', '按顺序配对')
        lane.spans.push(buildSpan(start, event, minTime, rangeMs))
        continue
      }
      if (!event.callId) continue
      if (event.type === 'tool/call' || event.type === 'llm/request') {
        openByCall.set(event.callId, event)
      } else if (
        event.type === 'tool/result' || event.type === 'tool/error' ||
        event.type === 'llm/response' || event.type === 'llm/error'
      ) {
        const start = openByCall.get(event.callId)
        openByCall.delete(event.callId)
        const lane = laneOf(laneMap, event.callId, start, event)
        lane.spans.push(buildSpan(start, event, minTime, rangeMs))
      }
    }
    // 仍在进行中、未等到配对的调用：画起点标记
    for (const [callId, start] of openByCall) {
      const lane = laneOf(laneMap, callId, start, null)
      lane.spans.push(buildSpan(start, null, minTime, rangeMs))
    }
    for (const start of pendingLlm) {
      const lane = laneOf(laneMap, '__llm__', start, null, '模型', '按顺序配对')
      lane.spans.push(buildSpan(start, null, minTime, rangeMs))
    }
    // 模型泳道排最前，工具按出现顺序
    return {
      key: group.key,
      turn: group.turn,
      lanes: [...laneMap.values()].sort((a, b) => (a.key === '__llm__' ? -1 : b.key === '__llm__' ? 1 : 0))
    }
  })
})

function laneOf(laneMap, key, start, end, forcedName, hint) {
  if (!laneMap.has(key)) {
    const name = forcedName
      || end?.tool || start?.tool
      || (start?.type?.startsWith('llm/') || end?.type?.startsWith('llm/') ? '模型' : '调用')
    laneMap.set(key, { key, callId: key === '__llm__' ? null : key, name, hint, spans: [] })
  }
  return laneMap.get(key)
}

function buildSpan(start, end, minTime, rangeMs) {
  const endMs = end ? end.time + (end.durationMs || 0) : (start?.time ?? minTime)
  const startMs = start ? start.time : end ? end.time - (end.durationMs || 0) : minTime
  const left = Math.min(98.5, Math.max(0, ((startMs - minTime) / rangeMs) * 100))
  const width = Math.min(100 - left, Math.max(1.5, ((endMs - startMs) / rangeMs) * 100))
  const error = !!end && (end.type === 'tool/error' || end.type === 'llm/error' || end.status === 'FAILED')
  const kind = start?.type?.startsWith('llm/') || end?.type?.startsWith('llm/') ? 'llm' : 'tool'
  const label = end
    ? (end.durationMs != null ? fmtDuration(end.durationMs) : fmtDuration(Math.max(0, endMs - startMs)))
    : '进行中'
  const title = [
    start ? eventLabel(start.type) : null,
    end ? eventLabel(end.type) : '未返回',
    start?.callId ? `callId ${start.callId}` : null
  ].filter(Boolean).join(' · ')
  return {
    key: `${start?.seq ?? 's'}-${end?.seq ?? 'open'}`,
    label,
    error,
    kind,
    title,
    style: { left: `${left}%`, width: `${width}%` }
  }
}

/** 相邻草稿版本的摘要 diff（trace 只留脱敏摘要，diff 基于计数/耗时/置信度/地点 ID） */
function versionDiff(versions, index) {
  const version = versions[index]
  const lines = []
  if (version.stopCount != null) lines.push(`站点 ${version.stopCount}`)
  if (version.totalTravelMinutes != null) lines.push(`总行程 ${version.totalTravelMinutes} 分钟`)
  if (version.routeConfidence) lines.push(`路线 ${routeConfidenceLabel(version.routeConfidence)}`)
  if (version.selectedPlaceIds?.length) lines.push(`地点 ${version.selectedPlaceIds.length} 个`)
  if (version.revisedFromVersion != null) lines.push(`由 v${version.revisedFromVersion} 修订`)
  if (index > 0) {
    const prev = versions[index - 1]
    if (version.stopCount != null && prev.stopCount != null && version.stopCount !== prev.stopCount) {
      lines.push(diffText('站点', version.stopCount - prev.stopCount))
    }
    if (version.totalTravelMinutes != null && prev.totalTravelMinutes != null
      && version.totalTravelMinutes !== prev.totalTravelMinutes) {
      lines.push(diffText('行程分钟', version.totalTravelMinutes - prev.totalTravelMinutes))
    }
    if (prev.routeConfidence && version.routeConfidence && prev.routeConfidence !== version.routeConfidence) {
      lines.push(`路线 ${routeConfidenceLabel(prev.routeConfidence)} → ${routeConfidenceLabel(version.routeConfidence)}`)
    }
    const added = version.selectedPlaceIds?.filter((id) => !prev.selectedPlaceIds?.includes(id)) || []
    const removed = prev.selectedPlaceIds?.filter((id) => !version.selectedPlaceIds?.includes(id)) || []
    if (added.length) lines.push(`新增地点 ${added.length} 个`)
    if (removed.length) lines.push(`移除地点 ${removed.length} 个`)
  }
  return lines.length ? lines : ['—']
}

function diffText(label, delta) {
  return `${label} ${delta > 0 ? '+' : ''}${delta}`
}

function routeConfidenceLabel(value) {
  return { REAL: '实测', ESTIMATE: '估算', UNKNOWN: '未知' }[value] || value
}

async function loadSessions(targetPage = 1) {
  page.value = Math.max(1, Number(targetPage) || 1)
  loading.value = true
  try {
    const params = { page: page.value, size: pageSize }
    if (statusFilter.value) params.status = statusFilter.value
    const result = await traceApi.list(params)
    sessions.value = result?.list || []
    total.value = result?.total || 0
  } catch (error) {
    ElMessage.error(error.message || '加载追踪列表失败')
  } finally {
    loading.value = false
  }
}

async function openSession(row) {
  try {
    selected.value = await traceApi.detail(row.sessionId)
    typeFilter.value = ''
    callIdFilter.value = ''
    await loadEvents(1, true)
  } catch (error) {
    ElMessage.error(error.message || '加载会话详情失败')
  }
}

async function loadEvents(targetPage = 1, replace = true) {
  if (!selected.value) return
  eventsLoading.value = true
  try {
    const params = { page: targetPage, size: EVENT_PAGE_SIZE }
    if (typeFilter.value) params.type = typeFilter.value
    if (callIdFilter.value?.trim()) params.callId = callIdFilter.value.trim()
    const result = await traceApi.events(selected.value.sessionId, params)
    const list = result?.list || []
    eventTotal.value = result?.total || 0
    const merged = replace ? list : events.value.concat(list)
    const seen = new Set()
    events.value = merged.filter((event) => {
      if (seen.has(event.seq)) return false
      seen.add(event.seq)
      return true
    })
    eventPage.value = targetPage
  } catch (error) {
    ElMessage.error(error.message || '加载事件失败')
  } finally {
    eventsLoading.value = false
  }
}

function reloadEvents() {
  loadEvents(1, true)
}

function loadMoreEvents() {
  loadEvents(eventPage.value + 1, false)
}

function backToList() {
  selected.value = null
  loadSessions(page.value)
}

async function exportJsonl() {
  if (!selected.value) return
  exporting.value = true
  try {
    const blob = await traceApi.exportJsonl(selected.value.sessionId)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `session-${selected.value.sessionId}.jsonl`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
    ElMessage.success('JSONL 已开始下载')
  } catch (error) {
    ElMessage.error(error.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

function eventLabel(type) {
  return EVENT_LABELS[type] || type || '未知事件'
}

function eventIcon(type) {
  if (!type) return '⚪'
  if (type.startsWith('session/')) return '🧭'
  if (type.startsWith('turn/')) return '💬'
  if (type.startsWith('step/')) return '🔁'
  if (type.startsWith('llm/')) return '🧠'
  if (type.startsWith('tool/')) return '🛠️'
  if (type.startsWith('map/')) return '🗺️'
  if (type.startsWith('place/')) return '📍'
  if (type.startsWith('route/')) return '🚶'
  if (type.startsWith('schedule/')) return '🕐'
  if (type.startsWith('plan/')) return '📝'
  if (type.startsWith('activity/')) return '🎉'
  if (type.startsWith('stream/')) return '🌊'
  if (type.startsWith('trace/')) return '🧩'
  return '⚪'
}

function statusLabel(status) {
  return {
    RUNNING: '运行中',
    COMPLETED: '已完成',
    INTERRUPTED: '已中断',
    FAILED: '失败',
    SUCCESS: '成功',
    CANCELLED: '已取消',
    PARTIAL: '不完整',
    SKIPPED: '已跳过'
  }[status] || status || '—'
}

function statusTagType(status) {
  if (status === 'COMPLETED' || status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELLED' || status === 'PARTIAL') return 'warning'
  if (status === 'RUNNING' || status === 'INTERRUPTED') return 'info'
  return 'info'
}

function completenessLabel(row) {
  if (row.status === 'RUNNING') return '进行中'
  return row.complete ? '完整' : '不完整'
}

function completenessType(row) {
  if (row.status === 'RUNNING') return 'info'
  return row.complete ? 'success' : 'warning'
}

function timelineType(status) {
  if (status === 'COMPLETED' || status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELLED' || status === 'PARTIAL') return 'warning'
  return 'primary'
}

function triggerLabel(source) {
  return {
    chat: '个人会话',
    group_chat: '群聊 @AI',
    save: '保存',
    revise: '修订'
  }[source] || source || '—'
}

function fmtTime(value) {
  if (value == null) return '—'
  if (typeof value === 'number') {
    return new Date(value).toLocaleString('zh-CN', { hour12: false })
  }
  return String(value).replace('T', ' ').replace(/\.\d+$/, '')
}

function fmtDuration(ms) {
  if (ms == null) return '—'
  const value = Number(ms)
  if (!Number.isFinite(value)) return '—'
  return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`
}

function fmtTokens(value) {
  if (value == null) return '未返回'
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('zh-CN') : String(value)
}

function hasData(event) {
  return event?.data && typeof event.data === 'object' && Object.keys(event.data).length > 0
}

function pretty(data) {
  try {
    return JSON.stringify(data, null, 2)
  } catch {
    return String(data)
  }
}

function shortValue(value) {
  if (value == null) return '—'
  if (Array.isArray(value)) return `[${value.length}]`
  if (typeof value === 'object') return '{…}'
  const text = String(value)
  return text.length > 48 ? `${text.slice(0, 48)}…` : text
}

function compactSummary(value) {
  if (!value || typeof value !== 'object') return shortValue(value)
  return Object.entries(value)
    .slice(0, 6)
    .map(([key, item]) => `${key}=${shortValue(item)}`)
    .join(', ')
}

function eventSummary(event) {
  const data = event?.data || {}
  const parts = []
  if (data.input && typeof data.input === 'object') parts.push(`入参 ${compactSummary(data.input)}`)
  if (data.output && typeof data.output === 'object') parts.push(`结果 ${compactSummary(data.output)}`)
  if (data.ruleIds?.length) parts.push(`规则 ${data.ruleIds.join(', ')}`)
  if (data.details && typeof data.details === 'object') parts.push(compactSummary(data.details))
  if (data.trigger) parts.push(triggerLabel(data.trigger))
  if (data.provider || data.model) parts.push([data.provider, data.model].filter(Boolean).join(' · '))
  if (data.messageLength != null) parts.push(`消息长度 ${data.messageLength}`)
  if (data.reason) parts.push(`原因 ${data.reason}`)
  if (data.source) parts.push(`来源 ${data.source}`)
  if (data.mode) parts.push(`方式 ${data.mode}`)
  if (data.hitCount != null) parts.push(`命中 ${data.hitCount}`)
  if (data.noteHitCount != null) parts.push(`备注命中 ${data.noteHitCount}`)
  if (data.durationMs != null) parts.push(`耗时 ${fmtDuration(data.durationMs)}`)
  if (data.droppedCount != null) parts.push(`丢失 ${data.droppedCount}`)
  if (data.phase) parts.push(`阶段 ${data.phase}`)
  if (data.draftVersion != null) parts.push(`草稿 v${data.draftVersion}`)
  if (data.activityId != null) parts.push(`活动 ${data.activityId}`)
  if (data.operation) parts.push(data.operation)
  if (data.idempotentReuse) parts.push('幂等复用')
  if (data.finishReason) parts.push(`finish=${data.finishReason}`)
  if (data.toolCallCount != null) parts.push(`工具 ${data.toolCallCount}`)
  if (data.promptTokens != null || data.completionTokens != null) {
    parts.push(`Token 入${data.promptTokens ?? '—'}/出${data.completionTokens ?? '—'}`)
  }
  if (data.windowCount != null) parts.push(`窗口 ${data.windowCount}`)
  if (data.resultCount != null) parts.push(`结果 ${data.resultCount}`)
  if (data.stopCount != null) parts.push(`站点 ${data.stopCount}`)
  if (data.availableMinutes != null || data.requiredMinutes != null) {
    parts.push(`可用 ${data.availableMinutes ?? '—'} / 需要 ${data.requiredMinutes ?? '—'} 分钟`)
  }
  if (data.closeReason) parts.push(`关闭 ${data.closeReason}`)
  if (data.cancelled) parts.push('客户端已断开')
  return parts.join(' · ') || '—'
}

onMounted(() => loadSessions(1))
</script>

<style scoped>
.trace-page {
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px;
}
.trace-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.trace-header--detail {
  flex-wrap: wrap;
}
.trace-headline {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.trace-title {
  margin: 0;
  font-size: 20px;
  color: var(--app-text-primary);
}
.trace-sub {
  margin: 6px 0 0;
  color: var(--app-text-secondary);
  font-size: 13px;
}
.trace-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.trace-card {
  background: #fff;
  border-radius: 16px;
  box-shadow: var(--app-card-shadow);
  padding: 8px 12px 16px;
}
.trace-table :deep(.el-table__row) {
  cursor: pointer;
}
.trace-pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 4px 0;
}
.trace-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}
.trace-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  background: #fff;
  border-radius: 12px;
  box-shadow: var(--app-card-shadow);
}
.trace-stat--wide {
  grid-column: span 2;
}
.trace-stat__label {
  color: var(--app-text-secondary);
  font-size: 12px;
}
.trace-stat strong {
  color: var(--app-text-primary);
  font-size: 15px;
  font-weight: 600;
}
.trace-stat small {
  color: var(--app-text-secondary);
  font-size: 11px;
}
.trace-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.trace-call-filter {
  width: 200px;
}
.trace-groups {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.trace-group {
  padding: 14px 18px 2px;
  background: #fff;
  border-radius: 14px;
  box-shadow: var(--app-card-shadow);
}
.trace-group__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.trace-group__round {
  color: var(--el-color-primary);
  font-weight: 600;
}
.trace-group__step {
  padding: 2px 8px;
  color: var(--app-text-secondary);
  font-size: 12px;
  background: var(--brand-blue-light);
  border-radius: 999px;
}
.trace-event__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.trace-event__icon {
  font-size: 15px;
}
.trace-event__summary {
  margin-top: 4px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
}
.trace-data {
  margin-top: 6px;
}
.trace-data summary {
  color: var(--el-color-primary);
  font-size: 12px;
  cursor: pointer;
}
.trace-data pre {
  max-height: 280px;
  margin: 8px 0 0;
  padding: 10px 12px;
  overflow: auto;
  color: var(--app-text-primary);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  background: #f6f8fc;
  border-radius: 8px;
}
.trace-muted {
  color: var(--app-text-secondary);
  font-size: 12px;
}
.trace-mono {
  font-family: 'Cascadia Code', Consolas, monospace;
}
.trace-danger {
  color: var(--el-color-danger);
}
.trace-more {
  padding: 12px;
  text-align: center;
}
.trace-panel {
  padding: 14px 18px;
  margin-bottom: 16px;
}
.trace-panel__title {
  margin-bottom: 10px;
  color: var(--app-text-primary);
  font-weight: 600;
}
.trace-stats-table {
  width: 100%;
}
.trace-view-switch {
  margin-left: auto;
}
.trace-versions {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.trace-version {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  background: #f6f8fc;
  border-radius: 10px;
}
.trace-version__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.trace-version__badge {
  padding: 2px 10px;
  color: var(--el-color-primary);
  font-size: 13px;
  font-weight: 600;
  background: var(--brand-blue-light);
  border-radius: 999px;
}
.trace-version__diff {
  display: flex;
  gap: 6px 14px;
  flex-wrap: wrap;
}
.trace-version__line {
  color: var(--app-text-primary);
  font-size: 13px;
}
.trace-lanes {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 14px;
}
.trace-lane {
  display: flex;
  align-items: center;
  gap: 12px;
}
.trace-lane__label {
  display: flex;
  flex: 0 0 190px;
  flex-direction: column;
  overflow: hidden;
}
.trace-lane__name {
  color: var(--app-text-primary);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.trace-lane__label .trace-mono {
  font-size: 11px;
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden;
}
.trace-lane__track {
  position: relative;
  flex: 1;
  height: 24px;
  background: #f3f6fb;
  border-radius: 6px;
}
.trace-lane__bar {
  position: absolute;
  top: 3px;
  bottom: 3px;
  min-width: 40px;
  padding: 0 6px;
  overflow: hidden;
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  white-space: nowrap;
  text-overflow: ellipsis;
  border-radius: 5px;
}
.trace-lane__bar--llm {
  background: var(--el-color-primary);
}
.trace-lane__bar--tool {
  background: var(--el-color-success);
}
.trace-lane__bar--error {
  background: var(--el-color-danger);
}
.ml4 {
  margin-left: 4px;
}
@media (max-width: 768px) {
  .trace-page {
    padding: 16px;
  }
  .trace-stat--wide {
    grid-column: span 1;
  }
  .trace-call-filter {
    width: 100%;
  }
  .trace-view-switch {
    margin-left: 0;
    width: 100%;
  }
  .trace-lane__label {
    flex-basis: 120px;
  }
}
</style>
