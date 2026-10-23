<template>
  <div class="page content-page friends-page">
    <PageHeader
      title="好友"
      description="和朋友一起维护校园地图；私聊与群聊都沿用同一套消息能力。"
    />

    <section class="friends-section">
      <div class="friends-search">
        <SearchBar
          v-model="keyword"
          placeholder="搜索用户名或昵称"
          size="lg"
          @submit="doSearch"
        />
        <button type="button" class="ui-btn ui-btn--secondary" @click="doSearch">搜索</button>
      </div>
      <div v-if="searching" class="friends-state">
        <span class="ui-spinner" />
        正在搜索…
      </div>
      <div v-if="searchError" class="friends-error">
        <AppIcon name="alert-circle" :size="15" />
        <span>{{ searchError }}</span>
        <button type="button" class="ui-text-action" @click="doSearch">重试</button>
      </div>
      <div v-if="!searching && searchResults.length" class="friends-list">
        <article v-for="u in searchResults" :key="u.userId" class="friend-row">
          <span class="friend-avatar" aria-hidden="true">{{ (u.nickname || u.username || '?').slice(0, 1) }}</span>
          <div class="friend-row__main">
            <strong class="friend-row__name">{{ u.nickname || u.username }}</strong>
            <span class="friend-row__sub">@{{ u.username }}</span>
          </div>
          <div class="friend-row__actions">
            <button
              v-if="!u.friend && !u.requested"
              type="button"
              class="ui-btn ui-btn--sm ui-btn--primary"
              :disabled="isPending('add:' + u.userId)"
              @click="addFriend(u)"
            >{{ isPending('add:' + u.userId) ? '发送中…' : '加好友' }}</button>
            <span v-else class="ui-pill ui-pill--muted">{{ u.friend ? '已是好友' : '已申请' }}</span>
          </div>
        </article>
      </div>
      <EmptyState
        v-else-if="searched && !searching && !searchError"
        icon="search"
        title="没有匹配的用户"
        description="换个用户名或昵称再试试。"
        compact
      />
    </section>

    <section v-if="requests.received.length || loadingRequests || requestsError" class="friends-section">
      <SectionTitle title="收到的好友请求" :count="requests.received.length" />
      <div v-if="loadingRequests" class="friends-state">
        <span class="ui-spinner" />
        正在加载好友请求…
      </div>
      <div v-if="requestsError" class="friends-error">
        <AppIcon name="alert-circle" :size="15" />
        <span>{{ requestsError }}</span>
        <button type="button" class="ui-text-action" @click="loadRequests">重试</button>
      </div>
      <div v-if="!loadingRequests && requests.received.length" class="friends-list">
        <article v-for="r in requests.received" :key="r.id" class="friend-row">
          <span class="friend-avatar" aria-hidden="true">{{ (r.fromNickname || r.fromUsername || '?').slice(0, 1) }}</span>
          <div class="friend-row__main">
            <strong class="friend-row__name">{{ r.fromNickname || r.fromUsername }}</strong>
            <span class="friend-row__sub">@{{ r.fromUsername }}</span>
            <span v-if="r.message" class="friend-row__note">「{{ r.message }}」</span>
          </div>
          <div class="friend-row__actions">
            <button
              type="button"
              class="ui-btn ui-btn--sm ui-btn--primary"
              :disabled="isPending('request:' + r.id)"
              @click="accept(r)"
            >接受</button>
            <button
              type="button"
              class="ui-btn ui-btn--sm ui-btn--ghost"
              :disabled="isPending('request:' + r.id)"
              @click="reject(r)"
            >拒绝</button>
          </div>
        </article>
      </div>
    </section>

    <section class="friends-section">
      <SectionTitle title="我的好友" :count="friends.length" />
      <div v-if="loadingFriends" class="friends-state">
        <span class="ui-spinner" />
        正在加载好友…
      </div>
      <div v-if="friendsError" class="friends-error">
        <AppIcon name="alert-circle" :size="15" />
        <span>{{ friendsError }}</span>
        <button type="button" class="ui-text-action" @click="loadFriends">重试</button>
      </div>
      <div v-if="!loadingFriends && friends.length" class="friends-list">
        <article v-for="f in friends" :key="f.userId" class="friend-row">
          <span class="friend-avatar" :class="{ 'is-online': f.online }" aria-hidden="true">
            {{ (f.nickname || f.username || '?').slice(0, 1) }}
            <span class="friend-avatar__dot" />
          </span>
          <div class="friend-row__main">
            <strong class="friend-row__name">{{ f.nickname || f.username }}</strong>
            <span class="friend-row__sub">@{{ f.username }} · {{ f.online ? '在线' : '离线' }}</span>
          </div>
          <div class="friend-row__actions">
            <button type="button" class="ui-btn ui-btn--sm ui-btn--secondary" @click="openChat(f)">私聊</button>
            <button
              type="button"
              class="ui-text-action ui-text-action--muted friend-row__remove"
              :disabled="isPending('remove:' + f.userId)"
              @click="removeFriend(f)"
            >删除</button>
          </div>
        </article>
      </div>
      <EmptyState
        v-else-if="!loadingFriends && !friendsError"
        icon="users"
        title="还没有好友"
        description="在上方搜索用户名或昵称，发出第一条好友申请。"
        compact
      />
    </section>
  </div>

  <el-dialog
    v-model="chatOpen"
    :title="chatPeer"
    width="min(420px, 94vw)"
    class="ui-dialog friends-chat-dialog"
    modal-class="ui-content-overlay"
    append-to-body
  >
    <div v-if="activeConversation" class="ui-v2 friends-chat-panel">
      <ChatPanel :conversation="activeConversation" style="height: min(380px, 58vh)" />
    </div>
  </el-dialog>
</template>

<script setup>
// 全局顶栏（品牌/导航/退出/好友申请角标）由路由壳 AppShell 提供
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chatApi, friendApi } from '../api'
import { on } from '../composables/useChatSocket'
import ChatPanel from '../components/ChatPanel.vue'
import AppIcon from '../components/ui/AppIcon.vue'
import EmptyState from '../components/ui/EmptyState.vue'
import PageHeader from '../components/ui/PageHeader.vue'
import SearchBar from '../components/ui/SearchBar.vue'
import SectionTitle from '../components/ui/SectionTitle.vue'

const route = useRoute()
const router = useRouter()
const keyword = ref('')
const searching = ref(false)
const searched = ref(false)
const searchResults = ref([])
const searchError = ref('')
const friends = ref([])
const loadingFriends = ref(false)
const friendsError = ref('')
const requests = ref({ received: [], sent: [] })
const loadingRequests = ref(false)
const requestsError = ref('')
const chatOpen = ref(false)
const chatPeer = ref('')
const activeConversation = ref(null)
const pending = ref({})
let removers = []
let friendsSeq = 0
let requestsSeq = 0
let searchSeq = 0

function isPending(key) {
  return pending.value[key] === true
}

function setPending(key, value) {
  if (value) pending.value[key] = true
  else delete pending.value[key]
}

async function loadFriends() {
  const seq = ++friendsSeq
  loadingFriends.value = true
  friendsError.value = ''
  try {
    const list = await friendApi.list()
    if (seq !== friendsSeq) return
    friends.value = Array.isArray(list) ? list : []
  } catch (e) {
    if (seq !== friendsSeq) return
    friendsError.value = e.message || '好友加载失败'
  } finally {
    if (seq === friendsSeq) loadingFriends.value = false
  }
}

async function loadRequests() {
  const seq = ++requestsSeq
  loadingRequests.value = true
  requestsError.value = ''
  try {
    const data = await friendApi.requests()
    if (seq !== requestsSeq) return
    requests.value = data || { received: [], sent: [] }
  } catch (e) {
    if (seq !== requestsSeq) return
    requestsError.value = e.message || '好友请求加载失败'
  } finally {
    if (seq === requestsSeq) loadingRequests.value = false
  }
}

async function doSearch() {
  const query = keyword.value.trim()
  if (!query) {
    searchResults.value = []
    searched.value = false
    searchError.value = ''
    return
  }
  const seq = ++searchSeq
  searching.value = true
  searchError.value = ''
  try {
    const list = await friendApi.search(query)
    if (seq !== searchSeq) return
    searchResults.value = Array.isArray(list) ? list : []
    searched.value = true
  } catch (e) {
    if (seq !== searchSeq) return
    searchError.value = e.message || '搜索失败'
  } finally {
    if (seq === searchSeq) searching.value = false
  }
}

async function addFriend(u) {
  const key = `add:${u.userId}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    await friendApi.sendRequest({ userId: u.userId })
    ElMessage.success(`已向 ${u.nickname || u.username} 发出好友请求`)
    u.requested = true
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    setPending(key, false)
  }
}

async function accept(r) {
  const key = `request:${r.id}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    await friendApi.accept(r.id)
    ElMessage.success(`已添加 ${r.fromNickname} 为好友`)
    await Promise.all([loadFriends(), loadRequests()])
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    setPending(key, false)
  }
}

async function reject(r) {
  const key = `request:${r.id}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    await friendApi.reject(r.id)
    await loadRequests()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    setPending(key, false)
  }
}

/** 消息中心深链：/friends?chat={peerUserId} 打开与某位好友的私聊 */
async function openRequestedChat() {
  const peer = Number(route.query.chat)
  if (!Number.isInteger(peer) || peer <= 0) return
  let friend = friends.value.find((f) => f.userId === peer)
  if (!friend) {
    await loadFriends()
    friend = friends.value.find((f) => f.userId === peer)
  }
  if (friend) {
    await openChat(friend)
  } else {
    ElMessage.warning('未找到该好友，可能已解除好友关系')
  }
  await router.replace({ path: '/friends' })
}

async function openChat(f) {
  try {
    activeConversation.value = await chatApi.createPrivate(f.userId)
    chatPeer.value = f.nickname || f.username
    chatOpen.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeFriend(f) {
  const key = `remove:${f.userId}`
  if (isPending(key)) return
  try {
    await ElMessageBox.confirm(`确定删除好友 ${f.nickname || f.username}？私聊会话将不可再发送`, '确认')
  } catch {
    return
  }
  setPending(key, true)
  try {
    await friendApi.remove(f.userId)
    ElMessage.success('已删除')
    await loadFriends()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    setPending(key, false)
  }
}

onMounted(async () => {
  await Promise.all([loadFriends(), loadRequests()])
  await openRequestedChat()
  removers = [
    on('FRIEND_REQUEST', () => loadRequests()),
    on('FRIEND_ACCEPTED', (frame) => {
      ElMessage.success(`${frame.nickname} 接受了你的好友请求`)
      loadFriends()
    }),
    on('PRESENCE', (frame) => {
      const friend = friends.value.find((f) => f.userId === frame.userId)
      if (friend) friend.online = frame.online
    })
  ]
})

onUnmounted(() => {
  for (const off of removers) off()
})
</script>

<style scoped>
.friends-section {
  margin-bottom: 24px;
}

.friends-search {
  display: flex;
  align-items: center;
  gap: 8px;
}

.friends-search .ui-search {
  flex: 1;
}

.friends-state {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 2px;
  font-size: 12.5px;
  color: var(--ui-ink-3);
}

.friends-error {
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

.friends-error .ui-text-action {
  margin-left: auto;
  color: var(--ui-danger);
}

.friends-list {
  margin-top: 10px;
  border-top: 1px solid var(--ui-line);
}

.friend-row {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
  padding: 12px 2px;
  border-bottom: 1px solid var(--ui-line);
}

.friend-avatar {
  position: relative;
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

.friend-avatar__dot {
  position: absolute;
  right: -1px;
  bottom: -1px;
  width: 8px;
  height: 8px;
  border: 2px solid var(--ui-surface);
  border-radius: 50%;
  background: var(--ui-ink-3);
}

.friend-avatar.is-online .friend-avatar__dot {
  background: var(--ui-success);
}

.friend-row__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 7px;
}

.friend-row__name {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ui-ink);
}

.friend-row__sub,
.friend-row__note {
  min-width: 0;
  font-size: 11.5px;
  color: var(--ui-ink-3);
  overflow-wrap: anywhere;
}

.friend-row__note {
  flex: 1 1 100%;
}

.friend-row__actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 6px;
}

.friend-row__remove {
  font-size: 11.5px;
}

@media (max-width: 768px) {
  .friends-search {
    align-items: stretch;
  }

  .friend-row {
    align-items: flex-start;
  }

  .friend-row__main {
    flex: 1 1 0;
    min-width: 0;
  }

  .friend-row__actions {
    width: auto;
    margin-left: auto;
    padding-left: 0;
    justify-content: flex-end;
  }

  .friend-row__actions .ui-btn {
    min-height: 40px;
  }

  .friend-row__name {
    max-width: 100%;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

@media (max-width: 389px) {
  .friends-search {
    flex-direction: column;
  }

  .friends-search .ui-btn {
    width: 100%;
  }
}
</style>
