<template>
  <div class="page content-page">
    <div class="hero">
      <div>
        <h1>消息中心</h1>
        <p>通知、会话和待办都汇在这里；离线时的通知也会保留</p>
      </div>
      <el-tag effect="plain" round>未读 {{ summary.total }}</el-tag>
    </div>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane name="notifications">
        <template #label>
          通知
          <span v-if="summary.unreadNotifications" class="tab-count">{{ summary.unreadNotifications }}</span>
        </template>
        <div class="toolbar">
          <el-radio-group v-model="notificationScope" size="small" @change="loadNotifications">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button value="unread">未读</el-radio-button>
          </el-radio-group>
          <el-button
            size="small"
            :disabled="!summary.unreadNotifications"
            @click="markAllRead"
          >全部已读</el-button>
        </div>
        <p v-if="notificationError" class="hint empty">{{ notificationError }}</p>
        <p v-else-if="!loadingNotifications && !notifications.length" class="hint empty">
          暂无通知；好友申请、活动邀请、提醒和取消都会在这里留一条记录
        </p>
        <div
          v-for="n in notifications"
          :key="n.id"
          class="row row--clickable"
          :class="{ 'row--unread': !n.read }"
          @click="openNotification(n)"
        >
          <div class="row__main">
            <span v-if="!n.read" class="dot dot--unread" />
            <strong>{{ n.title }}</strong>
            <span v-if="n.content" class="hint">{{ n.content }}</span>
            <span class="hint">{{ fmt(n.createdAt) }}</span>
          </div>
          <div class="row__actions">
            <el-tag size="small" effect="plain" :type="typeTag(n.type)">{{ typeText(n.type) }}</el-tag>
            <el-button v-if="!n.read" size="small" text @click.stop="markRead(n)">标记已读</el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane name="conversations">
        <template #label>
          会话
          <span v-if="summary.unreadChatConversations" class="tab-count">{{ summary.unreadChatConversations }}</span>
        </template>
        <p v-if="!conversations.length" class="hint empty">还没有会话；在地图群聊或好友私聊里发第一条消息吧</p>
        <div
          v-for="c in conversations"
          :key="c.id"
          class="row row--clickable"
          @click="openConversation(c)"
        >
          <div class="row__main">
            <strong>{{ c.title || '会话' }}</strong>
            <el-tag size="small" effect="plain">{{ conversationTypeText(c.type) }}</el-tag>
            <span v-if="c.lastMessage?.content" class="hint last-msg">{{ c.lastMessage.content }}</span>
            <span class="hint">{{ fmt(c.lastMessage?.createdAt || c.createdAt) }}</span>
          </div>
          <div class="row__actions">
            <el-badge v-if="c.unread" :value="c.unread" :max="99">
              <span class="unread-pill">未读</span>
            </el-badge>
            <el-button v-else size="small" text>打开</el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane name="friendRequests">
        <template #label>
          好友申请
          <span v-if="summary.pendingFriendRequests" class="tab-count">{{ summary.pendingFriendRequests }}</span>
        </template>
        <p v-if="!requests.received.length && !requests.sent.length" class="hint empty">没有待处理的好友申请</p>
        <div v-for="r in requests.received" :key="'received-' + r.id" class="row">
          <div class="row__main">
            <strong>{{ r.fromNickname || r.fromUsername }}</strong>
            <span class="hint">@{{ r.fromUsername }}</span>
            <span v-if="r.message" class="hint">「{{ r.message }}」</span>
            <span class="hint">{{ fmt(r.createdAt) }}</span>
          </div>
          <div class="row__actions">
            <el-button size="small" type="primary" @click="acceptFriend(r)">接受</el-button>
            <el-button size="small" @click="rejectFriend(r)">拒绝</el-button>
          </div>
        </div>
        <div v-if="requests.sent.length" class="section">
          <h3>我发出的申请</h3>
          <div v-for="r in requests.sent" :key="'sent-' + r.id" class="row">
            <div class="row__main">
              <strong>{{ r.toNickname || r.toUsername }}</strong>
              <span class="hint">@{{ r.toUsername }}</span>
              <span class="hint">{{ fmt(r.createdAt) }}</span>
            </div>
            <el-tag size="small" effect="plain" :type="requestStatusTag(r.status)">
              {{ requestStatusText(r.status) }}
            </el-tag>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane name="invites">
        <template #label>
          活动邀请
          <span v-if="summary.pendingActivityInvites" class="tab-count">{{ summary.pendingActivityInvites }}</span>
        </template>
        <p v-if="!invites.length" class="hint empty">没有待响应的活动邀请</p>
        <div v-for="i in invites" :key="i.id" class="row">
          <div class="row__main">
            <strong>{{ i.activityTitle }}</strong>
            <span class="hint">{{ fmtRange(i.activityStartTime, i.activityEndTime) }}</span>
            <span class="hint">📍 {{ i.placeName || '地点' }}</span>
            <span class="hint">{{ i.inviterNickname }} 邀请你加入</span>
          </div>
          <div class="row__actions">
            <el-button size="small" type="primary" @click="acceptInvite(i)">接受</el-button>
            <el-button size="small" @click="declineInvite(i)">拒绝</el-button>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { activityApi, chatApi, friendApi, notificationApi } from '../api'
import { on } from '../composables/useChatSocket'
import { useMessageSummary } from '../composables/useMessageSummary'
import { fmt, fmtRange } from '../utils/format'
import { conversationRoute, notificationRoute } from '../utils/notificationTarget'

const route = useRoute()
const router = useRouter()
const { summary, refresh: refreshSummary, refreshSoon } = useMessageSummary()

const activeTab = ref(route.query.tab === 'invites' ? 'invites' : 'notifications')
const notificationScope = ref('all')
const notifications = ref([])
const loadingNotifications = ref(false)
const notificationError = ref('')
const conversations = ref([])
const requests = ref({ received: [], sent: [] })
const invites = ref([])
let removers = []

const TYPE_TEXT = {
  FRIEND_REQUEST: '好友申请',
  FRIEND_ACCEPTED: '好友通过',
  ACTIVITY_INVITED: '活动邀请',
  ACTIVITY_REMINDER: '活动提醒',
  ACTIVITY_CANCELLED: '活动取消',
  ACTIVITY_MEMBER_JOINED: '成员加入'
}
const TYPE_TAG = {
  FRIEND_REQUEST: 'primary',
  FRIEND_ACCEPTED: 'success',
  ACTIVITY_INVITED: 'warning',
  ACTIVITY_REMINDER: 'warning',
  ACTIVITY_CANCELLED: 'danger',
  ACTIVITY_MEMBER_JOINED: 'success'
}

function typeText(type) {
  return TYPE_TEXT[type] || type
}

function typeTag(type) {
  return TYPE_TAG[type] || 'info'
}

function conversationTypeText(type) {
  return { GROUP: '群聊', PRIVATE: '私聊', PLACE: '地点聊' }[type] || type
}

function requestStatusText(status) {
  return { 0: '待处理', 1: '已接受', 2: '已拒绝' }[status] || '未知'
}

function requestStatusTag(status) {
  return { 0: 'warning', 1: 'success', 2: 'info' }[status] || 'info'
}

async function loadNotifications() {
  loadingNotifications.value = true
  notificationError.value = ''
  try {
    const page = await notificationApi.list({
      unreadOnly: notificationScope.value === 'unread',
      page: 1,
      size: 50
    })
    notifications.value = page?.list || []
  } catch (e) {
    notificationError.value = e.message || '通知加载失败'
  } finally {
    loadingNotifications.value = false
  }
}

async function loadConversations() {
  try {
    conversations.value = (await chatApi.conversations()) || []
  } catch (e) {
    ElMessage.error(e.message || '会话加载失败')
  }
}

async function loadRequests() {
  try {
    requests.value = (await friendApi.requests()) || { received: [], sent: [] }
  } catch (e) {
    ElMessage.error(e.message || '好友申请加载失败')
  }
}

async function loadInvites() {
  try {
    invites.value = (await activityApi.pendingInvites()) || []
  } catch (e) {
    ElMessage.error(e.message || '活动邀请加载失败')
  }
}

async function refreshAll() {
  await Promise.all([loadNotifications(), loadConversations(), loadRequests(), loadInvites(), refreshSummary()])
}

function onTabChange(name) {
  if (name === 'notifications') loadNotifications()
  if (name === 'conversations') loadConversations()
  if (name === 'friendRequests') loadRequests()
  if (name === 'invites') loadInvites()
  refreshSummary()
}

async function markRead(n) {
  if (n.read) return
  try {
    await notificationApi.markRead(n.id)
    n.read = true
    if (notificationScope.value === 'unread') {
      notifications.value = notifications.value.filter((item) => item.id !== n.id)
    }
    await refreshSummary()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function markAllRead() {
  try {
    await notificationApi.markAllRead()
    await Promise.all([loadNotifications(), refreshSummary()])
    ElMessage.success('已全部标记为已读')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function openNotification(n) {
  if (!n.read) {
    await markRead(n)
  }
  router.push(notificationRoute(n))
}

function openConversation(c) {
  router.push(conversationRoute(c))
}

async function acceptFriend(r) {
  try {
    await friendApi.accept(r.id)
    ElMessage.success('已添加好友')
    await Promise.all([loadRequests(), loadConversations(), refreshSummary()])
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function rejectFriend(r) {
  try {
    await friendApi.reject(r.id)
    ElMessage.info('已拒绝该好友申请')
    await Promise.all([loadRequests(), refreshSummary()])
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function acceptInvite(i) {
  try {
    await activityApi.acceptInvite(i.id)
    ElMessage.success(`已加入「${i.activityTitle}」`)
    await Promise.all([loadInvites(), loadNotifications(), refreshSummary()])
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function declineInvite(i) {
  try {
    await activityApi.declineInvite(i.id)
    ElMessage.info('已拒绝该邀请')
    await Promise.all([loadInvites(), loadNotifications(), refreshSummary()])
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  await refreshAll()
  removers = [
    on('NOTIFICATION', () => {
      loadNotifications()
      refreshSoon()
    }),
    on('UNREAD', () => {
      loadConversations()
      refreshSoon()
    }),
    on('FRIEND_REQUEST', () => {
      loadRequests()
      refreshSoon()
    }),
    on('FRIEND_ACCEPTED', () => {
      Promise.all([loadRequests(), loadConversations()])
      refreshSoon()
    }),
    on('ACTIVITY_INVITED', () => {
      loadInvites()
      refreshSoon()
    }),
    on('ACTIVITY_CANCELLED', () => {
      Promise.all([loadInvites(), loadNotifications()])
      refreshSoon()
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
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin: 40px 0 20px;
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
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
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
.row--clickable {
  cursor: pointer;
}
.row--clickable:hover {
  box-shadow: 0 4px 16px rgba(31, 56, 88, 0.12);
}
.row--unread {
  background: #f6f9ff;
}
.row__main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
  flex: 1;
}
.row__actions {
  display: flex;
  align-items: center;
  gap: 6px;
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
.last-msg {
  max-width: 340px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c9ced6;
  flex-shrink: 0;
}
.dot--unread {
  background: #409eff;
}
.tab-count {
  display: inline-block;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  margin-left: 4px;
  border-radius: 8px;
  background: #f56c6c;
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}
.unread-pill {
  font-size: 12px;
  color: #409eff;
}
.section {
  margin-top: 20px;
}
.section h3 {
  margin: 0 0 10px;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.9);
}
@media (max-width: 600px) {
  .page {
    padding: 0 16px 40px;
  }
  .hero {
    margin: 24px 0 16px;
  }
  .hero h1 {
    font-size: 24px;
  }
  .row {
    align-items: flex-start;
  }
  .row__actions {
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .last-msg {
    max-width: 100%;
  }
  .toolbar {
    flex-wrap: wrap;
  }
  :deep(.el-tabs__item) {
    padding: 0 10px;
    font-size: 13px;
  }
}
</style>
