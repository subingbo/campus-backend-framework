<template>
  <div class="app-shell">
    <header class="app-topbar">
      <router-link to="/" class="app-topbar__brand">Campus Map</router-link>
      <nav class="app-topbar__nav">
        <router-link to="/" class="nav-item" :class="{ active: isActive('/') }">地图</router-link>
        <span class="nav-badge-wrap">
          <router-link to="/messages" class="nav-item" :class="{ active: isActive('/messages') }">
            消息
          </router-link>
          <span v-if="summary.total" class="nav-badge" aria-label="未读消息">
            {{ summary.total > 99 ? '99+' : summary.total }}
          </span>
        </span>
        <router-link to="/activities" class="nav-item" :class="{ active: isActive('/activities') }">日程</router-link>
        <router-link to="/assistant" class="nav-item" :class="{ active: isActive('/assistant') }">AI 助手</router-link>
      </nav>
      <div class="app-topbar__right">
        <el-dropdown trigger="click" @command="onAccountCommand">
          <button type="button" class="account-trigger">
            <span class="account-avatar">{{ avatarText }}</span>
            <span class="account-name">{{ displayName }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>@{{ username || 'account' }}</el-dropdown-item>
              <el-dropdown-item command="friends">好友</el-dropdown-item>
              <el-dropdown-item command="developer">开发者视图</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>
    <main class="app-shell__main">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userApi } from '../api'
import { clearSession, getAccessClaims } from '../api/authSession'
import { clearOutboxForCurrentUser, connect, disconnect, on } from '../composables/useChatSocket'
import { useMessageSummary } from '../composables/useMessageSummary'

const route = useRoute()
const router = useRouter()
const { summary, refresh: refreshSummary, refreshSoon, reset: resetSummary } = useMessageSummary()
const profile = ref({ username: '', nickname: '' })
const username = computed(() => profile.value.username)
const displayName = computed(() => profile.value.nickname || profile.value.username || '账号')
const avatarText = computed(() => (displayName.value || '账').slice(0, 1).toUpperCase())
let removers = []

function isActive(to) {
  if (to === '/') return route.path === '/'
  return route.path.startsWith(to)
}

async function loadProfile() {
  const claims = getAccessClaims()
  profile.value = { username: claims?.username || '', nickname: '' }
  try {
    const me = await userApi.me()
    profile.value = {
      username: me.username || profile.value.username,
      nickname: me.nickname || ''
    }
  } catch {
    // 资料加载失败只用 token 里的用户名兜底，不阻塞导航
  }
}

function onAccountCommand(command) {
  if (command === 'friends') {
    router.push('/friends')
  } else if (command === 'developer') {
    router.push('/traces')
  } else if (command === 'logout') {
    logout()
  }
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
  loadProfile()
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
.account-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgba(0, 0, 0, 0.7);
  cursor: pointer;
}
.account-trigger:hover {
  background: rgba(31, 56, 88, 0.06);
}
.account-avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 12px;
}
.account-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
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
  .account-name {
    display: none;
  }
}
</style>
