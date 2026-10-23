<template>
  <div class="activity-card" :class="{ 'is-urgent': urgent, 'is-compact': compact }">
    <div class="activity-card__head">
      <strong class="activity-card__title">{{ activity.title }}</strong>
      <el-tag size="small" :type="phaseTagType(activity.phase)" effect="plain">{{ phaseText(activity.phase) }}</el-tag>
    </div>
    <p class="hint">
      {{ fmtRange(activity.startTime, activity.endTime) }} · {{ activity.joinedCount }}/{{ activity.maxMembers }} 人
      <template v-if="categoryText(activity.category)"> · {{ categoryText(activity.category) }}</template>
    </p>

    <div v-if="!isCancelled" class="activity-card__actions">
      <template v-if="activity.joined">
        <el-tag v-if="activity.checkedIn" size="small" type="success">已签到</el-tag>
        <template v-else-if="checkinOpen">
          <el-input v-model="code" size="small" class="checkin-input" placeholder="签到码" @keyup.enter="doCheckin" />
          <el-button size="small" type="primary" @click="doCheckin">签到</el-button>
        </template>
        <span v-else class="hint">{{ checkinHint }}</span>
        <el-button v-if="activity.phase !== 'ENDED'" size="small" @click="leave">取消报名</el-button>
      </template>
      <el-button v-else-if="activity.phase === 'OPEN'" size="small" type="primary" @click="join">报名</el-button>
      <el-button size="small" @click="toggleMembers">{{ compact ? '成员' : `成员（${activity.joinedCount}）` }}</el-button>
      <template v-if="isCreator">
        <el-button size="small" @click="emit('edit', activity)">编辑</el-button>
        <el-button v-if="activity.phase === 'OPEN'" size="small" type="danger" plain @click="cancel">取消活动</el-button>
      </template>
    </div>
    <span v-if="isCreator && activity.checkinCode && !isCancelled" class="checkin-code">
      现场公布签到码：{{ activity.checkinCode }}
    </span>

    <div v-if="membersOpen" class="activity-card__members">
      <p v-if="!members.length" class="hint">暂无成员</p>
      <div v-for="m in members" :key="m.userId" class="activity-card__member">
        <span>{{ m.nickname || m.username }}（{{ m.checkedIn ? '已签 ' + fmtClock(m.checkedInAt) : '未签' }}）</span>
      </div>
      <div v-if="isCreator && !compact && activity.phase !== 'ENDED' && !isCancelled" class="invite-row">
        <el-select v-model="inviteChoice" size="small" filterable placeholder="邀请好友参加" class="invite-select" popper-class="ui-content-popper">
          <el-option v-for="f in friends" :key="f.userId" :value="f.userId" :label="f.nickname || f.username" />
        </el-select>
        <el-button size="small" type="primary" plain @click="invite">发邀请</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 共享活动操作卡：/activities（活动日程页）与地图工作台地点面板共用，
 * 报名/取消报名/输码签到/成员与签到记录/创建者（编辑·取消·公布签到码·邀请）都在卡内完成。
 * 操作成功 emit updated(最新活动对象) / cancelled(被取消的活动)，由父级回写列表；编辑 emit edit 交父级开表单弹窗。
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { activityApi, friendApi } from '../api'
import { myUserId } from '../composables/useChatSocket'
import { categoryText, fmtClock, fmtRange, phaseTagType, phaseText, toDate } from '../utils/format'

/** 与后端一致：开始前 15 分钟起、checkinOpenUntil（默认开始后 15 分钟）止 */
const CHECKIN_WINDOW_MS = 15 * 60_000

const props = defineProps({
  activity: { type: Object, required: true },
  /** 手机浮窗窄版：成员按钮不带人数、不显示邀请行 */
  compact: { type: Boolean, default: false },
  urgent: { type: Boolean, default: false }
})

const emit = defineEmits(['updated', 'cancelled', 'edit'])

const myId = myUserId()
const code = ref('')
const membersOpen = ref(false)
const members = ref([])
const friends = ref([])
const inviteChoice = ref(null)

const isCreator = computed(() => myId != null && props.activity.creatorId === myId)
const isCancelled = computed(() => props.activity.phase === 'CANCELLED')
const checkinOpen = computed(() => {
  const start = toDate(props.activity.startTime)
  const openUntil = toDate(props.activity.checkinOpenUntil)
  if (!start || !openUntil) return false
  const now = Date.now()
  return now >= start.getTime() - CHECKIN_WINDOW_MS && now <= openUntil.getTime()
})
const checkinHint = computed(() => {
  if (props.activity.phase === 'ENDED') return '活动已结束，未能签到'
  const start = toDate(props.activity.startTime)
  if (start && Date.now() < start.getTime() - CHECKIN_WINDOW_MS) {
    return '开始前 15 分钟至开始后 15 分钟内可签到'
  }
  return '签到窗口已关闭'
})

async function join() {
  try {
    emit('updated', await activityApi.join(props.activity.id))
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function leave() {
  try {
    emit('updated', await activityApi.leave(props.activity.id))
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doCheckin() {
  const value = code.value.trim()
  if (!value) {
    ElMessage.warning('请输入签到码')
    return
  }
  try {
    emit('updated', await activityApi.checkin(props.activity.id, value))
    code.value = ''
    ElMessage.success('签到成功')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function toggleMembers() {
  if (membersOpen.value) {
    membersOpen.value = false
    return
  }
  try {
    members.value = await activityApi.members(props.activity.id)
    membersOpen.value = true
    if (isCreator.value && !friends.value.length) {
      friends.value = await friendApi.list()
    }
    inviteChoice.value = null
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function invite() {
  if (!inviteChoice.value) {
    ElMessage.warning('先选择要邀请的好友')
    return
  }
  try {
    await activityApi.invite(props.activity.id, inviteChoice.value)
    ElMessage.success('已发出召集邀请，对方接受后加入成员')
    inviteChoice.value = null
    members.value = await activityApi.members(props.activity.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function cancel() {
  try {
    await ElMessageBox.confirm('取消后所有成员都会收到通知，操作不可撤销', '取消活动')
  } catch {
    return
  }
  try {
    await activityApi.cancel(props.activity.id)
    membersOpen.value = false
    ElMessage.success('活动已取消，在线成员会收到提醒')
    emit('cancelled', props.activity)
  } catch (e) {
    ElMessage.error(e.message)
  }
}
</script>

<style scoped>
.activity-card {
  border: 0;
  border-bottom: 1px solid var(--ui-line);
  border-radius: 0;
  padding: 12px 2px;
  margin-bottom: 0;
  background: transparent;
}
.activity-card.is-urgent {
  border-left: 2px solid var(--ui-warning);
  padding-left: 10px;
}
.activity-card.is-compact {
  padding: 10px 0;
}
.activity-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.activity-card__title {
  min-width: 0;
  overflow-wrap: anywhere;
  white-space: normal;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ui-ink);
}
.activity-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 8px;
}
.activity-card__actions .hint {
  flex: 1 1 100%;
  order: -1;
  margin: 0 0 2px;
}
.activity-card__actions .el-button + .el-button {
  margin-left: 0;
}
.checkin-input {
  width: 110px;
}
.checkin-code {
  display: block;
  margin-top: 6px;
  font-variant-numeric: tabular-nums;
  color: var(--ui-accent);
  font-weight: 600;
}
.activity-card__members {
  margin-top: 8px;
  border-top: 1px solid var(--ui-line);
  padding-top: 8px;
}
.activity-card__member {
  font-size: 12px;
  color: var(--ui-ink-2);
  line-height: 1.8;
}
.invite-row {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.invite-select {
  flex: 1;
}
.hint {
  margin: 3px 0;
  font-size: 12px;
  color: var(--ui-ink-3);
}
@media (max-width: 768px) {
  .activity-card__head {
    align-items: flex-start;
  }
  .activity-card__actions {
    gap: 6px;
  }
  .activity-card__actions .el-button {
    flex: 1 1 auto;
  }
  .checkin-input {
    width: 100%;
  }
  .invite-row {
    flex-wrap: wrap;
  }
  .invite-select {
    flex: 1 1 100%;
    width: 100%;
  }
}
</style>
