<template>
  <div class="app-shell">
    <header class="app-topbar">
      <router-link to="/" class="app-topbar__brand">Campus Map</router-link>
      <nav class="app-topbar__nav">
        <router-link to="/" class="nav-item" :class="{ active: isActive('/') }">我的地图</router-link>
        <el-badge :value="requestCount" :hidden="!requestCount" :max="99">
          <router-link to="/friends" class="nav-item" :class="{ active: isActive('/friends') }" @click="requestCount = 0">
            好友
          </router-link>
        </el-badge>
        <router-link to="/activities" class="nav-item" :class="{ active: isActive('/activities') }">活动日程</router-link>
        <router-link to="/assistant" class="nav-item" :class="{ active: isActive('/assistant') }">AI 助手</router-link>
        <router-link to="/traces" class="nav-item" :class="{ active: isActive('/traces') }">执行追踪</router-link>
      </nav>
      <div class="app-topbar__right">
        <el-button text size="small" @click="logout">退出</el-button>
      </div>
    </header>
    <main class="app-shell__main">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { friendApi } from '../api'
import { connect, on } from '../composables/useChatSocket'

const route = useRoute()
const router = useRouter()
const requestCount = ref(0)
let removers = []

function isActive(to) {
  if (to === '/') return route.path === '/'
  return route.path.startsWith(to)
}

/** 好友申请角标：挂载时拉一次，WS 事件自增/回源；点进好友页清零 */
async function loadRequestCount() {
  try {
    const res = await friendApi.requests()
    requestCount.value = res.received.length
  } catch {
    // 角标失败不影响导航
  }
}

function logout() {
  localStorage.clear()
  router.push('/login')
}

onMounted(() => {
  connect()
  loadRequestCount()
  removers = [
    on('FRIEND_REQUEST', () => {
      requestCount.value += 1
      ElMessage.info('收到新的好友请求')
    }),
    on('FRIEND_ACCEPTED', () => loadRequestCount())
  ]
})

onUnmounted(() => {
  for (const off of removers) off()
})
</script>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.app-topbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 24px;
  height: 56px;
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid rgba(31, 56, 88, 0.08);
}
.app-topbar__brand {
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--el-color-primary);
  text-decoration: none;
  white-space: nowrap;
}
.app-topbar__nav {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 20px;
  overflow-x: auto;
}
.nav-item {
  color: rgba(0, 0, 0, 0.55);
  text-decoration: none;
  font-size: 14px;
  padding: 4px 2px;
  white-space: nowrap;
}
.nav-item.active {
  color: var(--el-color-primary);
  font-weight: 600;
}
.app-topbar__right {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}
/* 主区接管滚动：页面与工作台都按 100% 高度自适应 */
.app-shell__main {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
@media (max-width: 600px) {
  .app-topbar {
    gap: 12px;
    padding: 0 16px;
  }
  .app-topbar__nav {
    gap: 14px;
  }
}
</style>
