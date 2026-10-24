<template>
  <el-dialog
    :model-value="modelValue"
    :title="`邀请好友加入「${activity?.title || '活动'}」`"
    width="min(520px, 94vw)"
    class="invite-friends-dialog ui-dialog"
    modal-class="ui-content-overlay"
    append-to-body
    :close-on-click-modal="false"
    @update:model-value="(value) => emit('update:modelValue', value)"
  >
    <div v-loading="loading">
      <p class="hint invite-summary">
        已加入 {{ joinedCount }}/{{ activity?.maxMembers || '—' }} 人，剩余 {{ remaining }} 个名额
        <span v-if="isCancelled" class="invite-cancelled">活动已取消，不能再邀请</span>
      </p>

      <p v-if="!loading && !friends.length" class="hint empty">
        还没有好友可邀请。先到「好友」页添加好友，再回来。
        <button type="button" class="ui-text-action" @click="goFriends">去好友页</button>
      </p>

      <el-checkbox-group v-if="friends.length" v-model="selectedIds" class="invite-list">
        <el-checkbox
          v-for="candidate in candidates"
          :key="candidate.userId"
          :value="candidate.userId"
          :disabled="candidate.status !== 'ELIGIBLE' || isCancelled"
          class="invite-row"
        >
          <span class="invite-row__name">{{ candidate.nickname || candidate.username }}</span>
          <el-tag v-if="candidate.status === 'JOINED'" size="small" type="success" effect="plain">已加入</el-tag>
          <el-tag v-else-if="candidate.status === 'PENDING'" size="small" type="warning" effect="plain">已邀请</el-tag>
          <el-tag v-else-if="candidate.status === 'DECLINED'" size="small" type="info" effect="plain">已拒绝</el-tag>
          <el-tag v-else size="small" effect="plain">可邀请</el-tag>
        </el-checkbox>
      </el-checkbox-group>

      <p v-if="friends.length && remaining <= 0" class="hint">
        名额已满；如需继续邀请，请先提高人数上限或让部分成员取消报名。
      </p>
    </div>

    <template #footer>
      <button type="button" class="ui-btn ui-btn--ghost" :disabled="sending" @click="emit('update:modelValue', false)">取消</button>
      <button
        type="button"
        class="ui-btn ui-btn--primary"
        :disabled="sending || !selectedIds.length || remaining <= 0 || isCancelled"
        :aria-busy="sending"
        @click="sendInvites"
      >{{ sending ? '发送中…' : `发送邀请${selectedIds.length ? `（${selectedIds.length}）` : ''}` }}</button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { activityApi, friendApi } from '../api'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  activity: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'invited'])

const router = useRouter()
const loading = ref(false)
const sending = ref(false)
const friends = ref([])
const invites = ref([])
const members = ref([])
const selectedIds = ref([])

const joinedCount = computed(() => members.value.length || props.activity?.joinedCount || 0)
const remaining = computed(() => {
  const max = Number(props.activity?.maxMembers || 0)
  return Math.max(0, max - joinedCount.value)
})
const isCancelled = computed(() => props.activity?.phase === 'CANCELLED')
const candidates = computed(() => friends.value.map((friend) => {
  const joined = members.value.some((m) => Number(m.userId) === Number(friend.userId))
  const invite = invites.value.find((i) => Number(i.inviteeId) === Number(friend.userId))
  let status = 'ELIGIBLE'
  if (joined) status = 'JOINED'
  else if (invite?.status === 0) status = 'PENDING'
  else if (invite?.status === 1) status = 'ACCEPTED'
  else if (invite?.status === 2) status = 'DECLINED'
  return { ...friend, status }
}))

watch(
  () => [props.modelValue, props.activity?.id],
  async () => {
    if (!props.modelValue || !props.activity?.id) return
    selectedIds.value = []
    await loadData()
  },
  { immediate: true }
)

async function loadData() {
  loading.value = true
  try {
    const [friendList, inviteList, memberList] = await Promise.all([
      friendApi.list(),
      activityApi.invites(props.activity.id).catch(() => []),
      activityApi.members(props.activity.id).catch(() => [])
    ])
    friends.value = friendList || []
    invites.value = inviteList || []
    members.value = memberList || []
  } catch (e) {
    ElMessage.error(e.message || '加载邀请信息失败')
  } finally {
    loading.value = false
  }
}

async function sendInvites() {
  if (!selectedIds.value.length || sending.value) return
  if (selectedIds.value.length > remaining.value) {
    ElMessage.warning(`只剩 ${remaining.value} 个名额，请减少邀请人数`)
    return
  }
  sending.value = true
  try {
    const results = await Promise.allSettled(
      selectedIds.value.map((userId) => activityApi.invite(props.activity.id, userId))
    )
    const success = results.filter((r) => r.status === 'fulfilled').length
    const errors = results
      .filter((r) => r.status === 'rejected')
      .map((r) => r.reason?.message || '邀请失败')
    if (success) ElMessage.success(`已发送 ${success} 个邀请`)
    if (errors.length) ElMessage.warning(`${errors.length} 个失败：${[...new Set(errors)].join('；')}`)
    selectedIds.value = []
    await loadData()
    emit('invited', { success, failed: errors.length })
  } finally {
    sending.value = false
  }
}

function goFriends() {
  emit('update:modelValue', false)
  router.push('/friends')
}
</script>

<style scoped>
.invite-summary { margin: 0 0 10px; }
.invite-cancelled { margin-left: 8px; color: var(--ui-danger); }
.invite-list { display: flex; flex-direction: column; gap: 6px; max-height: 320px; overflow: auto; }
.invite-row { display: flex; align-items: center; margin-right: 0; }
.invite-row__name { margin-right: 8px; }
.empty { margin: 8px 0; }
@media (max-width: 768px) {
  :global(.invite-friends-dialog .el-dialog__footer) {
    position: sticky;
    bottom: 0;
    background: var(--ui-surface);
    padding-top: 8px;
  }
  .invite-list {
    max-height: 52vh;
  }
  .invite-row {
    align-items: flex-start;
  }
  .invite-row__name {
    flex: 1 1 100%;
    margin-right: 0;
  }
}
</style>
