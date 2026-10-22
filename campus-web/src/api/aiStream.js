import { createStreamAssistantChat } from './sseStream.js'
import { forceLogin, refreshCoordinator } from './http'
import { getAccessToken } from './authSession'

export { createStreamAssistantChat }

/**
 * AI 助手 SSE 流式封装：EventSource 不支持自定义 Authorization 头，
 * 因此走 fetch + ReadableStream 自行解析 data 协议（event: name / data: ...）。
 * 401 复用 axios 同一套 refreshCoordinator，只刷新并重试一次；
 * 已开始消费事件后不整体重发，避免重复生成/重复落库。
 */
export const streamAssistantChat = createStreamAssistantChat({
  fetchImpl: (...args) => fetch(...args),
  getToken: () => getAccessToken() || '',
  refresh: () => refreshCoordinator.refreshOnce(),
  onAuthLost: () => forceLogin()
})
