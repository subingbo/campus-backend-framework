<template>
  <div class="page">
    <div class="hero">
      <div class="hero__text">
        <h1>活动日程</h1>
        <p>会开在地点上，比赛来这儿召集</p>
      </div>
      <el-button type="primary" @click="createOpen = true">＋ 新建活动</el-button>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="日历" name="calendar">
        <el-calendar v-model="calendarDate" class="activity-calendar">
          <template #date-cell="{ data }">
            <div class="cal-cell" :class="{ 'cal-cell--today': data.isSelected }">
              <span class="cal-cell__date">{{ Number(data.day.split('-')[2]) }}</span>
              <div class="cal-cell__days">
                <span class="cal-entry" v-for="a in visibleEntries(data.day)" :key="a.id" :class="'cal-entry--' + (a.phase || '').toLowerCase()" :title="a.title" @click.stop="openActivityDetail(a)">
                  {{ fmtClock(a.startTime) }} {{ a.title }}
                </span>
                <span v-if="hiddenCount(data.day)" class="cal-entry cal-entry--more" @click.stop="openDayList(data.day)">
                  +{{ hiddenCount(data.day) }} 项
                </span>
              </div>
            </div>
          </template>
        </el-calendar>
        <div class="section">
          <h3>{{ selectedDayLabel }} 的活动（点活动可看详情、去现场）</h3>
          <p v-if="!selectedDayEntries.length" class="hint empty">这一天没有安排活动</p>
          <div v-for="a in selectedDayEntries" :key="a.id" class="row" :class="{ 'row--urgent': a.urgent }">
            <div class="row__main">
              <strong>{{ a.title }}</strong>
              <el-tag size="small" :type="phaseTagType(a.phase)" effect="plain">{{ phaseText(a.phase) }}</el-tag>
              <el-tag v-if="a.category" size="small" effect="plain">{{ categoryText(a.category) }}</el-tag>
              <span class="hint">{{ fmtRange(a.startTime, a.endTime) }}</span>
              <span class="hint">📍 {{ a.placeName || '地点' }}</span>
              <span class="hint">{{ a.joinedCount }}/{{ a.maxMembers }} 人</span>
            </div>
            <div class="row__actions">
              <el-button size="small" type="primary" plain @click="openActivityDetail(a)">详情 / 去现场</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="我的日程" name="schedule">
        <template v-for="group in scheduleGroups" :key="group.label">
          <div class="section" v-if="group.items.length">
            <h3>{{ group.label }}</h3>
            <!-- 共享操作卡：报名 / 取消报名 / 输码签到 / 成员 / 创建者编辑·取消 都能在这页直接做 -->
            <ActivityEntryCard
              v-for="a in group.items"
              :key="a.id"
              :activity="a"
              :urgent="a.urgent"
              @updated="onActivityUpdated"
              @cancelled="onActivityCancelled"
              @edit="openEdit"
            />
          </div>
        </template>
        <div v-if="!mine.length" class="empty-cta">
          <p class="hint">还没有安排或报名任何活动</p>
          <div class="empty-cta__actions">
            <el-button type="primary" @click="$router.push('/')">去地图逛逛</el-button>
            <el-button @click="createOpen = true">＋ 新建活动</el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="`收到的邀请${pendingInvites.length ? '（' + pendingInvites.length + '）' : ''}`" name="invites">
        <div class="section">
          <p v-if="!pendingInvites.length" class="hint empty">暂时没有待响应的召集邀请</p>
          <div v-for="i in pendingInvites" :key="i.id" class="row">
            <div class="row__main">
              <strong>{{ i.activityTitle }}</strong>
              <el-tag size="small" effect="plain">{{ categoryText(i.activityCategory) }}</el-tag>
              <span class="hint">{{ fmtRange(i.activityStartTime, i.activityEndTime) }}</span>
              <span class="hint">📍 {{ i.placeName || '地点' }}</span>
              <span class="hint">{{ i.inviterNickname }} 邀请你加入</span>
            </div>
            <div class="row__actions">
              <el-button size="small" type="primary" @click="acceptInvite(i)">接受</el-button>
              <el-button size="small" @click="declineInvite(i)">拒绝</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="我创建的" name="created">
        <div class="section">
          <div v-if="!createdActivities.length" class="empty-cta">
            <p class="hint">还没有创建过活动；可以手动新建，或去地图上点一个地点安排活动</p>
            <el-button type="primary" @click="createOpen = true">＋ 新建活动</el-button>
          </div>
          <ActivityEntryCard
            v-for="a in createdActivities"
            :key="a.id"
            :activity="a"
            @updated="onActivityUpdated"
            @cancelled="onActivityCancelled"
            @edit="openEdit"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>

  <el-dialog v-model="detailDialog" :title="detailActivity?.title || '活动详情'" width="440px">
    <div v-if="detailActivity" class="detail-body">
      <div class="row row--flat">
        <div class="row__main">
          <el-tag size="small" :type="phaseTagType(detailActivity.phase)" effect="plain">{{ phaseText(detailActivity.phase) }}</el-tag>
          <el-tag v-if="detailActivity.category" size="small" effect="plain">{{ categoryText(detailActivity.category) }}</el-tag>
          <el-tag v-if="detailActivity.urgent" size="small" type="warning">即将开始</el-tag>
        </div>
      </div>
      <p class="detail-line">{{ fmtRange(detailActivity.startTime, detailActivity.endTime) }}</p>
      <p class="detail-line">📍 {{ detailActivity.placeName || '地点' }} · {{ detailActivity.joinedCount }}/{{ detailActivity.maxMembers }} 人</p>
      <p class="detail-line">发起人：{{ detailActivity.creatorNickname || '—' }}</p>
      <p v-if="detailActivity.description" class="detail-line">{{ detailActivity.description }}</p>
      <!-- 多站行程时间线：仅有 stops 的活动显示 -->
      <div v-if="detailActivity.stops && detailActivity.stops.length" class="detail-stops">
        <h3>行程时间线</h3>
        <div v-for="s in detailActivity.stops" :key="s.seq" class="stop-item">
          <span class="stop-dot">{{ s.seq }}</span>
          <div class="stop-main">
            <div class="stop-head">
              <strong>{{ s.name }}</strong>
              <span v-if="s.startTime" class="hint">
                {{ fmtClock(s.startTime) }}<template v-if="s.endTime">–{{ fmtClock(s.endTime) }}</template>
              </span>
            </div>
            <p class="hint">
              <template v-if="s.placeName">📍{{ s.placeName }} · </template>
              <template v-else-if="s.longitude">📍{{ s.longitude }}, {{ s.latitude }} · </template>
              <template v-if="s.transportMode">{{ transportText(s.transportMode) }}</template>
            </p>
            <p v-if="s.note" class="hint">{{ s.note }}</p>
          </div>
        </div>
      </div>
      <!-- 报名 / 签到 / 成员 / 创建者操作：与工作台同一张操作卡 -->
      <ActivityEntryCard
        :activity="detailActivity"
        :urgent="detailActivity.urgent"
        @updated="onActivityUpdated"
        @cancelled="onActivityCancelled"
        @edit="openEdit"
      />
    </div>
    <template #footer>
      <el-button v-if="detailActivity" type="primary" @click="goPlace(detailActivity)">📅 去现场</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="mapPickDialog" title="该地点出现在多个地图" width="360px">
    <el-radio-group v-model="mapPickChoice" class="map-pick">
      <el-radio v-for="m in placeMaps" :key="m.id" :value="m.id">
        {{ m.name }}（{{ roleText(m.myRole) }}）
      </el-radio>
    </el-radio-group>
    <template #footer>
      <el-button @click="mapPickDialog = false">取消</el-button>
      <el-button type="primary" :disabled="!mapPickChoice" @click="confirmGoPlace">前往</el-button>
    </template>
  </el-dialog>

  <!-- 创建者的编辑弹窗：与工作台共用同一表单组件 -->
  <ActivityFormDialog v-model="editDialogOpen" mode="edit" :activity="editTarget" @updated="onActivityUpdated" />
  <!-- 手动新建（不依赖 AI）：选已有地点或地图点选新建，可配多站行程 -->
  <ActivityFormDialog v-model="createOpen" mode="create" @created="onCreated" />
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { activityApi, placeApi } from '../api'
import { myUserId, on } from '../composables/useChatSocket'
import ActivityEntryCard from '../components/ActivityEntryCard.vue'
import ActivityFormDialog from '../components/ActivityFormDialog.vue'
import { categoryText, fmt, fmtClock, fmtRange, pad2, phaseTagType, phaseText, toDate, transportText } from '../utils/format'

const router = useRouter()
const tab = ref('calendar')
const mine = ref([])
const pendingInvites = ref([])
let removers = []
const myId = myUserId()

function isCreatorOf(a) {
  return myId != null && a.creatorId === myId
}

/** ===== 日历视图：格子按日聚合我的活动 ===== */
const calendarDate = ref(new Date())
const detailDialog = ref(false)
const detailActivity = ref(null)
const mapPickDialog = ref(false)
const mapPickChoice = ref(null)
const placeMaps = ref([])
let pendingPlaceId = null

/** 草稿外的人工编辑（创建者）走共享表单弹窗 */
const editDialogOpen = ref(false)
const editTarget = ref(null)
/** 手动新建活动弹窗 */
const createOpen = ref(false)

async function onCreated() {
  await loadMine()
  tab.value = 'created'
}

function openEdit(a) {
  editTarget.value = a
  editDialogOpen.value = true
}

const activitiesByDay = computed(() => {
  const map = new Map()
  for (const a of mine.value) {
    const d = toDate(a.startTime)
    if (!d || Number.isNaN(d.getTime())) continue
    const key = dayKey(d)
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(a)
  }
  for (const list of map.values()) {
    list.sort((x, y) => (toDate(x.startTime)?.getTime() ?? 0) - (toDate(y.startTime)?.getTime() ?? 0))
  }
  return map
})

function dayKey(date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

const selectedDay = computed(() => {
  const d = calendarDate.value instanceof Date ? calendarDate.value : new Date(calendarDate.value)
  return Number.isNaN(d.getTime()) ? new Date() : d
})
const selectedDayEntries = computed(() => activitiesByDay.value.get(dayKey(selectedDay.value)) || [])
const selectedDayLabel = computed(
  () => `${selectedDay.value.getFullYear()}-${pad2(selectedDay.value.getMonth() + 1)}-${pad2(selectedDay.value.getDate())}`
)

function visibleEntries(dayStr) {
  return (activitiesByDay.value.get(dayStr) || []).slice(0, 2)
}

function hiddenCount(dayStr) {
  const list = activitiesByDay.value.get(dayStr) || []
  return Math.max(0, list.length - 2)
}

function openDayList(dayStr) {
  const [y, m, d] = dayStr.split('-').map(Number)
  calendarDate.value = new Date(y, m - 1, d)
}

function openActivityDetail(a) {
  detailActivity.value = a
  detailDialog.value = true
}

async function goPlace(a) {
  try {
    const maps = await placeApi.mapsForPlace(a.placeId)
    if (!maps.length) {
      ElMessage.info('该地点所在的地图不在你可见范围内，可在地图列表里寻找')
      return
    }
    if (maps.length === 1) {
      jumpToPlace(maps[0].id, a.placeId)
      return
    }
    placeMaps.value = maps
    mapPickChoice.value = null
    pendingPlaceId = a.placeId
    mapPickDialog.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function confirmGoPlace() {
  if (!mapPickChoice.value) {
    ElMessage.warning('先选择一张地图')
    return
  }
  jumpToPlace(mapPickChoice.value, pendingPlaceId)
}

function jumpToPlace(mapId, placeId) {
  detailDialog.value = false
  mapPickDialog.value = false
  router.push(`/maps/${mapId}?place=${placeId}`)
}

function roleText(role) {
  return { OWNER: '我创建', EDITOR: '我可编辑', VIEWER: '我可查看' }[role] || role
}

function dayStart(d) {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

const createdActivities = computed(() => mine.value.filter((a) => isCreatorOf(a)))

const scheduleGroups = computed(() => {
  const now = new Date()
  const today = dayStart(now)
  const tomorrow = today + 86_400_000
  const in7 = today + 7 * 86_400_000
  const buckets = {
    today: { label: '今天', items: [] },
    tomorrow: { label: '明天', items: [] },
    week: { label: '未来 7 天', items: [] },
    later: { label: '更远', items: [] },
    done: { label: '已结束 / 已取消', items: [] }
  }
  for (const a of mine.value) {
    const start = toDate(a.startTime)?.getTime() ?? 0
    a.urgent = (a.phase === 'OPEN' || a.phase === 'ONGOING') && start - now.getTime() <= 3_600_000 && start > now.getTime() - 600_000
    if (a.phase === 'ENDED' || a.phase === 'CANCELLED') {
      buckets.done.items.push(a)
    } else if (start >= today && start < tomorrow) {
      buckets.today.items.push(a)
    } else if (start >= tomorrow && start < in7) {
      buckets.tomorrow.items.push(a)
    } else if (start >= in7 && start < today + 30 * 86_400_000) {
      buckets.week.items.push(a)
    } else if (start < today) {
      // 正在进行但开始于今天之前的长活动，归入今天分组头前
      buckets.today.items.push(a)
    } else {
      buckets.later.items.push(a)
    }
  }
  return [buckets.today, buckets.tomorrow, buckets.week, buckets.later, buckets.done]
})

async function loadMine() {
  try {
    mine.value = await activityApi.mine('all')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function loadInvites() {
  try {
    pendingInvites.value = await activityApi.pendingInvites()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 操作卡回写：按 id 替换列表项；详情弹窗开着时同步替换（保持 phase/urgent 等 computed 生效） */
function onActivityUpdated(a) {
  mine.value = mine.value.map((x) => (x.id === a.id ? a : x))
  if (detailActivity.value?.id === a.id) {
    detailActivity.value = a
  }
}

function onActivityCancelled() {
  // 取消会改变分组归属（已结束/已取消），直接重拉最稳
  loadMine()
}

async function acceptInvite(i) {
  try {
    await activityApi.acceptInvite(i.id)
    ElMessage.success(`已加入「${i.activityTitle}」`)
    await Promise.all([loadInvites(), loadMine()])
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function declineInvite(i) {
  try {
    await activityApi.declineInvite(i.id)
    ElMessage.info('已拒绝该邀请')
    await loadInvites()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  await Promise.all([loadMine(), loadInvites()])
  removers = [
    on('ACTIVITY_REMINDER', (frame) => {
      ElMessage.warning(frame.summary)
    }),
    on('ACTIVITY_INVITED', (frame) => {
      ElMessage.warning(frame.summary)
      loadInvites()
    }),
    on('ACTIVITY_CANCELLED', (frame) => {
      ElMessage.info(frame.summary)
      loadMine()
    }),
    on('ACTIVITY_MEMBER_JOINED', (frame) => {
      ElMessage.success(frame.summary)
      loadMine()
    })
  ]
})

onUnmounted(() => {
  for (const off of removers) off()
})
</script>

<style scoped>
.page {
  max-width: 760px;
  margin: 0 auto;
  padding: 0 32px 56px;
}
.hero {
  margin: 40px 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.hero__text {
  min-width: 0;
}
.hero h1 {
  margin: 0 0 6px;
  font-size: 28px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.9);
}
.hero p {
  margin: 0;
  color: rgba(0, 0, 0, 0.55);
}
.empty-cta__actions {
  display: flex;
  justify-content: center;
  gap: 10px;
}
.section {
  margin-top: 20px;
  margin-bottom: 12px;
}
.section h3 {
  margin: 0 0 12px;
  font-size: 15px;
  color: rgba(0, 0, 0, 0.9);
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(31, 56, 88, 0.06);
  margin-bottom: 8px;
}
.row--flat {
  box-shadow: none;
  border: 1px solid var(--el-border-color-lighter);
}
.row--urgent {
  border-left: 3px solid var(--el-color-warning);
}
.row__main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
}
.row__actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.hint {
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
}
.empty {
  text-align: center;
  padding: 16px 0;
}
.empty-cta {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 28px 0;
}
@media (max-width: 600px) {
  .page {
    padding: 0 16px 40px;
  }
  .hero {
    margin: 24px 0 8px;
  }
  .hero h1 {
    font-size: 24px;
  }
}

/* ===== 日历视图 ===== */
.activity-calendar {
  background: #fff;
  border-radius: 10px;
  padding-bottom: 4px;
}
.activity-calendar :deep(.el-calendar__body) {
  padding: 8px 16px 12px;
}
.activity-calendar :deep(.el-calendar-table td) {
  vertical-align: top;
}
.cal-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 72px;
  padding: 2px 4px;
}
.cal-cell__date {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.6);
}
.cal-cell--today .cal-cell__date {
  color: var(--el-color-primary);
  font-weight: 600;
}
.cal-cell__days {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cal-entry {
  font-size: 11px;
  line-height: 17px;
  padding: 0 5px;
  border-radius: 4px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cal-entry--open {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.cal-entry--ongoing {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.cal-entry--ended,
.cal-entry--cancelled {
  background: #f4f4f5;
  color: #909399;
  text-decoration: line-through;
}
.cal-entry--more {
  color: var(--el-text-color-secondary);
}
.detail-line {
  margin: 6px 0;
  color: rgba(0, 0, 0, 0.75);
  font-size: 13px;
}
.detail-stops {
  margin: 10px 0 4px;
}
.detail-stops h3 {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin: 0 0 6px;
}
.stop-item {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  border-left: 2px solid var(--el-color-primary-light-7);
  margin-left: 6px;
  padding-left: 12px;
}
.stop-dot {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
  margin: 1px 0 0 -19px;
}
.stop-main {
  min-width: 0;
  font-size: 13px;
}
.stop-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  flex-wrap: wrap;
}
.stop-main p {
  margin: 2px 0 0;
}
.map-pick {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
