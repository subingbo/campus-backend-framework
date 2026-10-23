<template>
  <div
    class="app-shell"
    :class="{
      'app-shell--immersive': isImmersive,
      'app-shell--content-v2': isContentV2,
      'ui-v2': isContentV2,
      'app-shell--mobile-nav': route.meta.mobileNav === true
    }"
  >
    <header class="app-topbar">
      <div class="app-topbar__left">
        <router-link to="/" class="app-topbar__brand">
          <span v-if="isImmersive || isContentV2" class="app-topbar__mark">
            <AppIcon name="pin" :size="15" />
          </span>
          <span class="app-topbar__brand-text">Campus Map</span>
        </router-link>
        <nav class="app-topbar__nav">
          <router-link to="/" class="nav-item" :class="{ active: isActive('/') }">
            <AppIcon name="compass" :size="17" class="nav-icon" />
            <span class="nav-label">地图</span>
          </router-link>
          <span class="nav-badge-wrap">
            <router-link to="/messages" class="nav-item" :class="{ active: isActive('/messages') }">
              <AppIcon name="message" :size="17" class="nav-icon" />
              <span class="nav-label">消息</span>
            </router-link>
            <span v-if="summary.total" class="nav-badge" aria-label="未读消息">
              {{ summary.total > 99 ? '99+' : summary.total }}
            </span>
          </span>
          <router-link to="/activities" class="nav-item" :class="{ active: isActive('/activities') }">
            <AppIcon name="calendar" :size="17" class="nav-icon" />
            <span class="nav-label">日程</span>
          </router-link>
          <router-link to="/assistant" class="nav-item" :class="{ active: isActive('/assistant') }">
            <AppIcon name="sparkles" :size="17" class="nav-icon" />
            <span class="nav-label">AI 助手</span>
          </router-link>
        </nav>
      </div>
      <div class="app-topbar__right">
        <el-dropdown trigger="click" @command="onAccountCommand">
          <button type="button" class="account-trigger">
            <span class="account-avatar">{{ avatarText }}</span>
            <span class="account-name">{{ displayName }}</span>
            <AppIcon v-if="isImmersive || isContentV2" name="chevron-down" :size="14" class="account-chevron" />
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
import AppIcon from './ui/AppIcon.vue'

const route = useRoute()
const router = useRouter()
const isImmersive = computed(() => route.meta.immersive === true)
const isContentV2 = computed(() => route.meta.uiV2 === true)
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
.app-topbar__left {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 24px;
}
.app-topbar__brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--el-color-primary);
  text-decoration: none;
  white-space: nowrap;
}
.app-topbar__mark {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: var(--ui-accent);
  color: #fff;
}
.app-topbar__nav {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 20px;
  overflow-x: auto;
  scrollbar-width: none;
}
.app-topbar__nav::-webkit-scrollbar {
  display: none;
}
.account-chevron {
  color: var(--el-text-color-secondary);
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
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: rgba(0, 0, 0, 0.55);
  text-decoration: none;
  font-size: 14px;
  padding: 4px 2px;
  white-space: nowrap;
}
.nav-icon {
  display: none;
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

/* ===== 内容页：正常流顶栏 + 暖灰内容画布 ===== */
.app-shell--content-v2 .app-topbar {
  background: var(--ui-surface);
  border-bottom-color: var(--ui-line);
}

.app-shell--content-v2 .app-topbar__brand {
  color: var(--ui-ink);
}

.app-shell--content-v2 .app-topbar__nav {
  overflow: visible;
}

.app-shell--content-v2 .nav-item {
  color: var(--ui-ink-2);
}

.app-shell--content-v2 .nav-item:hover {
  color: var(--ui-ink);
}

.app-shell--content-v2 .nav-item.active {
  color: var(--ui-accent);
}

.app-shell--content-v2 .nav-badge {
  background: var(--ui-accent);
}

.app-shell--content-v2 .account-trigger {
  color: var(--ui-ink-2);
}

.app-shell--content-v2 .account-trigger:hover {
  background: var(--ui-surface-2);
}

.app-shell--content-v2 .account-avatar {
  background: var(--ui-accent-tint);
  color: var(--ui-accent);
}

/* ===== 地图沉浸模式：左右两个悬浮胶囊，中间留给地图 ===== */
.app-shell--immersive .app-topbar {
  position: absolute;
  z-index: 30;
  top: 12px;
  left: 0;
  right: 0;
  height: 52px;
  padding: 0 12px;
  gap: 12px;
  justify-content: space-between;
  background: transparent;
  border-bottom: 0;
  pointer-events: none;
}
.app-shell--immersive .app-topbar__left {
  flex: 0 1 auto;
  height: 52px;
  padding: 0 14px;
  gap: 18px;
  background: var(--ui-surface);
  border-radius: 16px;
  box-shadow: var(--ui-shadow-1);
  pointer-events: auto;
}
.app-shell--immersive .app-topbar__brand {
  color: var(--ui-ink);
}
.app-shell--immersive .app-topbar__nav {
  flex: 0 1 auto;
  gap: 2px;
  overflow: visible;
}
.app-shell--immersive .nav-item {
  height: 32px;
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  border-radius: 8px;
  color: var(--ui-ink-2);
  font-size: 13px;
}
.app-shell--immersive .nav-item:hover {
  background: var(--ui-surface-2);
  color: var(--ui-ink);
}
.app-shell--immersive .nav-item.active {
  background: var(--ui-surface-2);
  color: var(--ui-ink);
  font-weight: 600;
}
.app-shell--immersive .nav-badge-wrap {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.app-shell--immersive .nav-badge {
  position: static;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--ui-accent);
  font-size: 10px;
  line-height: 16px;
}
.app-shell--immersive .app-topbar__right {
  pointer-events: auto;
}
.app-shell--immersive .account-trigger {
  height: 44px;
  padding: 3px 12px 3px 4px;
  background: var(--ui-surface);
  border-radius: 999px;
  box-shadow: var(--ui-shadow-1);
  color: var(--ui-ink-2);
}
.app-shell--immersive .account-trigger:hover {
  background: var(--ui-surface-2);
}
.app-shell--immersive .account-avatar {
  background: var(--ui-accent-tint);
  color: var(--ui-accent);
}
.app-shell--immersive .account-name {
  font-size: 12.5px;
}
.app-shell--immersive .app-shell__main {
  flex: 1;
  height: 100vh;
  overflow: hidden;
}

/* 主区接管滚动：页面与工作台都按 100% 高度自适应 */
.app-shell__main {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
@media (max-width: 768px) {
  .app-shell--immersive .app-topbar {
    display: none;
  }
  .app-shell--immersive .app-shell__main {
    height: 100dvh;
  }

  .app-shell--content-v2.app-shell--mobile-nav .app-topbar {
    height: 56px;
    padding: 0 16px;
    gap: 12px;
  }

  .app-shell--content-v2.app-shell--mobile-nav .app-topbar__nav {
    flex: 1;
    gap: 12px;
    overflow: visible;
  }

  .app-shell--content-v2.app-shell--mobile-nav .nav-item {
    padding: 4px 0;
  }

  .app-shell--content-v2.app-shell--mobile-nav .app-shell__main {
    overflow-y: auto;
    overflow-x: hidden;
  }

  /* 首页 / AI 助手：保留一条紧凑导航栏，避免沉浸页面失去导航出口 */
  .app-shell--immersive.app-shell--mobile-nav .app-topbar {
    display: flex;
    position: static;
    height: 56px;
    padding: 0 16px;
    gap: 12px;
    background: var(--ui-surface);
    border-bottom: 1px solid var(--ui-line);
    pointer-events: auto;
  }
  .app-shell--immersive.app-shell--mobile-nav .app-topbar__left {
    flex: 1;
    height: auto;
    padding: 0;
    gap: 14px;
    background: transparent;
    border-radius: 0;
    box-shadow: none;
    pointer-events: auto;
  }
  .app-shell--immersive.app-shell--mobile-nav .app-topbar__nav {
    flex: 1;
    gap: 14px;
    overflow: hidden;
  }
  .app-shell--immersive.app-shell--mobile-nav .nav-item {
    height: 36px;
    padding: 0 2px;
    border-radius: 8px;
    color: var(--ui-ink-2);
    font-size: 14px;
  }
  .app-shell--immersive.app-shell--mobile-nav .nav-item.active {
    background: transparent;
    color: var(--ui-accent);
  }
  .app-shell--immersive.app-shell--mobile-nav .nav-badge {
    position: static;
  }
  .app-shell--immersive.app-shell--mobile-nav .app-topbar__right {
    pointer-events: auto;
  }
  .app-shell--immersive.app-shell--mobile-nav .account-trigger {
    height: 40px;
    padding: 3px 6px 3px 3px;
    background: transparent;
    box-shadow: none;
  }
  .app-shell--immersive.app-shell--mobile-nav .account-chevron {
    display: none;
  }
  .app-shell--immersive.app-shell--mobile-nav .app-shell__main {
    flex: 1;
    height: auto;
    min-height: 0;
    overflow: hidden;
  }
}

@media (max-width: 600px) {
  .nav-icon {
    display: inline-block;
  }
  .nav-label {
    display: none;
  }
}

@media (max-width: 389px) {
  .app-shell--immersive.app-shell--mobile-nav .app-topbar__brand-text {
    display: none;
  }
  .app-shell--content-v2 .app-topbar__brand-text {
    display: none;
  }
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
