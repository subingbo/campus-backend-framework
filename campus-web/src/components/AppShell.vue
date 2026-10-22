<template>
  <div class="app-shell">
    <header class="app-topbar">
      <router-link to="/" class="app-topbar__brand">Campus Map</router-link>
      <nav class="app-topbar__nav">
        <router-link to="/" class="nav-item" :class="{ active: isActive('/') }">我的地图</router-link>
        <span class="nav-badge-wrap">
          <router-link to="/messages" class="nav-item" :class="{ active: isActive('/messages') }">
            消息
          </router-link>
          <span v-if="summary.total" class="nav-badge" aria-label="未读消息">
            {{ summary.total > 99 ? '99+' : summary.total }}
          </span>
        </span>
        <router-link to="/friends" class="nav-item" :class="{ active: isActive('/friends') }">好友</router-link>
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
import { onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { clearSession } from '../api/authSession'
import { clearOutboxForCurrentUser, connect, disconnect, on } from '../composables/useChatSocket'
import { useMessageSummary } from '../composables/useMessageSummary'

const route = useRoute()
const router = useRouter()
const { summary, refresh: refreshSummary, refreshSoon, reset: resetSummary } = useMessageSummary()
let removers = []

function isActive(to) {
  if (to === '/') return route.path === '/'
  return route.path.startsWith(to)
}

function logout() {
  clearOutboxForCurrentUser()
  disconnect()
  resetSummary()
  clearSession()
  router.push('/login')
}

onMounted(() => {
  connect()
  refreshSummary()
  removers = [
    // 持久通知/未读变化：以服务端 summary 为准刷新角标，避免前端累加导致重复计数
    on('NOTIFICATION', () => refreshSoon()),
    on('UNREAD', () => refreshSoon()),
    on('FRIEND_REQUEST', () => {
      ElMessage.info('收到新的好友请求')
      refreshSoon()
    }),
    on('FRIEND_ACCEPTED', () => refreshSoon()),
    on('ACTIVITY_INVITED', () => refreshSoon()),
    on('ACTIVITY_CANCELLED', () => refreshSoon()),
    on('ACTIVITY_REMINDER', () => refreshSoon())
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
.nav-badge-wrap {
  position: relative;
  display: inline-flex;
}
.nav-badge {
  position: absolute;
  top: -6px;
  right: -12px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: #f56c6c;
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
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
