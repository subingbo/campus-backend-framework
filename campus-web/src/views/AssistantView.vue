<template>
  <div class="assistant-page">
    <aside class="chat-col">
      <header class="chat-head">
        <span class="chat-head__title">AI 规划助手</span>
        <el-dropdown trigger="click" @visible-change="onSessionsVisible" @command="switchConversation">
          <el-button size="small" :disabled="busy">会话 ▾</el-button>
          <template #dropdown>
            <el-dropdown-menu class="session-menu">
              <el-dropdown-item
                v-for="c in chatSessions"
                :key="c.id"
                :command="c.id"
                :class="{ 'session-menu__item--active': c.id === activeChatId }"
              >
                <div class="session-menu__title" :class="{ 'is-empty': !c.title }">{{ c.title || '新的规划' }}</div>
                <div class="session-menu__meta">
                  <el-tag v-if="c.status === 'SAVED'" size="small" type="success" effect="plain">已保存</el-tag>
                  <el-tag v-else-if="c.planVersion" size="small" type="warning" effect="plain">草稿 v{{ c.planVersion }}</el-tag>
                  <span class="session-menu__time">{{ fmt(c.updatedAt) }}</span>
                </div>
              </el-dropdown-item>
              <el-dropdown-item v-if="!chatSessions.length" disabled>暂无历史会话</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button size="small" :disabled="busy" @click="newChat">＋ 新对话</el-button>
        <el-select
          v-model="referenceMapIds"
          size="small"
          multiple
          collapse-tags
          clearable
          placeholder="参考地图"
          class="chat-head__ref"
        >
          <el-option v-for="m in myMaps" :key="m.id" :value="m.id" :label="m.name" />
        </el-select>
      </header>

      <div ref="listRef" class="chat-scroll" @scroll="onChatScroll">
      <div class="msg-list">
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
              <!-- 流式期间纯文本（避免每段 delta 全量重排），结束后切 Markdown 渲染 -->
              <AssistantReply v-if="msg.content" :text="msg.content" :streaming="msg.streaming" :animate="msg.animate" @reveal="scrollBottom()" />
              <!-- 本条消息涉及的地点：点芯片聚焦地图并打开浮窗；有草稿时按路线站序排前并带序号 -->
              <div v-if="msg.geoPlaces && msg.geoPlaces.length" class="geo-chips">
                <span
                  v-for="row in orderedChips(msg)"
                  :key="row.chip.key"
                  class="geo-chip"
                  :class="'geo-chip--' + String(row.chip.source || '').toLowerCase()"
                  @click="focusGeoPlace(row.chip)"
                >
                  <span v-if="row.seq" class="geo-chip__seq">{{ row.seq }}</span>
                  <span class="geo-chip__badge">{{ sourceLabel(row.chip.source) }}</span>{{ row.chip.name }}
                </span>
              </div>
              <AmapRouteLinks v-if="msg.geoEvents?.length" :events="msg.geoEvents" />
            </div>
          </div>
        </template>
        <!-- 确认式问句兜底：模型停下来问“要不要提交”时一键回复，无需打字 -->
        <div v-if="quickReplies.length" class="quick-replies">
          <button
            v-for="q in quickReplies"
            :key="q"
            type="button"
            class="quick-reply"
            @click="sendQuickReply(q)"
          >{{ q }}</button>
        </div>
      </div>

      <div v-if="plan" class="plan-card">
        <div class="plan-card__header">
        <h3 class="plan-card__title">
          📅 {{ plan.title }}
          <el-tag size="small" effect="plain">{{ PLAN_TEXT[planCategory] || '' }}</el-tag>
          <el-tag v-if="currentPlanVersion" size="small" type="info" effect="plain" class="plan-ver">v{{ currentPlanVersion }}</el-tag>
          <el-tag v-if="plan.routeConfidence === 'ESTIMATED'" size="small" type="warning" effect="plain">交通·估算</el-tag>
          <el-tag v-else-if="plan.routeConfidence === 'AMAP'" size="small" effect="plain">交通·高德路线</el-tag>
          <el-tag v-else-if="plan.routeConfidence === 'PARTIAL'" size="small" type="warning" effect="plain">交通·部分未核验</el-tag>
          <el-tag v-if="plan.constraintStatus === 'CONFLICT'" size="small" type="danger" effect="plain">有冲突</el-tag>
          <el-tag v-else-if="plan.constraintStatus === 'TENTATIVE'" size="small" type="warning" effect="plain">暂定</el-tag>
          <el-tag v-else-if="plan.constraintStatus === 'FEASIBLE'" size="small" type="success" effect="plain">忙闲可行</el-tag>
        </h3>
        <button type="button" class="plan-card__toggle" aria-controls="assistant-plan-body" :aria-expanded="planExpanded" @click="planExpanded = !planExpanded">{{ planExpanded ? '收起' : '展开' }} <span aria-hidden="true">{{ planExpanded ? '⌃' : '⌄' }}</span></button>
        </div>
        <p v-if="!planExpanded" class="plan-card__summary">{{ plan.stops?.length || 0 }} 站 · {{ planRangeText }}</p>
        <div id="assistant-plan-body" v-show="planExpanded">
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
                  <a
                    v-if="stop.navigationUrl"
                    class="plan-stop__nav"
                    :href="stop.navigationUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                    :title="stop.navigationTitle"
                    @click.stop
                  >{{ stop.navigationLabel }} ↗</a>
                </div>
                <p class="hint">
                  <template v-if="stop.placeText">📍{{ stop.placeText }} · </template>
                  <template v-if="stop.transportText">{{ stop.transportText }}</template>
                </p>
                <p v-if="stop.note" class="hint">{{ stop.note }}</p>
              </div>
            </div>
          </template>
          <div
            v-if="planMembers.length || planConflicts.length || plan.warnings?.length"
            class="plan-people"
          >
            <button type="button" class="plan-people__toggle" @click="peopleOpen = !peopleOpen">
              <span>👥 成员 {{ knownMemberCount }}/{{ planMembers.length }}</span>
              <span v-if="planConflicts.length" class="plan-people__conflict">
                ⚠ {{ planConflicts.length }} 个冲突
              </span>
              <span class="plan-people__chevron">{{ peopleOpen ? '收起' : '展开' }}</span>
            </button>
            <div v-if="peopleOpen" class="plan-people__body">
              <div v-if="planMembers.length" class="plan-members">
                <span
                  v-for="member in planMembers"
                  :key="member.userId"
                  class="plan-member"
                  :class="{ 'is-unknown': member.status === 'UNKNOWN' }"
                >
                  {{ member.nickname }}
                  <em>{{ member.status === 'UNKNOWN' ? '未授权' : '已核验' }}</em>
                </span>
              </div>
              <div v-for="(conflict, i) in planConflicts" :key="'conflict-' + i" class="plan-conflict">
                <p class="plan-conflict__line">
                  ⚠ <strong>{{ conflict.nickname }}</strong>
                  {{ conflictTimeText(conflict) }} 与计划冲突
                  <span v-if="conflict.severity === 'BLOCKING'">（重叠 {{ conflict.overlapMinutes }} 分钟）</span>
                  <span v-else>（前后衔接不足 10 分钟）</span>
                </p>
                <p class="plan-conflict__suggestion">建议：{{ conflict.suggestion }}</p>
              </div>
              <p v-if="plan.warnings?.length" class="hint">{{ plan.warnings.join('；') }}</p>
            </div>
          </div>
          <p class="plan-card__range">🕐 {{ planRangeText }}</p>
          <div class="plan-card__actions">
            <el-button size="small" @click="planDialogVisible = true">预览</el-button>
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
              <el-select
                v-if="idx > 0"
                v-model="stop.transportMode"
                size="small"
                placeholder="前往本站的交通方式"
                clearable
                class="plan-edit__transport"
              >
                <el-option
                  v-for="opt in TRANSPORT_OPTIONS.slice(1)"
                  :key="opt.value"
                  :value="opt.value"
                  :label="opt.label"
                />
              </el-select>
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
      </div>
      </div>

      <button v-if="plan" type="button" class="draft-toggle" :aria-expanded="planExpanded" aria-controls="assistant-plan-body" @click="togglePlan">
        <span>📅 {{ saved ? '已保存活动' : '活动草稿' }} · {{ plan.stops?.length || 0 }} 站</span>
        <span>{{ planExpanded ? '收起活动' : '查看活动' }} {{ planExpanded ? '⌃' : '⌄' }}</span>
      </button>
      <p v-if="locateNote" class="input-note">{{ locateNote }}</p>
      <div class="transport-row">
        <span class="transport-row__label">出行方式</span>
        <el-select v-model="transportChoice" size="small" class="transport-row__select" placeholder="按问题描述">
          <el-option
            v-for="opt in TRANSPORT_OPTIONS"
            :key="opt.value"
            :value="opt.value"
            :label="opt.label"
          />
        </el-select>
        <span class="hint">不指定时直接按问题描述规划</span>
      </div>
      <footer class="input-row">
        <div class="locate-col">
          <!-- 打开即实时：每次发送前重新取样设备定位（GCJ-02），随请求注入 get_my_location 工具 -->
          <el-button
            class="locate-toggle"
            size="small"
            :plain="!attachLocation"
            :type="attachLocation ? 'primary' : ''"
            :loading="locating"
            :title="attachLocation ? '每次发送都会取实时位置；点一下关闭' : '每次发送带上实时位置（浏览器可能请求定位权限）'"
            @click="toggleAttachLocation"
          >
            📍 位置
          </el-button>
          <!-- 手动纠偏：在地图上点一下设为我的位置（优先于设备定位，可清除） -->
          <el-button
            size="small"
            :type="pickMode ? 'warning' : ''"
            :plain="!pickMode"
            :title="pickMode ? '请在地图上点击你的真实位置' : '定位不准？点一下后在地图上点选你的真实位置'"
            @click="togglePickMode"
          >
            {{ pickMode ? '点地图…' : '纠偏' }}
          </el-button>
          <el-button v-if="manualLocation" size="small" plain type="danger" @click="clearManualLocation">清除</el-button>
        </div>
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
      <!-- 地点浮窗：点聊天芯片或地图标记后锚定在该点附近（工作台同款像素锚定） -->
      <div v-if="geoPopup" ref="geoPopRef" class="geo-pop" :style="geoPopupStyle">
        <div class="geo-pop__head">
          <span class="geo-pop__name">{{ geoPopup.name }}</span>
          <span class="geo-pop__src">{{ sourceLabel(geoPopup.source) }}</span>
          <button type="button" class="geo-pop__close" @click="geoPopup = null">×</button>
        </div>
        <p v-if="geoPopup.address" class="geo-pop__line">{{ geoPopup.address }}</p>
        <p v-if="geoPopup.categoryName || geoPopup.distanceMeters != null" class="geo-pop__line">
          <template v-if="geoPopup.categoryName">{{ geoPopup.categoryName }}</template>
          <template v-if="geoPopup.distanceMeters != null"> · 约 {{ Math.round(geoPopup.distanceMeters) }} 米</template>
        </p>
        <p v-if="geoPopup.noteDigest" class="geo-pop__note">📝 {{ geoPopup.noteDigest }}</p>
        <div class="geo-pop__actions">
          <el-button size="small" @click="focusGeoPlace(geoPopup)">居中</el-button>
          <el-button size="small" @click="navigateGeoPopup(geoPopup)">🧭 高德导航</el-button>
          <el-button v-if="geoPopup.mapId" size="small" type="primary" @click="openInMap(geoPopup)">在共建地图打开</el-button>
        </div>
      </div>
    </section>

    <!-- 用户点击预览时打开；生成草稿不打断聊天。 -->
    <el-dialog v-model="planDialogVisible" title="📅 活动预览" width="380px" append-to-body class="plan-dialog">
      <template v-if="plan">
        <p class="plan-dialog__name">{{ plan.title }}</p>
        <p class="hint">🕐 {{ planRangeText }} · {{ (plan.stops || []).length }} 站
          <el-tag v-if="plan.routeConfidence === 'AMAP'" size="small" effect="plain">交通·高德路线</el-tag>
          <el-tag v-else-if="plan.routeConfidence === 'ESTIMATED'" size="small" type="warning" effect="plain">交通·估算</el-tag>
          <el-tag v-else-if="plan.routeConfidence === 'PARTIAL'" size="small" type="warning" effect="plain">交通·部分未核验</el-tag>
        </p>
        <p class="hint">草稿不是终态：可先在聊天卡片里编辑时间与站点，或直接保存为活动邀请报名。</p>
      </template>
      <template #footer>
        <el-button size="small" @click="planDialogVisible = false">先看看再说</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="saved || editing"
          @click="planDialogVisible = false; savePlan()"
        >{{ saved ? '已保存到活动日程' : (plan?.lineageActivityId ? '保存并更新活动' : '保存为活动') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onUnmounted, ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { assistantApi, mapApi } from '../api'
import { streamAssistantChat } from '../api/aiStream'
import { loadAmap, createPlaceMarkerController, createRouteLineController, createAmapLocationProvider } from '../composables/useAmap'
import { useLocation } from '../composables/useLocation'
import AssistantReply from '../components/AssistantReply.vue'
import AmapRouteLinks from '../components/AmapRouteLinks.vue'
import { fmt } from '../utils/format'
import { buildAmapNavigationUrl, openAmapNavigation } from '../utils/amapNavigation'

const router = useRouter()
const route = useRoute()

const PLAN_TEXT = { MEETING: '会议', COMPETITION: '比赛', OTHER: '活动' }
const TRANSPORT_TEXT = {
  WALK: '🚶 步行前往',
  RIDE: '🚲 骑行前往',
  DRIVE: '🚗 驾车前往',
  TAXI: '🚕 打车前往',
  TRANSIT: '🚌 公交前往'
}
const TRANSPORT_OPTIONS = [
  { value: '', label: '不指定' },
  { value: 'WALK', label: '步行' },
  { value: 'DRIVE', label: '驾车' },
  { value: 'TAXI', label: '打车' },
  { value: 'RIDE', label: '骑车' },
  { value: 'TRANSIT', label: '公共交通' }
]
const TRANSPORT_CHOICE_TEXT = { WALK: '步行', DRIVE: '驾车', TAXI: '打车', RIDE: '骑车', TRANSIT: '公共交通' }

const messages = ref([])
const input = ref('')
const pendingPrompt = ref('')
const busy = ref(false)
const plan = ref(null)
const planExpanded = ref(true)
const followLatest = ref(true)
const saving = ref(false)
const saved = ref(false)
const referenceMapIds = ref([])
const transportChoice = ref('')
const peopleOpen = ref(false)
const lastLocation = ref(null)
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
const chatSessions = ref([])
const activeChatId = ref(null)
const planDialogVisible = ref(false)
let streamingPlaceholderIndex = -1
let abortController = null
let markerCtrl = null
let routeCtrl = null
let mapApi_ = null
let locProvider_ = null

/** 定位层：发送前实时取样（GCJ-02），失败不阻塞发送 */
const {
  locating,
  error: locateError,
  locate
} = useLocation(() => locProvider_)
const attachLocation = ref(localStorage.getItem('campus.assistant.locate') === '1')
const locateNote = ref('')

function toggleAttachLocation() {
  attachLocation.value = !attachLocation.value
  localStorage.setItem('campus.assistant.locate', attachLocation.value ? '1' : '0')
  locateNote.value = ''
}

const isMobile = () => window.matchMedia('(max-width: 768px)').matches

/** ===== 对话地理信息（实时 + 历史回放）：地图标记/折线/我的位置 + 可点击浮窗 ===== */
const geoPlaces = ref([])
const geoRoutes = ref([])
const geoPopup = ref(null)
const geoPopupStyle = ref({})
const geoPopRef = ref(null)
let offMapViewport = null

/** 手动纠正的“我的位置”（优先于设备定位，localStorage 记忆） */
const pickMode = ref(false)
const manualLocation = ref(readManualLocation())

function readManualLocation() {
  try {
    return JSON.parse(localStorage.getItem('campus.assistant.myLocation') || 'null')
  } catch (e) {
    return null
  }
}

function sourceLabel(source) {
  if (source === 'GROUP_MAP') return '共建'
  if (source === 'PERSONAL_MAP') return '收藏'
  if (source === 'CAMPUS_LIB') return '校园库'
  if (source === 'AMAP_POI') return 'POI'
  if (source === 'USER_LOCATION') return '定位'
  return '地点'
}

/** 稳定去重键：系统地点按 placeId，POI 按坐标（5 位小数） */
function geoKeyOf(p) {
  if (p.placeId != null) return `p-${p.placeId}`
  return `c-${Number(p.longitude).toFixed(5)},${Number(p.latitude).toFixed(5)}`
}

function decorate(p) {
  return { ...p, key: geoKeyOf(p), mapId: p.mapIds && p.mapIds.length ? p.mapIds[0] : null }
}

/** 芯片排序：有行程草稿时，命中行程站点的芯片排前并按站序，其余保持原（时间足迹）顺序 */
function orderedChips(msg) {
  const chips = msg.geoPlaces || []
  if (!chips.length) return []
  const stops = plan.value?.stops
  if (!stops || !stops.length) {
    return chips.map((chip) => ({ chip, seq: null }))
  }
  const coordOf = (o) => `c-${Number(o.longitude).toFixed(5)},${Number(o.latitude).toFixed(5)}`
  const seqOfChip = new Map()
  for (const stop of stops) {
    if (seqOfChip.size >= chips.length) break
    const chip = chips.find((c) =>
      !seqOfChip.has(c)
      && ((stop.placeId != null && c.placeId != null && c.placeId === stop.placeId)
        || (stop.longitude != null && coordOf(c) === coordOf(stop))))
    if (chip) seqOfChip.set(chip, stop.seq)
  }
  return [...chips]
    .sort((a, b) => {
      const sa = seqOfChip.get(a)
      const sb = seqOfChip.get(b)
      if (sa != null && sb != null) return sa - sb
      if (sa != null) return -1
      if (sb != null) return 1
      return 0
    })
    .map((chip) => ({ chip, seq: seqOfChip.get(chip) ?? null }))
}

/** 从事件里抽出可展示的地点 chip（含 mapId 供“在共建地图打开”）；同点合并，保留首次出现顺序 */
function collectPlacesOf(events) {
  const out = []
  const placeIdKeys = new Set()
  const coordIndex = new Map()
  for (const ev of events || []) {
    for (const p of ev.places || []) {
      if (p.longitude == null || p.latitude == null) continue
      const coordKey = `c-${Number(p.longitude).toFixed(5)},${Number(p.latitude).toFixed(5)}`
      const seen = coordIndex.get(coordKey)
      if (p.placeId != null) {
        if (placeIdKeys.has(p.placeId) || (seen && seen.hasPlaceId)) continue
        placeIdKeys.add(p.placeId)
        const item = decorate(p)
        if (seen && !seen.hasPlaceId) {
          // 同坐标先出现了无 ID 条目：原位替换为带 ID 版本（浮窗字段更全）
          out.splice(out.indexOf(seen.item), 1, item)
        } else {
          out.push(item)
        }
        coordIndex.set(coordKey, { item, hasPlaceId: true })
      } else {
        if (seen) continue
        const item = decorate(p)
        out.push(item)
        coordIndex.set(coordKey, { item, hasPlaceId: false })
      }
    }
  }
  return out
}

/** 把一批 geo 事件并入地图状态并重绘（实时与历史回放共用） */
function applyGeoEvents(events) {
  if (!events || !events.length) return
  let changed = false
  for (const ev of events) {
    for (const raw of ev.places || []) {
      if (raw.longitude == null || raw.latitude == null) continue
      if (raw.source === 'USER_LOCATION') {
        // 我的位置只画蓝点（不重复建普通标记）；chip 仍由 collectPlacesOf 提供
        markerCtrl?.setMyLocation({ lng: raw.longitude, lat: raw.latitude, accuracy: raw.distanceMeters || 0 })
        continue
      }
      const item = decorate(raw)
      const idx = geoPlaces.value.findIndex((x) => x.key === item.key)
      if (idx >= 0) geoPlaces.value.splice(idx, 1, { ...geoPlaces.value[idx], ...item })
      else geoPlaces.value.push(item)
      changed = true
    }
    if (ev.route?.polyline?.length > 1) {
      geoRoutes.value.push({ mode: ev.route.mode || 'WALK', polyline: ev.route.polyline })
      changed = true
    }
    for (const seg of ev.segments || []) {
      if (seg.polyline?.length > 1) {
        geoRoutes.value.push({ mode: seg.transportMode || 'WALK', polyline: seg.polyline })
        changed = true
      }
    }
  }
  if (changed) redrawMap()
}

/** 统一重绘：标记（地点集合）→ 折线（geo 路线 + 草稿站点间预览），折线必须后画（render 会 clearMap） */
function redrawMap({ fitView = false } = {}) {
  if (!markerCtrl) return
  const markers = geoPlaces.value.map((p) => ({
    id: p.key,
    name: p.name,
    longitude: Number(p.longitude),
    latitude: Number(p.latitude)
  }))
  markerCtrl.render(markers, { fitView })
  const lines = [...geoRoutes.value]
  for (const seg of stopSegments.value) {
    if (seg?.polyline?.length > 1) lines.push({ mode: seg.mode, polyline: seg.polyline })
  }
  if (lines.length) routeCtrl?.draw(lines)
}

function focusGeoPlace(p) {
  if (!p) return
  if (isMobile()) {
    mapRef.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }
  markerCtrl?.focusPlace({ id: p.key, name: p.name, longitude: Number(p.longitude), latitude: Number(p.latitude) })
  geoPopup.value = p
  nextTick(updateGeoPopupPos)
}

function updateGeoPopupPos() {
  const p = geoPopup.value
  if (!p || !mapRef.value) return
  const pt = markerCtrl?.projectToContainer?.({ longitude: p.longitude, latitude: p.latitude })
  if (!pt) {
    geoPopupStyle.value = { right: '12px', bottom: '16px' }
    return
  }
  const w = geoPopRef.value?.offsetWidth || 260
  const h = geoPopRef.value?.offsetHeight || 120
  const cw = mapRef.value.clientWidth
  const ch = mapRef.value.clientHeight
  const below = pt.y - 48 - h < 8
  const x = cw > w + 16 ? Math.min(Math.max(pt.x, w / 2 + 8), cw - w / 2 - 8) : cw / 2
  const top = below ? Math.min(pt.y + 12, Math.max(ch - h - 8, 8)) : pt.y - 48
  geoPopupStyle.value = {
    left: `${Math.round(x)}px`,
    top: `${Math.round(Math.max(top, 8))}px`,
    transform: below ? 'translate(-50%, 0)' : 'translate(-50%, -100%)'
  }
}

function openInMap(p) {
  if (p?.mapId && p?.placeId != null) {
    router.push(`/maps/${p.mapId}?place=${p.placeId}`)
  }
}

/** 地图交互：纠偏模式下点选"我的位置"，否则点空白关闭浮窗 */
function onMapClick(lng, lat) {
  if (pickMode.value) {
    setManualLocation(lng, lat)
    return
  }
  geoPopup.value = null
}

function onMarkerClick(place) {
  const item = geoPlaces.value.find((p) => p.key === place.id)
  if (!item) return
  geoPopup.value = item
  nextTick(updateGeoPopupPos)
}

function togglePickMode() {
  pickMode.value = !pickMode.value
  locateNote.value = pickMode.value ? '请在地图上点击你的真实位置（点击「点地图…」取消）' : ''
}

function setManualLocation(lng, lat) {
  manualLocation.value = { lng, lat }
  localStorage.setItem('campus.assistant.myLocation', JSON.stringify(manualLocation.value))
  pickMode.value = false
  markerCtrl?.setMyLocation({ lng, lat, accuracy: 0, locationType: 'manual' })
  locateNote.value = '已把手动位置设为“我的位置”（发送时优先使用，可清除）'
}

function clearManualLocation() {
  manualLocation.value = null
  localStorage.removeItem('campus.assistant.myLocation')
  markerCtrl?.clearMyLocation()
  locateNote.value = '已清除手动位置，将使用设备定位'
}

const planCategory = computed(() => plan.value?.category || 'OTHER')
const planMembers = computed(() => plan.value?.members || [])
const planConflicts = computed(() => plan.value?.conflicts || [])
const knownMemberCount = computed(() => planMembers.value.filter((m) => m.status === 'KNOWN').length)

function conflictTimeText(conflict) {
  return `${dateLabel(conflict.startTime)} ${clock(conflict.startTime)}–${clock(conflict.endTime)}`
}

async function resolveNavigationFrom() {
  if (manualLocation.value) {
    return { lng: manualLocation.value.lng, lat: manualLocation.value.lat, name: '我的位置' }
  }
  if (lastLocation.value) return lastLocation.value
  const loc = await locate()
  if (!loc) return null
  lastLocation.value = { lng: loc.lng, lat: loc.lat, name: '我的位置' }
  return lastLocation.value
}

async function navigateGeoPopup(place) {
  if (!place || place.longitude == null || place.latitude == null) return
  const from = await resolveNavigationFrom()
  openAmapNavigation({
    from,
    to: { lng: Number(place.longitude), lat: Number(place.latitude), name: place.name },
    mode: 'WALK'
  })
}

/** 站点行 enrich：时间文本 + 站间交通建议（route 预览返回后填充） */
const enrichedStops = computed(() => {
  const stops = plan.value?.stops || []
  return stops.map((stop, i) => {
    const segment = stopSegments.value[i - 1]
    const timeText = stopTimeText(stop)
    let segmentText = ''
    if (i > 0) {
      const mode = TRANSPORT_TEXT[stop.transportMode] || '🚶 前往下一站'
      if (segment?.durationSeconds) {
        segmentText = `${mode} · 约 ${Math.max(1, Math.ceil(segment.durationSeconds / 60))} 分钟${segment.estimated ? '（估算）' : ''}`
      } else if (stop.transportMode) {
        segmentText = mode
      }
    }
    const from = i > 0 ? stops[i - 1] : manualLocation.value || lastLocation.value
    return {
      ...stop,
      navigationUrl: buildAmapNavigationUrl({ from, to: stop, mode: stop.transportMode || 'WALK' }),
      navigationLabel: i > 0 ? '高德路线' : from ? '高德导航' : '高德地点',
      navigationTitle: i > 0 ? `从${stops[i - 1].name}到${stop.name}` : from ? `从我的位置到${stop.name}` : '在高德中查看地点',
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
  const requestedChat = Number(route.query.chat)
  if (Number.isFinite(requestedChat) && requestedChat > 0) {
    try {
      await loadConversation(requestedChat)
    } catch (e) {
      ElMessage.error(e.message || '无法打开指定会话：仅发起人可查看')
      await restoreLatestChat()
    }
  } else {
    await restoreLatestChat()
  }
})

onUnmounted(() => {
  abortController?.abort()
  offMapViewport?.()
})

watch(plan, async (value) => {
  stopSegments.value = []
  if (value) {
    renderPlanOnMap(value)
    await loadRouteSegments(value)
  }
})

function initMap() {
  loadAmap(mapRef.value, onMapClick).then(({ AMap, map, geolocation }) => {
    markerCtrl = createPlaceMarkerController(AMap, map, onMarkerClick)
    routeCtrl = createRouteLineController(AMap, map)
    mapApi_ = { AMap, map }
    offMapViewport = markerCtrl.onViewportChange(() => updateGeoPopupPos())
    if (geolocation) {
      locProvider_ = createAmapLocationProvider(geolocation)
    }
    // 已有手动位置：先画蓝点；有历史 geo 或草稿再重绘
    if (manualLocation.value) {
      markerCtrl.setMyLocation({ lng: manualLocation.value.lng, lat: manualLocation.value.lat, accuracy: 0, locationType: 'manual' })
    }
    if (plan.value) {
      renderPlanOnMap(plan.value)
    } else if (geoPlaces.value.length) {
      redrawMap({ fitView: geoPlaces.value.length > 1 })
    }
  }).catch((e) => {
    mapError.value = e?.message || '地图加载失败'
  })
}

/** 草稿站点并入统一标记集合（键 stop-N），与对话 geo 点位共存于同一张地图 */
function renderPlanOnMap(value) {
  if (!markerCtrl) return
  const planStops = (value.stops || []).filter((s) => s.longitude != null)
  geoPlaces.value = geoPlaces.value.filter((p) => !String(p.key).startsWith('stop-'))
  for (const s of planStops) {
    geoPlaces.value.push({
      key: `stop-${s.seq}`,
      placeId: s.placeId ?? null,
      name: s.name,
      longitude: Number(s.longitude),
      latitude: Number(s.latitude),
      source: s.source || null,
      mapId: s.sourceMapId ?? null
    })
  }
  redrawMap({ fitView: planStops.length > 1 })
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
      if (info) {
        result[i - 1] = { mode: to.transportMode || 'WALK', polyline: info.polyline || [], durationSeconds: info.durationSeconds, estimated: info.estimated || info.provider === 'estimate' }
      }
    } catch (e) {
      // 折线缺失仅影响绘制，不阻塞时间线
    }
  }
  if (plan.value !== value) return
  stopSegments.value = result
  redrawMap()
}

async function restoreLatestChat() {
  try {
    const chats = await assistantApi.listChats()
    chatSessions.value = chats || []
    if (!chatSessions.value.length) return
    // 优先回到上次正看的会话（已不在列表则回退最近一条）
    let target = null
    try {
      const lastId = Number(localStorage.getItem('campus.assistant.lastChat'))
      target = chatSessions.value.find((c) => c.id === lastId) || null
    } catch (e) {
      target = null
    }
    if (!target) target = chatSessions.value[0]
    await loadConversation(target.id)
  } catch (e) {
    // 无历史会话则等待用户开启新对话
  }
}

/** 打开会话下拉时刷新列表（保持“最近更新优先”） */
async function onSessionsVisible(visible) {
  if (!visible) return
  try {
    chatSessions.value = await assistantApi.listChats()
  } catch (e) {
    /* 拉取失败保留旧列表 */
  }
}

/** 加载指定会话：消息 + geo 回放 + 草稿卡片（restoreLatestChat / switchConversation 共用） */
async function loadConversation(id) {
  chatId = id
  activeChatId.value = id
  planDialogVisible.value = false
  // 记住最后查看的会话：刷新/重进页面回到当前正看的会话，而不是“最近更新”的那条
  try { localStorage.setItem('campus.assistant.lastChat', String(id)) } catch (e) { /* 隐私模式忽略 */ }
  const detail = await assistantApi.detail(id)
  const meta = detail?.conversation
  saved.value = meta?.status === 'SAVED'
  followLatest.value = true
  currentPlanVersion.value = meta?.planVersion || 0
  // 历史消息连同其 geo_json 一起回放：chips + 地图标记/折线/蓝点
  messages.value = (detail?.messages || []).map((m) => {
    const item = {
      role: m.role === 'USER' ? 'user' : 'assistant',
      content: m.content
    }
    const events = parseGeoJson(m.geoJson)
    if (events.length) {
      item.geoEvents = events
      item.geoPlaces = collectPlacesOf(events)
      applyGeoEvents(events)
    }
    return item
  })
  if (detail?.plan) {
    plan.value = detail.plan
    planExpanded.value = !saved.value
  }
  // 群语境以服务端持久化的 sourceMapId 为准；不信任前端 plan.sourceMapIds
  if (detail?.sourceMapId) {
    if (myMaps.value.some((m) => m.id === detail.sourceMapId)) {
      referenceMapIds.value = [detail.sourceMapId]
    } else {
      referenceMapIds.value = []
      ElMessage.warning('你已不在该群，已退出群规划语境')
    }
  }
  // 刷新/分享链接回到当前会话
  router.replace({ path: '/assistant', query: { chat: String(id) } })
}

/** 切到历史会话：先清空上一会话的界面状态（消息/草稿/地图覆盖物），再加载目标会话 */
async function switchConversation(id) {
  if (!id || id === activeChatId.value || busy.value) return
  messages.value = []
  plan.value = null
  currentPlanVersion.value = 0
  saved.value = false
  editing.value = false
  stopSegments.value = []
  geoPlaces.value = []
  geoRoutes.value = []
  geoPopup.value = null
  redrawMap()
  try {
    await loadConversation(id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function ensureChat() {
  if (chatId) return chatId
  const session = await assistantApi.createChat()
  chatId = session.id
  return chatId
}

/** 解析助手消息携带的 geo_json（历史回放）；坏数据静默忽略 */
function parseGeoJson(geoJson) {
  if (!geoJson) return []
  try {
    const parsed = JSON.parse(geoJson)
    return Array.isArray(parsed) ? parsed : []
  } catch (e) {
    return []
  }
}

/** 手动开新会话：清空当前消息流与草稿（原会话仍保留在服务端会话列表里） */
async function newChat() {
  try {
    const session = await assistantApi.createChat()
    chatId = session.id
    activeChatId.value = session.id
    planDialogVisible.value = false
    messages.value = []
    plan.value = null
    currentPlanVersion.value = 0
    saved.value = false
    editing.value = false
    stopSegments.value = []
    geoPlaces.value = []
    geoRoutes.value = []
    geoPopup.value = null
    redrawMap()
    ElMessage.success('已开启新对话')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 确认式问句兜底：模型停下来问“要不要提交”时给一键回复 chips（点击即发送该文本） */
const quickReplies = computed(() => {
  if (busy.value || plan.value || editing.value) return []
  const last = [...messages.value].reverse().find((m) => m.role === 'assistant')
  const text = last?.content || ''
  if (!text || text.length > 240) return []
  const asksConfirm = /(提交|确认|要不要|可以吗|行吗|好吗|需要我)/.test(text) && /[？?]/.test(text)
  return asksConfirm ? ['提交吧', '先不提交'] : []
})

function sendQuickReply(text) {
  input.value = text
  send()
}

function composeContent() {
  let content = input.value.trim()
  const chosen = myMaps.value.filter((m) => referenceMapIds.value.includes(m.id))
  if (chosen.length) {
    const names = chosen.map((m) => `「${m.name}」`).join('')
    content = `（请优先参考我共建的共享地图${names}里的地点）\n${content}`
  }
  if (transportChoice.value) {
    const label = TRANSPORT_CHOICE_TEXT[transportChoice.value] || transportChoice.value
    content += `\n（默认出行偏好：${label}，仅在本条正文未指定方式时使用；正文明确的步行、驾车等要求优先。如耗时只能估算，请明确说明。）`
  }
  return content
}

async function send() {
  const content = composeContent()
  if (!content || busy.value) return
  // 开关打开时每条消息实时取样定位；失败不阻塞发送，只是本条不带位置
  // 参考地图随消息上送：后端据此识别群语境，生成同伴忙闲与冲突提示
  let payload = referenceMapIds.value.length
    ? { referenceMapIds: [...referenceMapIds.value] }
    : null
  if (attachLocation.value) {
    // 手动纠正的位置优先：无需再等设备定位（GPS 被拒时也能带上位置）
    let loc = null
    let source = 'device'
    if (manualLocation.value) {
      loc = { lng: manualLocation.value.lng, lat: manualLocation.value.lat, accuracy: 0 }
      source = 'manual'
    } else {
      loc = await locate()
    }
    if (loc) {
      lastLocation.value = { lng: loc.lng, lat: loc.lat, name: '我的位置' }
      payload = {
        ...(payload || {}),
        userLocation: {
          longitude: loc.lng,
          latitude: loc.lat,
          accuracy: loc.accuracy || null,
          capturedAt: new Date().toISOString(),
          source
        }
      }
      locateNote.value = source === 'manual'
        ? '本条使用手动纠正的位置'
        : `已附实时位置（${Math.round(Number(loc.accuracy)) || '精度未知'} 米）`
    } else {
      locateNote.value = `未获取到定位，本条不带位置发送（${locateError.value || '定位失败'}）`
    }
  }
  busy.value = true
  planExpanded.value = false
  // 首个有效 SSE 事件到达前不清空输入：401/网络失败时可原样恢复
  pendingPrompt.value = input.value
  planDialogVisible.value = false
  abortController = new AbortController()
  messages.value.push({ role: 'user', content })
  messages.value.push({ role: 'assistant', content: '', status: '连接助手…', streaming: true, animate: true })
  streamingPlaceholderIndex = messages.value.length - 1
  followLatest.value = true
  scrollBottom()
  try {
    const id = await ensureChat()
    messages.value[streamingPlaceholderIndex].status = '正在思考…'
    await streamAssistantChat(id, content, handleEvent, abortController.signal, payload)
  } catch (e) {
    handleEvent('error', { message: e?.message || '请求失败' })
  } finally {
    finishStreaming()
  }
}

function clearPendingPrompt() {
  if (!pendingPrompt.value) return
  if (input.value.trim() === pendingPrompt.value.trim()) {
    input.value = ''
  }
  pendingPrompt.value = ''
}

function restorePendingPrompt() {
  if (!input.value && pendingPrompt.value) {
    input.value = pendingPrompt.value
  }
  pendingPrompt.value = ''
}

function handleEvent(event, payload) {
  const placeholder = messages.value[streamingPlaceholderIndex]
  if (!placeholder) return
  if (event !== 'error') {
    clearPendingPrompt()
  }
  if (event === 'status') {
    placeholder.status = typeof payload === 'string' ? payload : placeholder.status
  } else if (event === 'delta') {
    placeholder.content += typeof payload === 'string' ? payload : ''
    scrollBottom()
  } else if (event === 'geo') {
    // 工具产出的地点/路线/我的位置：实时上图 + 挂到本条消息的 chips
    const events = payload?.events || []
    if (events.length) {
      applyGeoEvents(events)
      placeholder.geoEvents = (placeholder.geoEvents || []).concat(events)
      placeholder.geoPlaces = collectPlacesOf(placeholder.geoEvents)
    }
  } else if (event === 'plan') {
    placeholder.status = ''
    plan.value = payload
    planExpanded.value = true
    saved.value = false
    // 新草稿版本未知（plan 事件不含版本号）——保存/编辑前由 detail / revise 接口取回
    currentPlanVersion.value = 0
    scrollBottom()
  } else if (event === 'done') {
    placeholder.status = ''
    placeholder.streaming = false
  } else if (event === 'error') {
    placeholder.content = placeholder.content || ''
    placeholder.status = ''
    placeholder.streaming = false
    restorePendingPrompt()
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
  // 中止/无事件结束时也把未发送的输入还给用户
  restorePendingPrompt()
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
    const savedActivity = await assistantApi.savePlan(chatId, currentPlanVersion.value
      ? { expectedPlanVersion: currentPlanVersion.value }
      : undefined)
    saved.value = true
    ElMessage.success(plan.value?.lineageActivityId ? '已更新活动，正在打开详情' : '已保存为活动，正在打开详情')
    // 带上 activityId + invite=1：日程页定位活动并直接展开邀请弹窗
    router.push({
      path: '/activities',
      query: { activityId: savedActivity.activityId, invite: 1 }
    })
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
  if (!followLatest.value) return
  nextTick(() => {
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function onChatScroll() {
  const el = listRef.value
  if (el) followLatest.value = el.scrollHeight - el.clientHeight - el.scrollTop < 80
}

function togglePlan() {
  planExpanded.value = !planExpanded.value
  if (planExpanded.value) {
    nextTick(() => listRef.value?.querySelector('.plan-card')?.scrollIntoView({ block: 'nearest', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' }))
  }
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
  /* 全局导航壳的主区已定高，这里撑满壳内可视区 */
  height: 100%;
  background: #f5f7fa;
}

.chat-col {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  border-right: 1px solid #e8eaee;
  background: #fff;
}

.chat-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid #f0f0f2;
  flex-shrink: 0;
}
.chat-head__title {
  white-space: nowrap;
  margin-right: auto;
  font-weight: 600;
  color: var(--brand-blue, #0052d9);
}
.chat-head__ref {
  width: 100%;
}
.input-note, .transport-row, .input-row { flex-shrink: 0; }

.chat-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.msg-list {
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
.plan-card {
  margin: 8px 14px;
  padding: 12px 14px;
  border-radius: 16px;
  border: 1px solid #eef1f6;
  background: linear-gradient(180deg, #f7faff, #fff);
  box-shadow: 0 2px 10px rgba(0, 82, 217, 0.06);
}
.plan-card__header { display: flex; gap: 8px; align-items: flex-start; }
.plan-card__header h3 { flex: 1; min-width: 0; flex-wrap: wrap; }
.plan-card__toggle { flex-shrink: 0; padding: 3px 0 3px 8px; border: 0; background: transparent; color: #0052d9; cursor: pointer; }
.plan-card__summary { color: #69758a; font-size: 12px; margin: 4px 0 0; line-height: 1.6; }
.draft-toggle { display: flex; justify-content: space-between; gap: 8px; flex-shrink: 0; margin: 6px 14px 2px; padding: 8px 10px; border: 1px solid #dbe6fa; border-radius: 8px; color: #0052d9; background: #f7faff; cursor: pointer; font-size: 12px; }
.draft-toggle:focus-visible, .plan-card__toggle:focus-visible { outline: 2px solid #0052d9; outline-offset: 2px; }
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
.plan-stop__nav {
  text-decoration: none;
  margin-left: auto;
  border: 1px solid rgba(0, 82, 217, 0.25);
  background: #fff;
  color: var(--brand-blue, #0052d9);
  border-radius: 999px;
  font-size: 11px;
  line-height: 18px;
  padding: 1px 8px;
  cursor: pointer;
}
.plan-stop__nav:hover {
  background: rgba(0, 82, 217, 0.08);
}
.plan-people {
  margin: 8px 0 2px;
  border-top: 1px dashed #e6ebf5;
  padding-top: 8px;
}
.plan-people__toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  background: none;
  border: none;
  padding: 2px 0;
  cursor: pointer;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.65);
}
.plan-people__conflict {
  color: #d4380d;
  font-weight: 600;
}
.plan-people__chevron {
  margin-left: auto;
  color: var(--brand-blue, #0052d9);
}
.plan-people__body {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.plan-members {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.plan-member {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(0, 82, 217, 0.08);
  color: #1d39c4;
}
.plan-member em {
  font-style: normal;
  opacity: 0.65;
  margin-left: 4px;
  font-size: 10px;
}
.plan-member.is-unknown {
  background: rgba(0, 0, 0, 0.05);
  color: rgba(0, 0, 0, 0.55);
}
.plan-conflict {
  background: #fff7e6;
  border: 1px solid #ffe0a3;
  border-radius: 8px;
  padding: 6px 8px;
}
.plan-conflict__line {
  margin: 0;
  font-size: 12px;
  color: #ad4e00;
}
.plan-conflict__suggestion {
  margin: 2px 0 0;
  font-size: 11px;
  color: #874d00;
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
.locate-toggle {
  flex-shrink: 0;
}
.locate-col {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
  align-self: flex-end;
}
/* 三键等宽等高：📍位置 / 纠偏(点地图…) / 清除 */
.locate-col .el-button {
  width: 72px;
  margin-left: 0;
  padding-left: 0;
  padding-right: 0;
  justify-content: center;
}
.quick-replies {
  display: flex;
  gap: 8px;
  padding: 4px 16px 8px;
}
/* 行程站序号角标：主色圆底白字 */
.geo-chip__seq {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 15px;
  height: 15px;
  margin-right: 2px;
  padding: 0 3px;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  color: #fff;
  background: var(--el-color-primary);
  border-radius: 999px;
}
.quick-reply {
  padding: 5px 14px;
  font-size: 12px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.15s;
}
.quick-reply:hover {
  background: var(--el-color-primary-light-8);
}
.plan-dialog .plan-dialog__name {
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.geo-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.geo-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 20px;
  cursor: pointer;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  border: 1px solid transparent;
  transition: border-color 0.15s;
}
.geo-chip:hover {
  border-color: currentColor;
}
.geo-chip__badge {
  opacity: 0.75;
  font-size: 11px;
}
.geo-chip--group_map {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.geo-chip--personal_map {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.geo-chip--campus_lib {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.geo-chip--amap_poi {
  background: #f4f4f5;
  color: #909399;
}
.geo-chip--user_location {
  background: rgba(7, 193, 96, 0.12);
  color: #07a552;
}
.geo-pop {
  position: absolute;
  z-index: 30;
  box-sizing: border-box;
  width: min(280px, calc(100vw - 24px));
  padding: 10px 12px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(31, 56, 88, 0.18);
  font-size: 13px;
  text-align: left;
  white-space: normal;
}
.geo-pop__head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.geo-pop__name {
  flex: 1;
  min-width: 0;
  font-weight: 600;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.geo-pop__src {
  flex-shrink: 0;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  color: #fff;
  background: var(--el-color-primary);
}
.geo-pop__close {
  flex-shrink: 0;
  border: none;
  background: none;
  padding: 2px 4px;
  font-size: 18px;
  line-height: 1;
  color: rgba(0, 0, 0, 0.45);
  cursor: pointer;
}
.geo-pop__line {
  margin: 4px 0 0;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.65);
}
.geo-pop__note {
  margin: 6px 0 0;
  padding: 4px 8px;
  border-radius: 6px;
  background: #f7f8fa;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
}
.geo-pop__actions {
  display: flex;
  gap: 6px;
  margin-top: 8px;
}
.input-note {
  padding: 6px 14px 0;
  font-size: 11px;
  color: rgba(0, 0, 0, 0.45);
}

.map-col {
  position: relative;
  height: 100%;
  min-height: 0;
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
.plan-edit__transport {
  width: 100%;
}
.transport-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 14px 0;
}
.transport-row__label {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
  flex: none;
}
.transport-row__select {
  width: 132px;
}
.transport-row .hint {
  margin: 0;
}

@media (max-width: 768px) {
  .assistant-page {
    grid-template-columns: 1fr;
    height: auto;
    min-height: 100%;
  }
  .map-col {
    order: 2;
    height: auto;
    min-height: 46vh;
  }
  .chat-col {
    height: 54vh;
    border-right: none;
  }
  .transport-row {
    flex-wrap: wrap;
  }
  .transport-row__select {
    width: 100%;
  }
}
</style>

<style>
/* 会话下拉（teleport 到 body，需全局样式） */
.session-menu {
  max-width: 320px;
}
.session-menu .session-menu__title {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 1.3;
}
.session-menu .session-menu__title.is-empty {
  color: #909399;
}
.session-menu .session-menu__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
}
.session-menu .session-menu__time {
  color: #909399;
  font-size: 11px;
}
.session-menu .session-menu__item--active {
  background: var(--el-fill-color-light);
}
</style>
