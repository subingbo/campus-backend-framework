import { ref } from 'vue'
import { notificationApi } from '../api'

/**
 * 全局消息角标（模块级单例）：
 * total 只由后端 summary 的“未读通知 + 有未读的会话数”决定，
 * 好友申请/活动邀请只做各自 tab 的待办角标，避免前端二次相加造成重复计数。
 */
const EMPTY = Object.freeze({
  unreadNotifications: 0,
  unreadChatConversations: 0,
  pendingFriendRequests: 0,
  pendingActivityInvites: 0,
  total: 0
})

const summary = ref({ ...EMPTY })
let refreshing = null
let queued = false
let debounceTimer = null
let generation = 0

export function resetMessageSummary() {
  generation += 1
  summary.value = { ...EMPTY }
  queued = false
  clearTimeout(debounceTimer)
}

export function refreshMessageSummary() {
  if (refreshing) {
    queued = true
    return refreshing
  }
  const requestedGeneration = generation
  refreshing = notificationApi.summary()
    .then((data) => {
      // 登出/切号后忽略上一会话迟到的响应
      if (requestedGeneration === generation) {
        summary.value = { ...EMPTY, ...(data || {}) }
      }
    })
    .catch(() => {
      // 角标失败不影响页面主流程
    })
    .finally(() => {
      refreshing = null
      if (queued) {
        queued = false
        refreshMessageSummary()
      }
    })
  return refreshing
}

export function scheduleMessageSummaryRefresh(delay = 250) {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    debounceTimer = null
    refreshMessageSummary()
  }, delay)
}

export function useMessageSummary() {
  return {
    summary,
    refresh: refreshMessageSummary,
    refreshSoon: scheduleMessageSummaryRefresh,
    reset: resetMessageSummary
  }
}
