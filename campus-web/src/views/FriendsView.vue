<template>
  <div class="page">
    <div class="hero">
      <h1>好友</h1>
      <p>和朋友一起维护校园地图</p>
    </div>

    <div class="section">
      <el-input
        v-model="keyword"
        placeholder="搜索用户名或昵称，回车搜索"
        clearable
        @keyup.enter="doSearch"
      >
        <template #append>
          <el-button @click="doSearch">搜索</el-button>
        </template>
      </el-input>
      <div v-for="u in searchResults" :key="u.userId" class="row">
        <div class="row__main">
          <strong>{{ u.nickname || u.username }}</strong>
          <span class="hint">@{{ u.username }}</span>
        </div>
        <el-button v-if="!u.friend && !u.requested" size="small" type="primary" @click="addFriend(u)">
          加好友
        </el-button>
        <el-tag v-else size="small" type="info" effect="plain">
          {{ u.friend ? '已是好友' : '已申请' }}
        </el-tag>
      </div>
      <p v-if="searched && !searchResults.length" class="hint empty">没有匹配的用户</p>
    </div>

    <div v-if="requests.received.length" class="section">
      <h3>收到的好友请求</h3>
      <div v-for="r in requests.received" :key="r.id" class="row">
        <div class="row__main">
          <strong>{{ r.fromNickname }}</strong>
          <span class="hint">@{{ r.fromUsername }}</span>
          <span v-if="r.message" class="hint">「{{ r.message }}」</span>
        </div>
        <div class="row__actions">
          <el-button size="small" type="primary" @click="accept(r)">接受</el-button>
          <el-button size="small" @click="reject(r)">拒绝</el-button>
        </div>
      </div>
    </div>

    <div class="section">
      <h3>我的好友</h3>
      <p v-if="!friends.length" class="hint empty">还没有好友，先在上面搜索添加吧</p>
      <div v-for="f in friends" :key="f.userId" class="row">
        <div class="row__main">
          <span class="dot" :class="{ on: f.online }" />
          <strong>{{ f.nickname || f.username }}</strong>
          <span class="hint">@{{ f.username }}</span>
          <span class="hint">{{ f.online ? '在线' : '离线' }}</span>
        </div>
        <div class="row__actions">
          <el-button size="small" type="primary" plain @click="openChat(f)">私聊</el-button>
          <el-button size="small" type="danger" text @click="removeFriend(f)">删除</el-button>
        </div>
      </div>
    </div>
  </div>

  <el-dialog v-model="chatOpen" :title="chatPeer" width="420px">
    <ChatPanel v-if="activeConversation" :conversation="activeConversation" style="height: 380px" />
  </el-dialog>
</template>

<script setup>
// 全局顶栏（品牌/导航/退出/好友申请角标）由路由壳 AppShell 提供
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chatApi, friendApi } from '../api'
import { on } from '../composables/useChatSocket'
import ChatPanel from '../components/ChatPanel.vue'

const keyword = ref('')
const searched = ref(false)
const searchResults = ref([])
const friends = ref([])
const requests = ref({ received: [], sent: [] })
const chatOpen = ref(false)
const chatPeer = ref('')
const activeConversation = ref(null)
let removers = []

onMounted(async () => {
  await Promise.all([loadFriends(), loadRequests()])
  removers = [
    // 顶部角标提示在 AppShell 统一弹出，这里只负责刷新本页数据
    on('FRIEND_REQUEST', () => loadRequests()),
    on('FRIEND_ACCEPTED', (frame) => {
      ElMessage.success(`${frame.nickname} 接受了你的好友请求`)
      loadFriends()
    }),
    on('PRESENCE', (frame) => {
      const friend = friends.value.find((f) => f.userId === frame.userId)
      if (friend) {
        friend.online = frame.online
      }
    })
  ]
})

onUnmounted(() => {
  for (const off of removers) off()
})

async function loadFriends() {
  try {
    friends.value = await friendApi.list()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function loadRequests() {
  try {
    requests.value = await friendApi.requests()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doSearch() {
  if (!keyword.value.trim()) {
    searchResults.value = []
    searched.value = false
    return
  }
  try {
    searchResults.value = await friendApi.search(keyword.value.trim())
    searched.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function addFriend(u) {
  try {
    await friendApi.sendRequest({ userId: u.userId })
    ElMessage.success(`已向 ${u.nickname || u.username} 发出好友请求`)
    u.requested = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function accept(r) {
  try {
    await friendApi.accept(r.id)
    ElMessage.success(`已添加 ${r.fromNickname} 为好友`)
    await Promise.all([loadFriends(), loadRequests()])
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function reject(r) {
  try {
    await friendApi.reject(r.id)
    await loadRequests()
  } catch (e) {
    ElMessage.error(e.message)
  }
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
  try {
    await ElMessageBox.confirm(`确定删除好友 ${f.nickname || f.username}？私聊会话将不可再发送`, '确认')
    await friendApi.remove(f.userId)
    ElMessage.success('已删除')
    await loadFriends()
  } catch (e) {
    if (e !== 'cancel' && e?.message) {
      ElMessage.error(e.message)
    }
  }
}
</script>

<style scoped>
.page {
  max-width: 720px;
  margin: 0 auto;
  padding: 0 32px 56px;
}
.hero {
  margin: 40px 0 24px;
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
  margin-bottom: 28px;
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
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c9ced6;
  flex-shrink: 0;
}
.dot.on {
  background: #2ba471;
}
@media (max-width: 600px) {
  .page {
    padding: 0 16px 40px;
  }
  .hero {
    margin: 24px 0 20px;
  }
  .hero h1 {
    font-size: 24px;
  }
}
</style>
