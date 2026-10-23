<template>
  <div class="page content-page">
    <PageHeader
      title="日程"
      description="会开在地点上，比赛来这儿召集；个人安排只参与忙闲计算。"
    >
      <template #actions>
        <button type="button" class="ui-btn ui-btn--secondary" @click="openScheduleDialog(null, calendarDate)">
          新建个人安排
        </button>
        <button type="button" class="ui-btn ui-btn--primary" @click="createOpen = true">
          <AppIcon name="plus" :size="14" />
          新建活动
        </button>
      </template>
    </PageHeader>

    <el-tabs v-model="tab">
      <el-tab-pane label="日历" name="calendar">
        <!-- 手机端收起 7 列月历，改为日期选择 + 当日列表 -->
        <div class="mobile-day-picker">
          <el-date-picker v-model="calendarDate" type="date" :clearable="false" placeholder="选择日期" />
        </div>
        <div v-if="loadingMine || loadingSchedules" class="activity-state">
          <span class="ui-spinner" />
          正在加载日程…
        </div>
        <div v-if="mineError || schedulesError" class="activity-error">
          <AppIcon name="alert-circle" :size="15" />
          <span>{{ mineError || schedulesError }}</span>
          <button type="button" class="ui-text-action" @click="retryCalendar">重试</button>
        </div>
        <el-calendar v-model="calendarDate" class="activity-calendar">
          <template #date-cell="{ data }">
            <div
              class="cal-cell"
              :class="{ 'cal-cell--selected': data.isSelected, 'cal-cell--today': data.day === todayKey }"
            >
              <span class="cal-cell__date">{{ Number(data.day.split('-')[2]) }}</span>
              <div class="cal-cell__days">
                <span
                  class="cal-entry"
                  v-for="entry in visibleEntries(data.day)"
                  :key="entry.key"
                  :class="[
                    entry.kind === 'activity'
                      ? 'cal-entry--' + (entry.phase || '').toLowerCase()
                      : 'cal-entry--schedule',
                    { 'cal-entry--highlight': entry.kind === 'activity' && entry.id === highlightedId }
                  ]"
                  :title="entry.title"
                  @click.stop="openCalendarEntry(entry)"
                >
                  {{ fmtClock(entry.startTime) }} {{ entry.title }}
                </span>
                <span v-if="hiddenCount(data.day)" class="cal-entry cal-entry--more" @click.stop="openDayList(data.day)">
                  +{{ hiddenCount(data.day) }} 项
                </span>
              </div>
            </div>
          </template>
        </el-calendar>
        <div class="section">
          <h3>{{ selectedDayLabel }} 的安排（个人日程与群活动）</h3>
          <EmptyState
            v-if="!loadingMine && !loadingSchedules && !selectedDayEntries.length"
            icon="calendar"
            title="这一天还没有安排"
            description="可以新建活动，或添加一条个人安排。"
            compact
          />
          <div
            v-for="entry in selectedDayEntries"
            :key="entry.key"
            class="row"
            :class="{ 'row--urgent': entry.kind === 'activity' && entry.urgent }"
          >
            <template v-if="entry.kind === 'activity'">
              <div class="row__main">
                <strong>{{ entry.title }}</strong>
                <el-tag size="small" :type="phaseTagType(entry.phase)" effect="plain">{{ phaseText(entry.phase) }}</el-tag>
                <el-tag v-if="entry.raw.category" size="small" effect="plain">{{ categoryText(entry.raw.category) }}</el-tag>
                <span class="hint">{{ fmtRange(entry.startTime, entry.endTime) }}</span>
                <span class="hint">📍 {{ entry.raw.placeName || '地点' }}</span>
                <span class="hint">{{ entry.raw.joinedCount }}/{{ entry.raw.maxMembers }} 人</span>
              </div>
              <div class="row__actions">
                <el-button size="small" type="primary" plain @click="openActivityDetail(entry.raw)">详情 / 去现场</el-button>
              </div>
            </template>
            <template v-else>
              <div class="row__main">
                <strong>{{ entry.title }}</strong>
                <el-tag size="small" type="success" effect="plain">个人安排</el-tag>
                <span class="hint">{{ fmtRange(entry.startTime, entry.endTime) }}</span>
                <span v-if="entry.raw.note" class="hint">{{ entry.raw.note }}</span>
              </div>
              <div class="row__actions">
                <el-button size="small" @click="openScheduleDialog(entry.raw)">编辑</el-button>
                <el-button size="small" type="danger" plain @click="deleteSchedule(entry.raw)">删除</el-button>
              </div>
            </template>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="我的日程" name="schedule">
        <div v-if="loadingMine || loadingSchedules" class="activity-state">
          <span class="ui-spinner" />
          正在加载日程…
        </div>
        <div v-if="mineError || schedulesError" class="activity-error">
          <AppIcon name="alert-circle" :size="15" />
          <span>{{ mineError || schedulesError }}</span>
          <button type="button" class="ui-text-action" @click="retryCalendar">重试</button>
        </div>
        <div class="section share-section">
          <div class="share-head">
            <h3>忙闲共享（供群规划使用）</h3>
            <el-tag size="small" effect="plain">{{ grantedCount }} 个群已授权</el-tag>
          </div>
          <p class="hint share-note">
            开启后，本群成员和 @AI 规划只能看到你的忙碌时间段，不会看到日程标题、备注或具体内容。
            默认关闭；未授权的成员在群规划中会标记为未知，方案会被标注为暂定。
          </p>
          <div v-if="shareError" class="activity-error">
            <AppIcon name="alert-circle" :size="15" />
            <span>{{ shareError }}</span>
            <button type="button" class="ui-text-action" @click="loadShareOptions">重试</button>
          </div>
          <div v-loading="shareLoading" class="share-list">
            <div v-for="option in shareOptions" :key="option.groupId" class="share-row">
              <div class="share-row__main">
                <strong>{{ option.groupName || option.mapName }}</strong>
                <span class="hint">
                  {{ option.mapName }} · {{ roleText(option.myRole) }}
                  · {{ option.visibility === 'PUBLIC' ? '公开地图' : '成员可见' }}
                </span>
                <span v-if="option.granted && option.grantedAt" class="hint">
                  授权于 {{ fmt(option.grantedAt) }}
                </span>
              </div>
              <div class="share-row__action">
                <el-switch
                  :model-value="option.granted"
                  :loading="!!shareSaving[option.groupId]"
                  @change="(value) => toggleShare(option, value)"
                />
                <span class="hint">{{ option.granted ? '已授权' : '未授权' }}</span>
              </div>
            </div>
            <EmptyState
              v-if="!shareLoading && !shareOptions.length"
              icon="users"
              title="还没有可共享的群"
              description="先创建或加入一张共享地图，才能设置群忙闲共享。"
              compact
            />
          </div>
        </div>
        <div class="section">
          <div class="share-head">
            <h3>个人安排</h3>
            <el-button size="small" type="primary" plain @click="openScheduleDialog(null, calendarDate)">
              ＋ 新建个人安排
            </el-button>
          </div>
          <p class="hint share-note">
            课程、会议、自习等私人安排。它们只用于自己的日历与群忙闲计算，群成员只能看到时段，看不到标题和备注。
          </p>
          <EmptyState
            v-if="!loadingSchedules && !schedules.length"
            icon="calendar"
            title="还没有个人安排"
            description="添加后会自动进入日历，并参与群规划的共同空闲计算。"
            compact
          />
          <div v-for="s in schedules" :key="'schedule-row-' + s.id" class="row">
            <div class="row__main">
              <strong>{{ s.title }}</strong>
              <el-tag size="small" type="success" effect="plain">个人</el-tag>
              <span class="hint">{{ fmtRange(s.startTime, s.endTime) }}</span>
              <span v-if="s.note" class="hint">{{ s.note }}</span>
            </div>
            <div class="row__actions">
              <el-button size="small" @click="openScheduleDialog(s)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="deleteSchedule(s)">删除</el-button>
            </div>
          </div>
        </div>
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
        <div v-if="!loadingMine && !mine.length" class="empty-cta">
          <p class="hint">还没有安排或报名任何活动</p>
          <div class="empty-cta__actions">
            <el-button type="primary" @click="$router.push('/')">去地图逛逛</el-button>
            <el-button @click="createOpen = true">＋ 新建活动</el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane name="invites">
        <template #label>
          收到的邀请
          <span v-if="pendingInvites.length" class="ui-count activity-tab-count">{{ pendingInvites.length }}</span>
        </template>
        <div class="section">
          <div v-if="loadingInvites" class="activity-state">
            <span class="ui-spinner" />
            正在加载邀请…
          </div>
          <div v-if="invitesError" class="activity-error">
            <AppIcon name="alert-circle" :size="15" />
            <span>{{ invitesError }}</span>
            <button type="button" class="ui-text-action" @click="loadInvites">重试</button>
          </div>
          <EmptyState
            v-if="!loadingInvites && !invitesError && !pendingInvites.length"
            icon="calendar"
            title="暂时没有待响应的召集邀请"
            description="收到活动邀请后会在这里等待你的响应。"
            compact
          />
          <div v-for="i in pendingInvites" :key="i.id" class="row">
            <div class="row__main">
              <strong>{{ i.activityTitle }}</strong>
              <el-tag size="small" effect="plain">{{ categoryText(i.activityCategory) }}</el-tag>
              <span class="hint">{{ fmtRange(i.activityStartTime, i.activityEndTime) }}</span>
              <span class="hint">📍 {{ i.placeName || '地点' }}</span>
              <span class="hint">{{ i.inviterNickname }} 邀请你加入</span>
            </div>
            <div class="row__actions">
              <el-button size="small" type="primary" :disabled="isInvitePending(i.id)" @click="acceptInvite(i)">接受</el-button>
              <el-button size="small" :disabled="isInvitePending(i.id)" @click="declineInvite(i)">拒绝</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="我创建的" name="created">
        <div class="section">
          <div v-if="loadingMine" class="activity-state">
            <span class="ui-spinner" />
            正在加载创建的活动…
          </div>
          <div v-if="mineError" class="activity-error">
            <AppIcon name="alert-circle" :size="15" />
            <span>{{ mineError }}</span>
            <button type="button" class="ui-text-action" @click="loadMine">重试</button>
          </div>
          <div v-if="!loadingMine && !mineError && !createdActivities.length" class="empty-cta">
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

  <el-dialog v-model="detailDialog" :title="detailActivity?.title || '活动详情'" width="min(440px, 94vw)">
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
      <el-button v-if="detailActivity" @click="navigateActivity(detailActivity)">🧭 高德导航</el-button>
      <el-button
        v-if="detailActivity && isCreatorOf(detailActivity) && detailActivity.phase !== 'CANCELLED'"
        @click="openInvite(detailActivity)"
      >邀请好友</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="mapPickDialog" title="该地点出现在多个地图" width="min(360px, 92vw)">
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
  <!-- 个人日程：课程/会议/自习，只进入自己的日历与忙闲计算 -->
  <ScheduleFormDialog
    v-model="scheduleDialogOpen"
    :schedule="scheduleTarget"
    :default-date="scheduleDefaultDate"
    @saved="onScheduleSaved"
    @deleted="onScheduleSaved"
  />
  <InviteFriendsDialog
    v-model="inviteDialogOpen"
    :activity="inviteTarget"
    @invited="onInvited"
  />
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { activityApi, placeApi, scheduleApi } from '../api'
import { myUserId, on } from '../composables/useChatSocket'
import ActivityEntryCard from '../components/ActivityEntryCard.vue'
import ActivityFormDialog from '../components/ActivityFormDialog.vue'
import ScheduleFormDialog from '../components/ScheduleFormDialog.vue'
import InviteFriendsDialog from '../components/InviteFriendsDialog.vue'
import EmptyState from '../components/ui/EmptyState.vue'
import PageHeader from '../components/ui/PageHeader.vue'
import AppIcon from '../components/ui/AppIcon.vue'
import { calendarDayKey, groupEntriesByDay, mergeCalendarEntries } from '../utils/calendarEntries'
import { categoryText, fmt, fmtClock, fmtRange, pad2, phaseTagType, phaseText, toDate, transportText } from '../utils/format'
import { openAmapNavigation } from '../utils/amapNavigation'

const router = useRouter()
const route = useRoute()
const tab = ref('calendar')
const highlightedId = ref(null)
let highlightTimer = null
const mine = ref([])
const schedules = ref([])
const pendingInvites = ref([])
const shareOptions = ref([])
const shareLoading = ref(false)
const shareSaving = ref({})
const loadingMine = ref(false)
const mineError = ref('')
const loadingSchedules = ref(false)
const schedulesError = ref('')
const loadingInvites = ref(false)
const invitesError = ref('')
const shareError = ref('')
const pendingInviteActions = ref({})
const todayKey = calendarDayKey(new Date())
let mineSeq = 0
let scheduleSeq = 0
let inviteSeq = 0
let shareSeq = 0
let removers = []
const myId = myUserId()

function isInvitePending(id) {
  return pendingInviteActions.value[id] === true
}

function isCreatorOf(a) {
  return myId != null && a.creatorId === myId
}

const grantedCount = computed(() => shareOptions.value.filter((item) => item.granted).length)

async function loadShareOptions() {
  const seq = ++shareSeq
  shareLoading.value = true
  shareError.value = ''
  try {
    const list = (await scheduleApi.shareOptions()) || []
    if (seq !== shareSeq) return
    shareOptions.value = list
  } catch (e) {
    if (seq !== shareSeq) return
    shareError.value = e.message || '加载忙闲共享设置失败'
  } finally {
    if (seq === shareSeq) shareLoading.value = false
  }
}

async function toggleShare(option, value) {
  if (!option?.groupId || shareSaving.value[option.groupId]) return
  shareSaving.value = { ...shareSaving.value, [option.groupId]: true }
  try {
    await scheduleApi.setShare({
      targetType: 'GROUP',
      targetId: option.groupId,
      grant: value
    })
    option.granted = value
    option.grantedAt = value ? new Date().toISOString() : null
    ElMessage.success(value
      ? `已授权「${option.groupName}」读取忙闲时段`
      : `已撤销「${option.groupName}」忙闲共享`)
  } catch (e) {
    ElMessage.error(e.message || '设置忙闲共享失败')
    await loadShareOptions()
  } finally {
    const next = { ...shareSaving.value }
    delete next[option.groupId]
    shareSaving.value = next
  }
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
/** 个人日程弹窗 */
const scheduleDialogOpen = ref(false)
const scheduleTarget = ref(null)
const scheduleDefaultDate = ref(null)
/** 保存/创建后直接邀请 */
const inviteDialogOpen = ref(false)
const inviteTarget = ref(null)

async function onCreated(created) {
  await loadMine()
  tab.value = 'created'
  if (created?.id) openInvite(created)
}

function openInvite(activity) {
  if (!activity?.id) return
  inviteTarget.value = activity
  inviteDialogOpen.value = true
}

async function onInvited() {
  await loadMine()
  if (detailActivity.value?.id) {
    detailActivity.value = mine.value.find((a) => a.id === detailActivity.value.id) || detailActivity.value
  }
}

function openEdit(a) {
  editTarget.value = a
  editDialogOpen.value = true
}

const calendarEntries = computed(() => mergeCalendarEntries(mine.value, schedules.value))
const entriesByDay = computed(() => groupEntriesByDay(calendarEntries.value))

const selectedDay = computed(() => {
  const d = calendarDate.value instanceof Date ? calendarDate.value : new Date(calendarDate.value)
  return Number.isNaN(d.getTime()) ? new Date() : d
})
const selectedDayEntries = computed(() => entriesByDay.value.get(calendarDayKey(selectedDay.value)) || [])
const selectedDayLabel = computed(
  () => `${selectedDay.value.getFullYear()}-${pad2(selectedDay.value.getMonth() + 1)}-${pad2(selectedDay.value.getDate())}`
)

function visibleEntries(dayStr) {
  const list = entriesByDay.value.get(dayStr) || []
  // 已取消/已结束的活动不抢占日历格的两个可见位；它们仍可通过“+N 项”查看
  const active = list.filter((entry) => entry.kind === 'schedule'
    || (entry.phase !== 'ENDED' && entry.phase !== 'CANCELLED'))
  const inactive = list.filter((entry) => !active.includes(entry))
  return [...active, ...inactive].slice(0, 2)
}

function hiddenCount(dayStr) {
  const list = entriesByDay.value.get(dayStr) || []
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

function openCalendarEntry(entry) {
  if (entry.kind === 'activity') {
    openActivityDetail(entry.raw)
  } else {
    openScheduleDialog(entry.raw)
  }
}

function openScheduleDialog(schedule = null, date = null) {
  scheduleTarget.value = schedule
  scheduleDefaultDate.value = date || calendarDate.value
  scheduleDialogOpen.value = true
}

async function onScheduleSaved() {
  await loadSchedules()
}

async function deleteSchedule(schedule) {
  if (!schedule) return
  try {
    await ElMessageBox.confirm(`删除「${schedule.title}」？`, '删除个人安排', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await scheduleApi.remove(schedule.id)
    ElMessage.success('已删除个人安排')
    await loadSchedules()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
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

/** 高德导航：多站活动取首个带坐标的站点；单地点活动先取地点详情坐标 */
async function navigateActivity(a) {
  if (!a) return
  let target = (a.stops || []).find((s) => s.longitude != null && s.latitude != null)
  if (!target && a.placeId) {
    try {
      const place = await placeApi.get(a.placeId)
      target = { name: place.name, longitude: place.longitude, latitude: place.latitude }
    } catch (e) {
      // 地点详情失败时走下方统一提示
    }
  }
  if (!target) {
    ElMessage.info('该活动没有可导航的坐标，请先在地图里打开地点')
    return
  }
  openAmapNavigation({
    to: {
      lng: Number(target.longitude),
      lat: Number(target.latitude),
      name: target.name || target.placeName || a.title
    }
  })
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
  const seq = ++mineSeq
  loadingMine.value = true
  mineError.value = ''
  try {
    const list = await activityApi.mine('all')
    if (seq !== mineSeq) return
    mine.value = Array.isArray(list) ? list : []
  } catch (e) {
    if (seq !== mineSeq) return
    mineError.value = e.message || '活动加载失败'
  } finally {
    if (seq === mineSeq) loadingMine.value = false
  }
}

async function loadSchedules() {
  const seq = ++scheduleSeq
  loadingSchedules.value = true
  schedulesError.value = ''
  try {
    const list = (await scheduleApi.list()) || []
    if (seq !== scheduleSeq) return
    schedules.value = list
  } catch (e) {
    if (seq !== scheduleSeq) return
    schedulesError.value = e.message || '加载个人安排失败'
  } finally {
    if (seq === scheduleSeq) loadingSchedules.value = false
  }
}

async function loadInvites() {
  const seq = ++inviteSeq
  loadingInvites.value = true
  invitesError.value = ''
  try {
    const list = (await activityApi.pendingInvites()) || []
    if (seq !== inviteSeq) return
    pendingInvites.value = list
  } catch (e) {
    if (seq !== inviteSeq) return
    invitesError.value = e.message || '活动邀请加载失败'
  } finally {
    if (seq === inviteSeq) loadingInvites.value = false
  }
}

function retryCalendar() {
  loadMine()
  loadSchedules()
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
  if (isInvitePending(i.id)) return
  pendingInviteActions.value = { ...pendingInviteActions.value, [i.id]: true }
  try {
    await activityApi.acceptInvite(i.id)
    ElMessage.success(`已加入「${i.activityTitle}」`)
    await Promise.all([loadInvites(), loadMine()])
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    const next = { ...pendingInviteActions.value }
    delete next[i.id]
    pendingInviteActions.value = next
  }
}

async function declineInvite(i) {
  if (isInvitePending(i.id)) return
  pendingInviteActions.value = { ...pendingInviteActions.value, [i.id]: true }
  try {
    await activityApi.declineInvite(i.id)
    ElMessage.info('已拒绝该邀请')
    await loadInvites()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    const next = { ...pendingInviteActions.value }
    delete next[i.id]
    pendingInviteActions.value = next
  }
}

/** AI 保存后跳转 /activities?activityId=：定位、打开详情并短暂高亮 */
async function focusRequestedActivity() {
  // 消息中心的活动邀请通知：/activities?tab=invites
  if (route.query.tab === 'invites') {
    tab.value = 'invites'
    await router.replace({ path: '/activities' })
    return
  }
  const raw = route.query.activityId
  if (!raw) return
  const shouldInvite = route.query.invite === '1'
  const id = Number(raw)
  const activity = Number.isFinite(id) ? mine.value.find((a) => a.id === id) : null
  if (!activity) {
    ElMessage.warning('已保存，但未在当前日程中找到该活动')
  } else {
    tab.value = 'calendar'
    const start = toDate(activity.startTime)
    if (start && !Number.isNaN(start.getTime())) {
      calendarDate.value = start
    }
    openActivityDetail(activity)
    if (shouldInvite && isCreatorOf(activity)) {
      openInvite(activity)
    }
    highlightedId.value = id
    clearTimeout(highlightTimer)
    highlightTimer = setTimeout(() => {
      highlightedId.value = null
    }, 2500)
  }
  // 清掉 query，刷新页面不再重复弹窗
  await router.replace({ path: '/activities' })
}

onMounted(async () => {
  await Promise.all([loadMine(), loadSchedules(), loadInvites(), loadShareOptions()])
  await focusRequestedActivity()
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
  clearTimeout(highlightTimer)
})
</script>

<style scoped>
.cal-entry--highlight {
  outline: 2px solid var(--ui-accent);
  outline-offset: 1px;
  background: var(--ui-accent-tint);
  border-radius: 4px;
}
.empty-cta__actions {
  display: flex;
  justify-content: center;
  gap: 10px;
}
.section {
  margin-top: 22px;
  margin-bottom: 12px;
}
.section h3 {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ui-ink);
}
.share-section {
  padding: 14px 16px;
  border-radius: var(--ui-radius-lg);
  border: 1px solid var(--ui-line);
  background: var(--ui-surface);
}
.share-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.share-head h3 {
  margin: 0;
}
.share-note {
  margin: 0 0 12px;
  line-height: 1.6;
}
.share-list {
  min-height: 40px;
}
.share-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 0;
  border-top: 1px solid var(--ui-line);
}
.share-row__main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.share-row__action {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 12px 2px;
  border: 0;
  border-bottom: 1px solid var(--ui-line);
  border-radius: 0;
  background: transparent;
  margin-bottom: 0;
}
.row--flat {
  background: var(--ui-surface-2);
  border: 0;
  border-radius: var(--ui-radius-md);
  padding: 10px 12px;
}
.row--urgent {
  border-left: 2px solid var(--ui-warning);
  padding-left: 10px;
}
.row__main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
  flex: 1;
}
.row__main strong {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ui-ink);
}
.row__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.hint {
  color: var(--ui-ink-3);
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
.mobile-day-picker {
  display: none;
}
.activity-state {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 18px 2px;
  font-size: 12.5px;
  color: var(--ui-ink-3);
}
.activity-error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 0;
  padding: 10px 12px;
  border-radius: var(--ui-radius-md);
  background: var(--ui-danger-tint);
  color: var(--ui-danger);
  font-size: 12.5px;
}
.activity-error .ui-text-action {
  margin-left: auto;
  color: var(--ui-danger);
}
.activity-tab-count {
  background: var(--ui-accent-tint);
  color: var(--ui-accent);
}
@media (max-width: 768px) {
  .activity-calendar {
    display: none;
  }
  .mobile-day-picker {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 10px;
  }
  .row {
    flex-wrap: wrap;
  }
  .row__actions {
    width: 100%;
    justify-content: flex-end;
    flex-wrap: wrap;
  }
  .share-row {
    align-items: flex-start;
  }
}

/* ===== 日历视图 ===== */
.activity-calendar {
  background: var(--ui-surface);
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius-lg);
  overflow: hidden;
}
.activity-calendar :deep(.el-calendar__header) {
  padding: 10px 14px;
  border-bottom: 1px solid var(--ui-line);
}
.activity-calendar :deep(.el-calendar__body) {
  padding: 0 10px 10px;
}
.activity-calendar :deep(.el-calendar-table td) {
  vertical-align: top;
  border-color: var(--ui-line);
}
.activity-calendar :deep(.el-calendar-day) {
  height: 82px;
  padding: 4px;
}
.cal-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 72px;
  padding: 2px 2px;
  border-radius: 6px;
}
.cal-cell--selected {
  background: var(--ui-accent-tint);
}
.cal-cell__date {
  font-size: 12px;
  color: var(--ui-ink-3);
}
.cal-cell--today .cal-cell__date {
  color: var(--ui-accent);
  font-weight: 700;
}
.cal-cell--selected .cal-cell__date {
  color: var(--ui-accent);
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
  background: var(--ui-accent-tint);
  color: var(--ui-accent);
}
.cal-entry--ongoing {
  background: var(--ui-success-tint);
  color: var(--ui-success);
}
.cal-entry--ended,
.cal-entry--cancelled {
  background: var(--ui-surface-2);
  color: var(--ui-ink-3);
  text-decoration: line-through;
}
.cal-entry--schedule {
  background: var(--ui-surface-2);
  color: var(--ui-ink-2);
}
.cal-entry--more {
  color: var(--ui-ink-3);
}
.detail-line {
  margin: 6px 0;
  color: var(--ui-ink-2);
  font-size: 13px;
}
.detail-stops {
  margin: 10px 0 4px;
}
.detail-stops h3 {
  font-size: 13px;
  color: var(--ui-ink-3);
  margin: 0 0 6px;
}
.stop-item {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  border-left: 1px solid var(--ui-line);
  margin-left: 6px;
  padding-left: 12px;
}
.stop-dot {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--ui-accent);
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
