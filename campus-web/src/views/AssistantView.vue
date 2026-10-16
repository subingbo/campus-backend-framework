<template>
  <div class="assistant-page">
    <aside class="chat-col">
      <header class="chat-head">
        <el-button text size="small" @click="$router.push('/')">← 地图</el-button>
        <span class="chat-head__title">AI 规划助手</span>
        <el-select
          v-model="referenceMapId"
          size="small"
          clearable
          placeholder="参考地图"
          class="chat-head__ref"
        >
          <el-option v-for="m in myMaps" :key="m.id" :value="m.id" :label="m.name" />
        </el-select>
      </header>

      <div ref="listRef" class="msg-list">
        <div v-if="!messages.length" class="msg-empty">
          <h3>一句话，规划一场校园活动</h3>
          <p>例如：周六下午 5 个人逛校园，最后一起吃晚饭。</p>
          <p>助手会优先使用你和朋友们共建地图里的地点，并用高德查询真实路线与耗时。</p>
        </div>
        <template v-for="(msg, i) in messages" :key="i">
          <div v-if="msg.role === 'user'" class="msg msg--user">
            <div class="bubble bubble--user">{{ msg.content }}</div>
          </div>
          <div v-else class="msg msg--assistant">
            <div class="bubble bubble--assistant">
              <div v-if="msg.status" class="msg__status">
                <span class="spin-dot"></span>{{ msg.status }}
              </div>
              <span v-if="msg.content" class="msg__text">{{ msg.content }}<span v-if="msg.streaming" class="caret">▌</span></span>
            </div>
          </div>
        </template>
      </div>

      <div v-if="plan" class="plan-card">
        <h3 class="plan-card__title">
          📅 {{ plan.title }}
          <el-tag size="small" effect="plain">{{ PLAN_TEXT[planCategory] || '' }}</el-tag>
          <el-tag v-if="currentPlanVersion" size="small" type="info" effect="plain" class="plan-ver">v{{ currentPlanVersion }}</el-tag>
          <el-tag v-if="plan.routeConfidence === 'ESTIMATED'" size="small" type="warning" effect="plain">交通·估算</el-tag>
          <el-tag v-else-if="plan.routeConfidence === 'AMAP'" size="small" effect="plain">交通·高德实测</el-tag>
        </h3>
        <template v-if="!editing">
          <template v-for="stop in enrichedStops" :key="stop.seq">
            <div v-if="stop.segmentText" class="plan-seg">{{ stop.segmentText }}</div>
            <div class="plan-stop" @click="focusStop(stop)">
              <span class="plan-stop__dot">{{ stop.seq }}</span>
              <div class="plan-stop__main">
                <div class="plan-stop__head">
                  <strong>{{ stop.name }}</strong>
                  <span v-if="stop.source" class="plan-stop__src" :class="{ poi: stop.source === 'AMAP_POI' }">{{
                    stop.source === 'GROUP_MAP' ? '共建' : stop.source === 'PERSONAL_MAP' ? '收藏' : 'POI'
                  }}</span>
                  <span v-if="stop.timeText" class="hint">{{ stop.timeText }}</span>
                </div>
                <p class="hint">
                  <template v-if="stop.placeText">📍{{ stop.placeText }} · </template>
                  <template v-if="stop.transportText">{{ stop.transportText }}</template>
                </p>
                <p v-if="stop.note" class="hint">{{ stop.note }}</p>
              </div>
            </div>
          </template>
          <p class="plan-card__range">🕐 {{ planRangeText }}</p>
          <div class="plan-card__actions">
            <el-button size="small" :disabled="saving || !chatId" @click="openEditor">✏️ 编辑</el-button>
            <el-button
              type="primary"
              class="plan-card__save"
              :loading="saving"
              :disabled="saved || editing"
              @click="savePlan"
            >
              {{ saved ? '已保存到活动日程' : (plan.lineageActivityId ? '保存并更新活动' : '保存为活动') }}
            </el-button>
            <el-button
              v-if="plan.lineageActivityId && !saved"
              size="small"
              :disabled="saving"
              @click="saveAsNewActivity"
            >🆕 另建新活动（复制草稿）</el-button>
          </div>
        </template>
        <template v-else>
          <div class="plan-edit">
            <el-input v-model="editForm.title" size="small" maxlength="128" placeholder="活动标题" />
            <div class="plan-edit__row">
              <el-select v-model="editForm.category" size="small" style="width: 120px">
                <el-option value="MEETING" label="会议" />
                <el-option value="COMPETITION" label="比赛" />
                <el-option value="OTHER" label="活动" />
              </el-select>
              <span class="hint">计划 {{ editForm.maxMembers || 20 }} 人</span>
            </div>
            <div class="plan-edit__row">
              <span class="hint">活动起</span>
              <input v-model="editForm.startTime" type="datetime-local" class="dt" />
              <span class="hint">止</span>
              <input v-model="editForm.endTime" type="datetime-local" class="dt" />
            </div>
            <div v-for="(stop, idx) in editForm.stops" :key="idx" class="plan-edit__stop">
              <div class="plan-edit__stophead">
                <span class="plan-stop__dot">{{ idx + 1 }}</span>
                <span v-if="idx > 0" class="mini" @click="moveStop(idx, -1)">↑</span>
                <span v-if="idx < editForm.stops.length - 1" class="mini" @click="moveStop(idx, 1)">↓</span>
                <span class="mini mini--danger" @click="editForm.stops.splice(idx, 1)">✕</span>
              </div>
              <el-input v-model="stop.name" size="small" maxlength="128" placeholder="站点名称" />
              <div class="plan-edit__when" v-if="stop.longitude != null || !stop.placeId">
                <input v-model="stop.startTime" type="datetime-local" class="dt" />
                <input v-model="stop.endTime" type="datetime-local" class="dt" />
              </div>
              <el-input v-model="stop.note" size="small" maxlength="256" placeholder="站点备注（可空）" />
            </div>
            <p v-if="!editForm.stops.length" class="hint">⚠️ 至少保留一个站点</p>
            <p class="hint">站间交通方式沿用原值，保存前服务端会重新校验时间与顺序。</p>
            <div class="plan-card__actions">
              <el-button size="small" @click="editing = false">取消</el-button>
              <el-button type="primary" size="small" :loading="revising" @click="applyEdit">应用修改（重校验）</el-button>
            </div>
          </div>
        </template>
      </div>

      <footer class="input-row">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="描述你要办的活动，如：周六下午和社团 6 人漫游校园，最后一起吃晚饭"
          @keydown.enter.exact.prevent="send"
        />
        <el-button type="primary" :disabled="busy || !input.trim()" @click="send">
          {{ busy ? '规划中…' : '发送' }}
        </el-button>
      </footer>
    </aside>

    <section class="map-col">
      <div v-if="mapError" class="map-fallback">{{ mapError }}</div>
      <div ref="mapRef" class="map-canvas"></div>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onUnmounted, ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { assistantApi, mapApi } from '../api'
import { streamAssistantChat } from '../api/aiStream'
import { loadAmap, createPlaceMarkerController, createRouteLineController } from '../composables/useAmap'

const router = useRouter()

const PLAN_TEXT = { MEETING: '会议', COMPETITION: '比赛', OTHER: '活动' }
const TRANSPORT_TEXT = { WALK: '🚶 步行前往', RIDE: '🚲 骑行前往', DRIVE: '🚗 驾车前往', TRANSIT: '🚌 公交前往' }

const messages = ref([])
const input = ref('')
const busy = ref(false)
const plan = ref(null)
const saving = ref(false)
const saved = ref(false)
const referenceMapId = ref(null)
const myMaps = ref([])
const mapError = ref('')
const listRef = ref(null)
const mapRef = ref(null)
/** 阶段 F：草稿编辑态 + 乐观版本（服务端仍行锁 + 重校验兜底） */
const editing = ref(false)
const revising = ref(false)
const currentPlanVersion = ref(0)
const editForm = ref(null)

let chatId = null
let streamingPlaceholderIndex = -1
let abortController = null
let markerCtrl = null
let routeCtrl = null
let mapApi_ = null

const planCategory = computed(() => plan.value?.category || 'OTHER')

/** 站点行 enrich：时间文本 + 站间交通建议（route 预览返回后填充） */
const enrichedStops = computed(() => {
  const stops = plan.value?.stops || []
  return stops.map((stop, i) => {
    const segment = stopSegments.value[i]
    const timeText = stopTimeText(stop)
    let segmentText = ''
    if (i > 0) {
      const mode = TRANSPORT_TEXT[stop.transportMode] || '🚶 前往下一站'
      if (segment?.durationSeconds) {
        segmentText = `${mode} · 约 ${Math.max(1, Math.round(segment.durationSeconds / 60))} 分钟${segment.estimated ? '（估算）' : ''}`
      } else if (stop.transportMode) {
        segmentText = mode
      }
    }
    return {
      ...stop,
      timeText,
      segmentText,
      transportText: TRANSPORT_TEXT[stop.transportMode] || '',
      placeText: stop.placeName || (stop.longitude != null ? `${Number(stop.longitude).toFixed(6)}, ${Number(stop.latitude).toFixed(6)}` : '')
    }
  })
})

/** 站间路线预览（index i 连接 stop i 与 stop i+1） */
const stopSegments = ref([])

const planRangeText = computed(() => {
  const p = plan.value
  if (!p?.startTime || !p?.endTime) return ''
  return `${dateLabel(p.startTime)} ${clock(p.startTime)} – ${clock(p.endTime)}${p.maxMembers ? ` · 计划 ${p.maxMembers} 人` : ''}`
})

onMounted(async () => {
  initMap()
  try {
    const mapsResp = await mapApi.list()
    myMaps.value = (mapsResp || []).map((m) => ({ id: m.id, name: m.name }))
  } catch (e) {
    myMaps.value = []
  }
  restoreLatestChat()
})

onUnmounted(() => {
  abortController?.abort()
})

watch(plan, async (value) => {
  if (value) {
    renderPlanOnMap(value)
    await loadRouteSegments(value)
  }
})

function initMap() {
  loadAmap(mapRef.value).then(({ AMap, map }) => {
    markerCtrl = createPlaceMarkerController(AMap, map, null)
    routeCtrl = createRouteLineController(AMap, map)
    mapApi_ = { AMap, map }
    if (plan.value) {
      renderPlanOnMap(plan.value)
    }
  }).catch((e) => {
    mapError.value = e?.message || '地图加载失败'
  })
}

function renderPlanOnMap(value) {
  if (!markerCtrl) return
  const places = (value.stops || []).filter((s) => s.longitude != null)
    .map((s) => ({ id: `stop-${s.seq}`, name: s.name, longitude: Number(s.longitude), latitude: Number(s.latitude) }))
  markerCtrl.render(places, { fitView: places.length > 1 })
}

/** 站间折线走后端 /routes（与规划阶段同源缓存） */
async function loadRouteSegments(value) {
  const stops = value?.stops || []
  const result = []
  for (let i = 1; i < stops.length; i++) {
    const from = stops[i - 1]
    const to = stops[i]
    if (from?.longitude == null || to?.longitude == null) continue
    try {
      const info = await assistantApi.route({
        fromLng: Number(from.longitude),
        fromLat: Number(from.latitude),
        toLng: Number(to.longitude),
        toLat: Number(to.latitude),
        mode: to.transportMode || 'WALK'
      })
      if (info?.polyline?.length) {
        result[i - 1] = { mode: to.transportMode || 'WALK', polyline: info.polyline, durationSeconds: info.durationSeconds, estimated: info.estimated }
      }
    } catch (e) {
      // 折线缺失仅影响绘制，不阻塞时间线
    }
  }
  stopSegments.value = result
  if (routeCtrl) {
    routeCtrl.draw(result.filter(Boolean))
  }
}

async function restoreLatestChat() {
  try {
    const chats = await assistantApi.listChats()
    const latest = (chats || [])[0]
    if (!latest) return
    chatId = latest.id
    saved.value = latest.status === 'SAVED'
    currentPlanVersion.value = latest.planVersion || 0
    const detail = await assistantApi.detail(chatId)
    currentPlanVersion.value = detail?.conversation?.planVersion || currentPlanVersion.value
    messages.value = (detail?.messages || []).map((m) => ({
      role: m.role === 'USER' ? 'user' : 'assistant',
      content: m.content
    }))
    if (detail?.plan) {
      plan.value = detail.plan
    }
  } catch (e) {
    // 无历史会话则等待用户开启新对话
  }
}

async function ensureChat() {
  if (chatId) return chatId
  const session = await assistantApi.createChat()
  chatId = session.id
  return chatId
}

function composeContent() {
  let content = input.value.trim()
  const refMap = myMaps.value.find((m) => m.id === referenceMapId.value)
  if (refMap) {
    content = `（请优先参考我共建的共享地图「${refMap.name}」里的地点）\n${content}`
  }
  return content
}

async function send() {
  const content = composeContent()
  if (!content || busy.value) return
  busy.value = true
  input.value = ''
  abortController = new AbortController()
  messages.value.push({ role: 'user', content })
  messages.value.push({ role: 'assistant', content: '', status: '连接助手…', streaming: true })
  streamingPlaceholderIndex = messages.value.length - 1
  scrollBottom()
  try {
    const id = await ensureChat()
    messages.value[streamingPlaceholderIndex].status = '正在思考…'
    await streamAssistantChat(id, content, handleEvent, abortController.signal)
  } catch (e) {
    handleEvent('error', { message: e?.message || '请求失败' })
  } finally {
    finishStreaming()
  }
}

function handleEvent(event, payload) {
  const placeholder = messages.value[streamingPlaceholderIndex]
  if (!placeholder) return
  if (event === 'status') {
    placeholder.status = typeof payload === 'string' ? payload : placeholder.status
  } else if (event === 'delta') {
    placeholder.content += typeof payload === 'string' ? payload : ''
    scrollBottom()
  } else if (event === 'plan') {
    placeholder.status = ''
    plan.value = payload
    saved.value = false
    // 新草稿版本未知（plan 事件不含版本号）——保存/编辑前由 detail / revise 接口取回
    currentPlanVersion.value = 0
  } else if (event === 'done') {
    placeholder.status = ''
    placeholder.streaming = false
  } else if (event === 'error') {
    placeholder.content = placeholder.content || ''
    placeholder.status = ''
    placeholder.streaming = false
    ElMessage.error(typeof payload === 'string' ? payload : payload?.message || '助手处理失败')
  }
}

function finishStreaming() {
  const placeholder = messages.value[streamingPlaceholderIndex]
  if (placeholder && placeholder.streaming) {
    placeholder.streaming = false
    placeholder.status = ''
  }
  busy.value = false
  streamingPlaceholderIndex = -1
}

/** 评审项：复制为新草稿（asNewDraft）→ 再次保存创建新活动，原活动不动 */
async function saveAsNewActivity() {
  if (!chatId || !plan.value || saving.value) return
  saving.value = true
  try {
    const draft = JSON.parse(JSON.stringify(plan.value))
    delete draft.lineageActivityId
    await assistantApi.revisePlan(chatId, {
      plan: draft,
      expectedPlanVersion: currentPlanVersion.value || null,
      asNewDraft: true
    })
    ElMessage.success('已复制为新草稿，正在另建新活动')
    await savePlan()
  } catch (e) {
    ElMessage.error(e?.message || '复制草稿失败，请重试')
  } finally {
    saving.value = false
  }
}

async function savePlan() {
  if (!chatId || saving.value) return
  saving.value = true
  try {
    await assistantApi.savePlan(chatId, currentPlanVersion.value
      ? { expectedPlanVersion: currentPlanVersion.value }
      : undefined)
    saved.value = true
    ElMessage.success(plan.value?.lineageActivityId ? '已更新活动，前往日程查看' : '已保存为活动，前往日程查看')
    router.push('/activities')
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请重试')
  } finally {
    saving.value = false
  }
}

/** 阶段 F：草稿轻量编辑（服务端重校验 + 版本防覆盖） */
function isoInput(value) {
  const d = toDate(value)
  return d ? `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}` : null
}

function toEditablePlan(source) {
  return {
    title: source.title || '',
    category: source.category || 'OTHER',
    summary: source.summary || '',
    startTime: isoInput(source.startTime),
    endTime: isoInput(source.endTime),
    maxMembers: source.maxMembers || 20,
    referenceMapId: source.referenceMapId ?? null,
    stops: (source.stops || []).map((s) => ({
      ...s,
      startTime: isoInput(s.startTime),
      endTime: isoInput(s.endTime)
    }))
  }
}

async function openEditor() {
  if (!chatId || !plan.value) return
  try {
    const detail = await assistantApi.detail(chatId)
    currentPlanVersion.value = detail?.conversation?.planVersion || currentPlanVersion.value
  } catch (e) {
    // 版本拿不到时按当前保存（服务端依旧行锁 + 校验兜底）
  }
  editForm.value = toEditablePlan(plan.value)
  editing.value = true
}

async function applyEdit() {
  if (revising.value || !editForm.value) return
  if (!editForm.value.title?.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!editForm.value.stops.length) {
    ElMessage.warning('至少保留一个站点')
    return
  }
  revising.value = true
  try {
    const resp = await assistantApi.revisePlan(chatId, {
      plan: editForm.value,
      expectedPlanVersion: currentPlanVersion.value || null
    })
    plan.value = resp.plan
    currentPlanVersion.value = resp.planVersion || currentPlanVersion.value + 1
    editing.value = false
    saved.value = false
    ElMessage.success('草稿已重新校验，时间线与地图已刷新')
  } catch (e) {
    ElMessage.error(e?.message || '修订失败，请重试')
  } finally {
    revising.value = false
  }
}

function moveStop(idx, delta) {
  const stops = editForm.value.stops
  const target = idx + delta
  if (target < 0 || target >= stops.length) return
  const [item] = stops.splice(idx, 1)
  stops.splice(target, 0, item)
}

function focusStop(stop) {
  if (!markerCtrl) return
  markerCtrl.focusPlace({
    id: `stop-${stop.seq}`,
    name: stop.name,
    longitude: Number(stop.longitude),
    latitude: Number(stop.latitude)
  })
}

function scrollBottom() {
  nextTick(() => {
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

/** 后端 LocalDateTime 可能是数组（[y,m,d,h,m(,s)]）或 ISO 字符串，统一兼容 */
function toDate(value) {
  if (!value) return null
  if (Array.isArray(value) && value.length >= 3) {
    return new Date(
      value[0],
      value[1] - 1,
      value[2],
      value[3] || 0,
      value[4] || 0,
      value[5] || 0
    )
  }
  return new Date(value)
}

function pad2(n) {
  return String(n).padStart(2, '0')
}

function clock(value) {
  const d = toDate(value)
  return d ? `${pad2(d.getHours())}:${pad2(d.getMinutes())}` : ''
}

function dateLabel(value) {
  const d = toDate(value)
  return d ? `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}` : ''
}

function stopTimeText(stop) {
  if (!stop.startTime) return ''
  return `${clock(stop.startTime)}${stop.endTime ? '–' + clock(stop.endTime) : ''}`
}
</script>

<style scoped>
.plan-stop__src {
  display: inline-block;
  padding: 0 4px;
  border-radius: 4px;
  font-size: 10px;
  background: rgba(64, 158, 255, 0.12);
  color: #2979cc;
}
.plan-stop__src.poi {
  background: rgba(144, 147, 153, 0.12);
  color: rgba(0, 0, 0, 0.45);
}

.assistant-page {
  display: grid;
  grid-template-columns: 400px 1fr;
  min-height: 100vh;
  background: #f5f7fa;
}

.chat-col {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  border-right: 1px solid #e8eaee;
  background: #fff;
}

.chat-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid #f0f0f2;
}
.chat-head__title {
  font-weight: 600;
  color: var(--brand-blue, #0052d9);
}
.chat-head__ref {
  margin-left: auto;
  width: 130px;
}

.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.msg-empty {
  text-align: center;
  color: rgba(0, 0, 0, 0.45);
  padding: 40px 12px;
  font-size: 13px;
  line-height: 1.8;
}
.msg-empty h3 {
  color: rgba(0, 0, 0, 0.85);
  margin-bottom: 6px;
}
.msg {
  display: flex;
}
.msg--user {
  justify-content: flex-end;
}
.bubble {
  max-width: 86%;
  padding: 9px 12px;
  border-radius: 14px;
  font-size: 13px;
  line-height: 1.65;
  word-break: break-word;
  white-space: pre-wrap;
}
.bubble--user {
  background: var(--brand-blue, #0052d9);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.bubble--assistant {
  background: #f3f5f8;
  color: rgba(0, 0, 0, 0.85);
  border-bottom-left-radius: 4px;
}
.msg__status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--brand-blue, #0052d9);
}
.spin-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand-blue, #0052d9);
  animation: pulse 1s infinite ease-in-out;
}
@keyframes pulse {
  50% { opacity: 0.3; transform: scale(0.8); }
}
.caret {
  color: var(--brand-blue, #0052d9);
}

.plan-card {
  margin: 8px 14px;
  padding: 12px 14px;
  border-radius: 16px;
  border: 1px solid #eef1f6;
  background: linear-gradient(180deg, #f7faff, #fff);
  box-shadow: 0 2px 10px rgba(0, 82, 217, 0.06);
}
.plan-card__title {
  margin: 0 0 8px;
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.plan-seg {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
  padding: 4px 0 0 24px;
}
.plan-stop {
  display: flex;
  gap: 10px;
  padding: 6px 0 6px 10px;
  border-left: 2px solid var(--brand-blue-light-7, #d6e4ff);
  margin-left: 8px;
  padding-left: 14px;
  cursor: pointer;
}
.plan-stop__dot {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--brand-blue, #0052d9);
  color: #fff;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
  margin: 1px 0 0 -22px;
}
.plan-stop__main {
  min-width: 0;
}
.plan-stop__head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  flex-wrap: wrap;
  font-size: 13px;
}
.hint {
  margin: 2px 0 0;
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
}
.plan-card__range {
  margin: 8px 0 10px;
  font-size: 13px;
  color: rgba(0, 0, 0, 0.75);
}
.plan-card__save {
  width: 100%;
  border-radius: 10px;
}

.input-row {
  display: flex;
  gap: 8px;
  padding: 10px 14px;
  border-top: 1px solid #f0f0f2;
}
.input-row :deep(.el-textarea__inner) {
  border-radius: 10px;
}

.map-col {
  position: relative;
  min-height: 100vh;
}
.map-canvas {
  position: absolute;
  inset: 0;
}
.map-fallback {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(0, 0, 0, 0.45);
  background: #f5f7fa;
}

/* —— 阶段 F：草稿编辑态 —— */
.plan-card__actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.plan-card__actions :deep(.el-button) {
  flex: 1;
}
.plan-ver {
  margin-left: 4px;
}
.plan-edit {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.plan-edit__row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.plan-edit__stop {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  background: rgba(64, 158, 255, 0.07);
  border: 1px dashed rgba(64, 158, 255, 0.25);
  border-radius: 10px;
}
.plan-edit__stophead {
  display: flex;
  align-items: center;
  gap: 10px;
}
.mini {
  cursor: pointer;
  font-size: 12px;
  opacity: 0.55;
  user-select: none;
}
.mini:hover {
  opacity: 1;
}
.mini--danger {
  color: #f56c6c;
  opacity: 0.85;
}
.dt {
  height: 24px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  background: rgba(255, 255, 255, 0.65);
  border-radius: 6px;
  padding: 2px 6px;
  font-size: 12px;
  color: inherit;
}
.plan-edit__when {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

@media (max-width: 768px) {
  .assistant-page {
    grid-template-columns: 1fr;
  }
  .map-col {
    order: 2;
    min-height: 46vh;
  }
  .chat-col {
    min-height: 54vh;
    border-right: none;
  }
}
</style>
