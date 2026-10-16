<template>
  <div>
    <header class="topbar">
      <span class="topbar__brand" @click="$router.push('/')">Campus Map</span>
      <div class="topbar__nav">
        <el-button text @click="$router.push('/assistant')" type="primary">AI 规划活动</el-button>
        <el-button text @click="$router.push('/')">我的地图</el-button>
        <el-button text @click="$router.push('/friends')">好友</el-button>
        <el-button text type="danger" @click="logout">退出</el-button>
      </div>
    </header>

    <div class="page">
      <div class="hero">
        <h1>活动日程</h1>
        <p>会开在地点上，比赛来这儿召集</p>
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
              <div v-for="a in group.items" :key="a.id" class="row" :class="{ 'row--urgent': a.urgent }">
                <div class="row__main">
                  <strong>{{ a.title }}</strong>
                  <el-tag size="small" :type="phaseTagType(a.phase)" effect="plain">{{ phaseText(a.phase) }}</el-tag>
                  <el-tag v-if="a.category" size="small" effect="plain">{{ categoryText(a.category) }}</el-tag>
                  <span class="hint">{{ fmtRange(a.startTime, a.endTime) }}</span>
                  <span class="hint">📍 {{ a.placeName || '地点' }}</span>
                  <span class="hint">{{ a.joinedCount }}/{{ a.maxMembers }} 人</span>
                  <el-tag v-if="a.urgent" size="small" type="warning">即将开始</el-tag>
                  <span v-if="a.checkedIn" class="hint">✔ 已签到</span>
                </div>
                <div class="row__actions">
                  <template v-if="isCreatorOf(a)">
                    <el-button v-if="a.phase === 'OPEN'" size="small" type="primary" @click="openMembers(a)">成员/邀请</el-button>
                    <el-button size="small" type="danger" plain @click="cancelActivity(a)">取消活动</el-button>
                  </template>
                  <el-tag v-else-if="a.checkedIn" size="small" type="success">已签到</el-tag>
                </div>
              </div>
            </div>
          </template>
          <p v-if="!mine.length" class="hint empty">还没有安排或报名任何活动；去地图上点一个地点安排活动吧</p>
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
            <p v-if="!mine.length" class="hint empty">还没有创建过活动</p>
            <div v-for="a in createdActivities" :key="a.id" class="row">
              <div class="row__main">
                <strong>{{ a.title }}</strong>
                <el-tag size="small" :type="phaseTagType(a.phase)" effect="plain">{{ phaseText(a.phase) }}</el-tag>
                <span v-if="a.checkinCode" class="hint">签到码 {{ a.checkinCode }}</span>
                <span class="hint">{{ fmtRange(a.startTime, a.endTime) }} · {{ a.joinedCount }}/{{ a.maxMembers }} 人</span>
              </div>
              <div class="row__actions">
                <el-button size="small" @click="openMembers(a)">成员/签到记录</el-button>
                <el-button size="small" type="danger" text :disabled="a.phase !== 'OPEN'" @click="cancelActivity(a)">取消活动</el-button>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="membersDialog" :title="membersActivity?.title || '活动成员'" width="460px">
      <template v-if="membersActivity">
        <p v-if="membersActivity.checkinCode" class="checkin-code">
          现场公布签到码：{{ membersActivity.checkinCode }}（默认开始后 15 分钟内有效）
        </p>
        <div class="section">
          <h3>成员与签到记录</h3>
          <p v-if="!members.length" class="hint empty">还没有成员</p>
          <div v-for="m in members" :key="m.userId" class="row row--flat">
            <div class="row__main">
              <strong>{{ m.nickname || m.username }}</strong>
              <span class="hint">@{{ m.username }}</span>
              <span class="hint">报名 {{ fmt(m.joinedAt) }}</span>
            </div>
            <div class="row__actions">
              <el-tag size="small" :type="m.checkedIn ? 'success' : 'info'">
                {{ m.checkedIn ? `已签 ${fmtClock(m.checkedInAt)}` : '未签到' }}
              </el-tag>
            </div>
          </div>
        </div>
        <div class="section">
          <h3>邀请好友加入</h3>
          <div class="invite-row">
            <el-select v-model="inviteChoice" filterable placeholder="选择好友" class="invite-select">
              <el-option v-for="f in friends" :key="f.userId" :value="f.userId" :label="f.nickname || f.username" />
            </el-select>
            <el-button type="primary" @click="inviteSelected">发召集邀请</el-button>
          </div>
          <p class="hint">对方接受后自动加入活动成员；比赛类活动常用。</p>
        </div>
      </template>
    </el-dialog>

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
        <p v-if="isCreatorOf(detailActivity) && detailActivity.checkinCode" class="checkin-code">
          现场公布签到码：{{ detailActivity.checkinCode }}
        </p>
        <p v-if="detailActivity.checkedIn" class="detail-line">✔ 我已签到</p>
        <!-- 活动的参与记录：点开才拉成员列表（创建者或已报名成员可见） -->
        <div class="section">
          <h3>参与人员 / 签到情况</h3>
          <div class="invite-row">
            <el-button size="small" @click="loadDetailMembers(detailActivity)">查看成员与签到</el-button>
          </div>
          <template v-if="detailMembersShown">
            <p v-if="!activityMembers.length" class="hint empty">还没有成员</p>
            <div v-for="m in activityMembers" :key="m.userId" class="row row--flat">
              <div class="row__main">
                <strong>{{ m.nickname || m.username }}</strong>
                <span class="hint">@{{ m.username }}</span>
              </div>
              <div class="row__actions">
                <el-tag size="small" :type="m.checkedIn ? 'success' : 'info'">
                  {{ m.checkedIn ? `已签 ${fmtClock(m.checkedInAt)}` : '未签到' }}
                </el-tag>
              </div>
            </div>
          </template>
        </div>
      </div>
      <template #footer>
        <el-button v-if="detailActivity" @click="openMembers(detailActivity)">管理（成员/邀请）</el-button>
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
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { activityApi, friendApi, placeApi } from '../api'
import { connect, myUserId, on } from '../composables/useChatSocket'

const router = useRouter()
const tab = ref('calendar')
const mine = ref([])
const pendingInvites = ref([])
const membersDialog = ref(false)
const membersActivity = ref(null)
const members = ref([])
const friends = ref([])
const inviteChoice = ref(null)
let removers = []
const myId = myUserId()

const PHASE_TEXT = { OPEN: '报名中', ONGOING: '进行中', ENDED: '已结束', CANCELLED: '已取消' }
const PHASE_TAG = { OPEN: 'primary', ONGOING: 'success', ENDED: 'info', CANCELLED: 'danger' }
const CATEGORY_TEXT = { MEETING: '会议', COMPETITION: '比赛', OTHER: '其他' }

function phaseText(phase) {
  return PHASE_TEXT[phase] || phase
}

function phaseTagType(phase) {
  return PHASE_TAG[phase] || 'info'
}

function categoryText(category) {
  return CATEGORY_TEXT[category] || ''
}

const TRANSPORT_TEXT = { WALK: '🚶 步行前往', RIDE: '🚲 骑行前往', DRIVE: '🚗 驾车前往', TRANSIT: '🚌 公交前往' }

function transportText(mode) {
  return TRANSPORT_TEXT[mode] || ''
}

function isCreatorOf(a) {
  return myId != null && a.creatorId === myId
}

/** ===== 日历视图：格子按日聚合我的活动 ===== */
const calendarDate = ref(new Date())
const detailDialog = ref(false)
const detailActivity = ref(null)
const activityMembers = ref([])
const detailMembersShown = ref(false)
const mapPickDialog = ref(false)
const mapPickChoice = ref(null)
const placeMaps = ref([])
let pendingPlaceId = null

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
  activityMembers.value = []
  detailMembersShown.value = false
  detailDialog.value = true
}

async function loadDetailMembers(a) {
  try {
    activityMembers.value = await activityApi.members(a.id)
    detailMembersShown.value = true
  } catch (e) {
    ElMessage.error(e.message)
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

/** LocalDateTime 在响应里多为 [年,月,日,时,分,秒] 数组，兼容 ISO 字符串 */
function toDate(value) {
  if (!value) return null
  if (Array.isArray(value)) return new Date(value[0], value[1] - 1, value[2] ?? 0, value[3] ?? 0, value[4] ?? 0, value[5] ?? 0)
  return new Date(value)
}

function pad2(n) {
  return String(n).padStart(2, '0')
}

function fmt(value) {
  const d = toDate(value)
  if (!d || Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

function fmtRange(a, b) {
  return `${fmt(a)} ~ ${fmt(b)}`
}

function fmtClock(value) {
  const d = toDate(value)
  if (!d || Number.isNaN(d.getTime())) return ''
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
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

async function loadFriends() {
  try {
    friends.value = await friendApi.list()
  } catch (e) {
    // 没有好友不阻塞页面
  }
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

async function openMembers(a) {
  try {
    membersActivity.value = a
    members.value = await activityApi.members(a.id)
    membersDialog.value = true
    if (!friends.value.length) {
      await loadFriends()
    }
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function inviteSelected() {
  if (!inviteChoice.value) {
    ElMessage.warning('先选择好友')
    return
  }
  try {
    await activityApi.invite(membersActivity.value.id, inviteChoice.value)
    ElMessage.success('已发出召集邀请')
    inviteChoice.value = null
    members.value = await activityApi.members(membersActivity.value.id)
    await loadMine()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function cancelActivity(a) {
  await ElMessageBox.confirm(`取消后所有成员都会收到通知，确定取消「${a.title}」？`, '取消活动')
  try {
    await activityApi.cancel(a.id)
    ElMessage.success('活动已取消')
    await loadMine()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  connect()
  await Promise.all([loadMine(), loadInvites(), loadFriends()])
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

function logout() {
  localStorage.clear()
  router.push('/login')
}
</script>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 56px;
  padding: 0 32px;
  background: #fff;
  border-bottom: 1px solid rgba(31, 56, 88, 0.08);
}
.topbar__brand {
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--el-color-primary);
  cursor: pointer;
}
.topbar__nav {
  display: flex;
  align-items: center;
}
.topbar__nav .el-button + .el-button {
  margin-left: 8px;
}
.page {
  max-width: 760px;
  margin: 0 auto;
  padding: 0 32px 56px;
}
.hero {
  margin: 40px 0 12px;
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
.checkin-code {
  font-variant-numeric: tabular-nums;
  color: var(--el-color-primary);
  font-weight: 600;
}
.invite-row {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
.invite-select {
  flex: 1;
}
@media (max-width: 600px) {
  .topbar {
    padding: 0 16px;
  }
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
