<template>
  <div class="page content-page messages-page">
    <PageHeader
      title="消息中心"
      description="通知、会话和待办都汇在这里；离线时的通知也会保留"
    >
      <template #summary>
        <span class="ui-pill" :class="{ 'ui-pill--muted': !summary.total }">未读 {{ summary.total }}</span>
      </template>
    </PageHeader>

    <el-tabs v-model="activeTab" class="messages-tabs" @tab-change="onTabChange">
      <el-tab-pane name="notifications">
        <template #label>
          <span class="messages-tab-label">
            通知
            <span v-if="summary.unreadNotifications" class="ui-count messages-tab-count">{{ summary.unreadNotifications }}</span>
          </span>
        </template>

        <div class="messages-toolbar">
          <div class="ui-chips messages-scope" role="group" aria-label="通知范围">
            <button
              v-for="scope in [{ value: 'all', label: '全部' }, { value: 'unread', label: '未读' }]"
              :key="scope.value"
              type="button"
              class="ui-chip"
              :class="{ 'is-active': notificationScope === scope.value }"
              @click="setNotificationScope(scope.value)"
            >{{ scope.label }}</button>
          </div>
          <button
            type="button"
            class="ui-text-action ui-text-action--muted messages-read-all"
            :disabled="!summary.unreadNotifications || isPending('all-read')"
            @click="markAllRead"
          >{{ isPending('all-read') ? '处理中…' : '全部已读' }}</button>
        </div>

        <div v-if="loadingNotifications" class="messages-state">
          <span class="ui-spinner" />
          正在加载通知…
        </div>
        <div v-if="notificationError" class="messages-error">
          <AppIcon name="alert-circle" :size="15" />
          <span>{{ notificationError }}</span>
          <button type="button" class="ui-text-action" @click="loadNotifications">重试</button>
        </div>
        <div v-if="!loadingNotifications && notifications.length" class="messages-list">
          <article
            v-for="n in notifications"
            :key="n.id"
            class="message-row"
            :class="{ 'is-unread': !n.read }"
          >
            <button type="button" class="message-row__open" @click="openNotification(n)">
              <span v-if="!n.read" class="message-row__dot" aria-hidden="true" />
              <div class="message-row__main">
                <div class="message-row__top">
                  <strong class="message-row__title">{{ n.title }}</strong>
                  <time class="message-row__time">{{ fmt(n.createdAt) }}</time>
                </div>
                <p v-if="n.content" class="message-row__summary">{{ n.content }}</p>
                <div class="message-row__meta">
                  <span class="ui-pill ui-pill--muted">{{ typeText(n.type) }}</span>
                  <span v-if="!n.read" class="message-row__unread">未读</span>
                </div>
              </div>
            </button>
            <div v-if="!n.read" class="message-row__actions">
              <button
                type="button"
                class="ui-text-action"
                :disabled="isPending('notification:' + n.id)"
                @click.stop="markRead(n)"
              >{{ isPending('notification:' + n.id) ? '处理中…' : '标记已读' }}</button>
            </div>
          </article>
        </div>
        <EmptyState
          v-else-if="!loadingNotifications && !notificationError"
          icon="message"
          title="暂无通知"
          description="好友申请、活动邀请、提醒和取消都会在这里留一条记录。"
          compact
        />
      </el-tab-pane>

      <el-tab-pane name="conversations">
        <template #label>
          <span class="messages-tab-label">
            会话
            <span v-if="summary.unreadChatConversations" class="ui-count messages-tab-count">{{ summary.unreadChatConversations }}</span>
          </span>
        </template>

        <div v-if="loadingConversations" class="messages-state">
          <span class="ui-spinner" />
          正在加载会话…
        </div>
        <div v-if="conversationError" class="messages-error">
          <AppIcon name="alert-circle" :size="15" />
          <span>{{ conversationError }}</span>
          <button type="button" class="ui-text-action" @click="loadConversations">重试</button>
        </div>
        <div v-if="!loadingConversations && conversations.length" class="messages-list">
          <button
            v-for="c in conversations"
            :key="c.id"
            type="button"
            class="message-row message-row--button"
            :class="{ 'is-unread': c.unread }"
            @click="openConversation(c)"
          >
            <span class="message-row__avatar" aria-hidden="true">{{ (c.title || '会').slice(0, 1) }}</span>
            <div class="message-row__main">
              <div class="message-row__top">
                <strong class="message-row__title">{{ c.title || '会话' }}</strong>
                <time class="message-row__time">{{ fmt(c.lastMessage?.createdAt || c.createdAt) }}</time>
              </div>
              <p v-if="c.lastMessage?.content" class="message-row__summary">{{ c.lastMessage.content }}</p>
              <div class="message-row__meta">
                <span class="ui-pill ui-pill--muted">{{ conversationTypeText(c.type) }}</span>
                <span v-if="c.unread" class="ui-count messages-tab-count">{{ c.unread > 99 ? '99+' : c.unread }}</span>
              </div>
            </div>
            <AppIcon name="chevron-right" :size="15" class="message-row__chev" />
          </button>
        </div>
        <EmptyState
          v-else-if="!loadingConversations && !conversationError"
          icon="message"
          title="还没有会话"
          description="在地图群聊或好友私聊里发第一条消息吧。"
          compact
        />
      </el-tab-pane>

      <el-tab-pane name="friendRequests">
        <template #label>
          <span class="messages-tab-label">
            好友申请
            <span v-if="summary.pendingFriendRequests" class="ui-count messages-tab-count">{{ summary.pendingFriendRequests }}</span>
          </span>
        </template>

        <div v-if="loadingRequests" class="messages-state">
          <span class="ui-spinner" />
          正在加载好友申请…
        </div>
        <div v-if="requestsError" class="messages-error">
          <AppIcon name="alert-circle" :size="15" />
          <span>{{ requestsError }}</span>
          <button type="button" class="ui-text-action" @click="loadRequests">重试</button>
        </div>
        <div v-if="!loadingRequests && requests.received.length" class="messages-list">
          <article v-for="r in requests.received" :key="'received-' + r.id" class="message-row">
            <span class="message-row__avatar" aria-hidden="true">{{ (r.fromNickname || r.fromUsername || '?').slice(0, 1) }}</span>
            <div class="message-row__main">
              <div class="message-row__top">
                <strong class="message-row__title">{{ r.fromNickname || r.fromUsername }}</strong>
                <time class="message-row__time">{{ fmt(r.createdAt) }}</time>
              </div>
              <div class="message-row__meta">
                <span class="message-row__sub">@{{ r.fromUsername }}</span>
                <span v-if="r.message" class="message-row__sub">「{{ r.message }}」</span>
              </div>
            </div>
            <div class="message-row__actions">
              <button
                type="button"
                class="ui-btn ui-btn--sm ui-btn--primary"
                :disabled="isPending('friend:' + r.id)"
                @click="acceptFriend(r)"
              >接受</button>
              <button
                type="button"
                class="ui-btn ui-btn--sm ui-btn--ghost"
                :disabled="isPending('friend:' + r.id)"
                @click="rejectFriend(r)"
              >拒绝</button>
            </div>
          </article>
        </div>
        <EmptyState
          v-else-if="!loadingRequests && !requestsError && !requests.sent.length"
          icon="users"
          title="没有待处理的好友申请"
          description="新的好友申请会出现在这里。"
          compact
        />

        <div v-if="!loadingRequests && requests.sent.length" class="messages-sent">
          <SectionTitle title="我发出的申请" :count="requests.sent.length" />
          <div class="messages-list">
            <article v-for="r in requests.sent" :key="'sent-' + r.id" class="message-row">
              <span class="message-row__avatar" aria-hidden="true">{{ (r.toNickname || r.toUsername || '?').slice(0, 1) }}</span>
              <div class="message-row__main">
                <div class="message-row__top">
                  <strong class="message-row__title">{{ r.toNickname || r.toUsername }}</strong>
                  <time class="message-row__time">{{ fmt(r.createdAt) }}</time>
                </div>
                <div class="message-row__meta">
                  <span class="message-row__sub">@{{ r.toUsername }}</span>
                  <span class="ui-pill message-status" :class="'message-status--' + (r.status === 1 ? 'ok' : r.status === 2 ? 'muted' : 'pending')">
                    {{ requestStatusText(r.status) }}
                  </span>
                </div>
              </div>
            </article>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane name="invites">
        <template #label>
          <span class="messages-tab-label">
            活动邀请
            <span v-if="summary.pendingActivityInvites" class="ui-count messages-tab-count">{{ summary.pendingActivityInvites }}</span>
          </span>
        </template>

        <div v-if="loadingInvites" class="messages-state">
          <span class="ui-spinner" />
          正在加载活动邀请…
        </div>
        <div v-if="invitesError" class="messages-error">
          <AppIcon name="alert-circle" :size="15" />
          <span>{{ invitesError }}</span>
          <button type="button" class="ui-text-action" @click="loadInvites">重试</button>
        </div>
        <div v-if="!loadingInvites && invites.length" class="messages-list">
          <article v-for="i in invites" :key="i.id" class="message-row">
            <span class="message-row__avatar message-row__avatar--activity" aria-hidden="true">
              <AppIcon name="calendar" :size="16" />
            </span>
            <div class="message-row__main">
              <div class="message-row__top">
                <strong class="message-row__title">{{ i.activityTitle }}</strong>
                <time class="message-row__time">{{ fmt(i.createdAt) }}</time>
              </div>
              <p class="message-row__summary">{{ fmtRange(i.activityStartTime, i.activityEndTime) }}</p>
              <div class="message-row__meta">
                <span v-if="i.placeName" class="message-row__sub">
                  <AppIcon name="pin" :size="12" /> {{ i.placeName }}
                </span>
                <span class="message-row__sub">{{ i.inviterNickname }} 邀请你加入</span>
              </div>
            </div>
            <div class="message-row__actions">
              <button
                type="button"
                class="ui-btn ui-btn--sm ui-btn--primary"
                :disabled="isPending('invite:' + i.id)"
                @click="acceptInvite(i)"
              >接受</button>
              <button
                type="button"
                class="ui-btn ui-btn--sm ui-btn--ghost"
                :disabled="isPending('invite:' + i.id)"
                @click="declineInvite(i)"
              >拒绝</button>
            </div>
          </article>
        </div>
        <EmptyState
          v-else-if="!loadingInvites && !invitesError"
          icon="calendar"
          title="没有待响应的活动邀请"
          description="收到活动邀请后会在这里等待你的响应。"
          compact
        />
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
import AppIcon from '../components/ui/AppIcon.vue'
import EmptyState from '../components/ui/EmptyState.vue'
import PageHeader from '../components/ui/PageHeader.vue'
import SectionTitle from '../components/ui/SectionTitle.vue'

const route = useRoute()
const router = useRouter()
const { summary, refresh: refreshSummary, refreshSoon } = useMessageSummary()

const activeTab = ref(route.query.tab === 'invites' ? 'invites' : 'notifications')
const notificationScope = ref('all')
const notifications = ref([])
const loadingNotifications = ref(false)
const notificationError = ref('')
const conversations = ref([])
const loadingConversations = ref(false)
const conversationError = ref('')
const requests = ref({ received: [], sent: [] })
const loadingRequests = ref(false)
const requestsError = ref('')
const invites = ref([])
const loadingInvites = ref(false)
const invitesError = ref('')
const pending = ref({})
let removers = []
let notificationSeq = 0
let conversationSeq = 0
let requestSeq = 0
let inviteSeq = 0

const TYPE_TEXT = {
  FRIEND_REQUEST: '好友申请',
  FRIEND_ACCEPTED: '好友通过',
  ACTIVITY_INVITED: '活动邀请',
  ACTIVITY_REMINDER: '活动提醒',
  ACTIVITY_CANCELLED: '活动取消',
  ACTIVITY_MEMBER_JOINED: '成员加入'
}

function typeText(type) {
  return TYPE_TEXT[type] || type
}

function conversationTypeText(type) {
  return { GROUP: '群聊', PRIVATE: '私聊', PLACE: '地点聊' }[type] || type
}

function requestStatusText(status) {
  return { 0: '待处理', 1: '已接受', 2: '已拒绝' }[status] || '未知'
}

function isPending(key) {
  return pending.value[key] === true
}

function setPending(key, value) {
  if (value) pending.value[key] = true
  else delete pending.value[key]
}

async function loadNotifications() {
  const seq = ++notificationSeq
  loadingNotifications.value = true
  notificationError.value = ''
  try {
    const page = await notificationApi.list({
      unreadOnly: notificationScope.value === 'unread',
      page: 1,
      size: 50
    })
    if (seq !== notificationSeq) return
    notifications.value = page?.list || []
  } catch (e) {
    if (seq !== notificationSeq) return
    notificationError.value = e.message || '通知加载失败'
  } finally {
    if (seq === notificationSeq) loadingNotifications.value = false
  }
}

async function loadConversations() {
  const seq = ++conversationSeq
  loadingConversations.value = true
  conversationError.value = ''
  try {
    const list = (await chatApi.conversations()) || []
    if (seq !== conversationSeq) return
    conversations.value = list
  } catch (e) {
    if (seq !== conversationSeq) return
    conversationError.value = e.message || '会话加载失败'
  } finally {
    if (seq === conversationSeq) loadingConversations.value = false
  }
}

async function loadRequests() {
  const seq = ++requestSeq
  loadingRequests.value = true
  requestsError.value = ''
  try {
    const data = (await friendApi.requests()) || { received: [], sent: [] }
    if (seq !== requestSeq) return
    requests.value = data
  } catch (e) {
    if (seq !== requestSeq) return
    requestsError.value = e.message || '好友申请加载失败'
  } finally {
    if (seq === requestSeq) loadingRequests.value = false
  }
}

async function loadInvites() {
  const seq = ++inviteSeq
  loadingInvites.value = true
  invitesError.value = ''
  try {
    const list = (await activityApi.pendingInvites()) || []
    if (seq !== inviteSeq) return
    invites.value = list
  } catch (e) {
    if (seq !== inviteSeq) return
    invitesError.value = e.message || '活动邀请加载失败'
  } finally {
    if (seq === inviteSeq) loadingInvites.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadNotifications(), loadConversations(), loadRequests(), loadInvites(), refreshSummary()])
}

function setNotificationScope(scope) {
  if (notificationScope.value === scope) return
  notificationScope.value = scope
  loadNotifications()
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
  const key = `notification:${n.id}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    await notificationApi.markRead(n.id)
    n.read = true
    if (notificationScope.value === 'unread') {
      notifications.value = notifications.value.filter((item) => item.id !== n.id)
    }
    await refreshSummary()
  } catch (e) {
    ElMessage.error(e.message || '标记失败')
  } finally {
    setPending(key, false)
  }
}

async function markAllRead() {
  if (isPending('all-read') || !summary.unreadNotifications) return
  setPending('all-read', true)
  try {
    await notificationApi.markAllRead()
    await Promise.all([loadNotifications(), refreshSummary()])
    ElMessage.success('已全部标记为已读')
  } catch (e) {
    ElMessage.error(e.message || '标记失败')
  } finally {
    setPending('all-read', false)
  }
}

async function openNotification(n) {
  if (!n.read) await markRead(n)
  router.push(notificationRoute(n))
}

function openConversation(c) {
  router.push(conversationRoute(c))
}

async function acceptFriend(r) {
  const key = `friend:${r.id}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    await friendApi.accept(r.id)
    ElMessage.success('已添加好友')
    await Promise.all([loadRequests(), loadConversations(), refreshSummary()])
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    setPending(key, false)
  }
}

async function rejectFriend(r) {
  const key = `friend:${r.id}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    await friendApi.reject(r.id)
    ElMessage.info('已拒绝该好友申请')
    await Promise.all([loadRequests(), refreshSummary()])
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    setPending(key, false)
  }
}

async function acceptInvite(i) {
  const key = `invite:${i.id}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    await activityApi.acceptInvite(i.id)
    ElMessage.success(`已加入「${i.activityTitle}」`)
    await Promise.all([loadInvites(), loadNotifications(), refreshSummary()])
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    setPending(key, false)
  }
}

async function declineInvite(i) {
  const key = `invite:${i.id}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    await activityApi.declineInvite(i.id)
    ElMessage.info('已拒绝该邀请')
    await Promise.all([loadInvites(), loadNotifications(), refreshSummary()])
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    setPending(key, false)
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
.messages-page {
  padding-bottom: 56px;
}

.messages-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.messages-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.messages-tab-count {
  background: var(--ui-accent-tint);
  color: var(--ui-accent);
}

.messages-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.messages-scope {
  flex-wrap: wrap;
}

.messages-scope .ui-chip {
  height: 32px;
  padding: 0 12px;
}

.messages-read-all {
  margin-left: auto;
  font-size: 12px;
}

.messages-state {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 22px 4px;
  font-size: 12.5px;
  color: var(--ui-ink-3);
}

.messages-error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: var(--ui-radius-md);
  background: var(--ui-danger-tint);
  color: var(--ui-danger);
  font-size: 12.5px;
}

.messages-error .ui-text-action {
  margin-left: auto;
  color: var(--ui-danger);
}

.messages-list {
  border-top: 1px solid var(--ui-line);
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  min-width: 0;
  padding: 14px 8px;
  border: 0;
  border-bottom: 1px solid var(--ui-line);
  background: transparent;
  color: inherit;
  text-align: left;
  transition: background var(--ui-dur) var(--ui-ease);
}

.message-row__open {
  display: flex;
  flex: 1;
  align-items: flex-start;
  gap: 11px;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.message-row--button {
  width: 100%;
  font: inherit;
  cursor: pointer;
}

.message-row.is-clickable,
.message-row--button {
  cursor: pointer;
}

.message-row.is-clickable:hover,
.message-row--button:hover,
.message-row:has(.message-row__open:hover) {
  background: var(--ui-surface-2);
}

.message-row.is-unread {
  background: var(--ui-accent-tint);
}

.message-row.is-unread:hover,
.message-row.is-unread:has(.message-row__open:hover) {
  background: #e6eef9;
}

.message-row__open:focus-visible,
.message-row--button:focus-visible {
  outline: 2px solid var(--ui-accent);
  outline-offset: -2px;
  border-radius: var(--ui-radius-sm);
}

.message-row__dot {
  flex: none;
  width: 7px;
  height: 7px;
  margin-top: 7px;
  border-radius: 50%;
  background: var(--ui-accent);
}

.message-row__avatar {
  flex: none;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--ui-surface-2);
  color: var(--ui-ink-2);
  font-size: 12px;
  font-weight: 600;
}

.message-row__avatar--activity {
  background: var(--ui-accent-tint);
  color: var(--ui-accent);
}

.message-row__main {
  flex: 1;
  min-width: 0;
}

.message-row__top {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.message-row__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ui-ink);
}

.message-row__time {
  flex: none;
  font-size: 11px;
  color: var(--ui-ink-3);
  white-space: nowrap;
}

.message-row__summary {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--ui-ink-2);
  overflow-wrap: anywhere;
}

.message-row__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 7px;
}

.message-row__sub {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11.5px;
  color: var(--ui-ink-3);
  overflow-wrap: anywhere;
}

.message-row__unread {
  font-size: 11px;
  font-weight: 600;
  color: var(--ui-accent);
}

.message-row__chev {
  flex: none;
  margin-top: 8px;
  color: var(--ui-ink-3);
}

.message-row__actions {
  flex: none;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-left: 4px;
}

.message-status--pending {
  background: var(--ui-warning-tint);
  color: var(--ui-warning);
}

.message-status--ok {
  background: var(--ui-success-tint);
  color: var(--ui-success);
}

.message-status--muted {
  background: var(--ui-surface-2);
  color: var(--ui-ink-3);
}

.messages-sent {
  margin-top: 22px;
}

.messages-sent :deep(.ui-section-title) {
  margin-bottom: 8px;
}

@media (max-width: 768px) {
  .messages-page {
    padding-bottom: 36px;
  }

  .message-row {
    flex-wrap: wrap;
  }

  .message-row__main {
    flex: 1 1 calc(100% - 42px);
  }

  .message-row__actions {
    width: 100%;
    margin-left: 0;
    padding-left: 42px;
    justify-content: flex-end;
  }

  .message-row__actions .ui-btn {
    min-height: var(--ui-tap);
  }

  .message-row__title {
    white-space: normal;
    overflow: visible;
    text-overflow: clip;
    overflow-wrap: anywhere;
  }

  .messages-tabs :deep(.el-tabs__item) {
    padding: 0 9px;
    font-size: 12.5px;
  }
}

@media (max-width: 389px) {
  .message-row {
    padding-left: 4px;
    padding-right: 4px;
  }

  .messages-tabs :deep(.el-tabs__item) {
    padding: 0 7px;
    font-size: 12px;
  }

  .messages-tab-label {
    gap: 3px;
  }
}
</style>
