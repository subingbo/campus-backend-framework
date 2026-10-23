<template>
  <div class="chat-panel">
    <div ref="listRef" class="chat-panel__list">
      <p v-if="!messages.length && !loading" class="chat-panel__empty">还没有消息，打个招呼吧</p>
      <div v-for="m in messages" :key="m.id" class="chat-msg" :class="{ mine: m.senderId === me && m.type !== 'AGENT' }">
        <span v-if="conversation.type !== 'PRIVATE' && m.senderId !== me" class="chat-msg__nick">
          {{ m.senderNickname }}
        </span>
        <div class="chat-msg__bubble" :class="{ agent: m.type === 'AGENT' }">
          <template v-if="m.type === 'AGENT'">
            <span class="chat-msg__ai"><AppIcon name="sparkles" :size="14" /></span>
            <MarkdownText :text="m.content" />
          </template>
          <template v-else>{{ m.content }}</template>
        </div>
        <span class="chat-msg__time">{{ formatTime(m.createdAt) }}</span>
      </div>
      <GroupPlanCard
        v-for="card in agentPlans"
        :key="card.planId || card.messageId"
        :card="card"
        :me="me"
        @view="openPlan"
        @continue="continuePlan"
      />
    </div>
    <div v-if="!connected" class="chat-panel__offline">
      连接已断开，消息会暂存并在恢复后自动发送
      <span v-if="pendingForCurrentConversation">（待发送 {{ pendingForCurrentConversation }} 条）</span>
    </div>
    <div v-else-if="pendingForCurrentConversation" class="chat-panel__pending">
      待发送 {{ pendingForCurrentConversation }} 条
    </div>
    <div v-if="typingText" class="chat-panel__typing">{{ typingText }}</div>
    <div v-if="agentRun" class="chat-panel__agent-status" :class="'is-' + agentRun.status.toLowerCase()">
      <span v-if="agentRun.status === 'RUNNING'">
        AI 正在规划<template v-if="agentRun.stage">：{{ agentRun.stage }}</template>
        （已调用 {{ agentRun.toolCount }} 个工具，{{ agentElapsed }}s）
      </span>
      <span v-else-if="agentRun.status === 'TOOL'">
        AI 调用工具 {{ agentRun.currentTool || '…' }}（{{ agentRun.toolCount }} 个，{{ agentElapsed }}s）
      </span>
      <span v-else-if="agentRun.status === 'CANCELLING'">正在取消本轮规划…</span>
      <span v-else-if="agentRun.status === 'CANCELLED'">已取消本轮规划</span>
      <span v-else-if="agentRun.status === 'FAILED'">规划失败：{{ agentRun.error || '请稍后重试' }}</span>
      <span v-else-if="agentRun.status === 'BUSY'">本群已有 AI 规划在进行，请稍候</span>
      <span v-else-if="agentRun.status === 'DONE'">本轮规划已完成</span>
      <el-button
        v-if="agentRun.status === 'RUNNING' || agentRun.status === 'TOOL'"
        size="small"
        text
        @click="cancelAgent"
      >取消</el-button>
    </div>
    <div class="chat-panel__input">
      <el-button
        v-if="conversation.type === 'GROUP'"
        class="chat-panel__ai-btn"
        size="small"
        @click="armAi"
      >问 AI 规划</el-button>
      <el-input
        ref="inputRef"
        v-model="draft"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="输入消息，Enter 发送"
        @keydown.enter.exact.prevent="submit"
        @input="notifyTyping"
      />
      <el-button type="primary" @click="submit">发送</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { chatApi } from '../api'
import {
  connected,
  myUserId,
  on,
  pendingForConversation,
  send,
  syncOutbox,
  takeFailedMessage
} from '../composables/useChatSocket'
import { mergeMessages } from '../composables/chatOutbox'
import { useMessageSummary } from '../composables/useMessageSummary'
import MarkdownText from './MarkdownText.vue'
import GroupPlanCard from './GroupPlanCard.vue'
import AppIcon from './ui/AppIcon.vue'

const props = defineProps({
  conversation: { type: Object, required: true }
})

const router = useRouter()
const { refreshSoon: refreshSummarySoon } = useMessageSummary()
const me = myUserId()
const messages = ref([])
const draft = ref('')
const loading = ref(false)
const typingText = ref('')
const listRef = ref(null)
const inputRef = ref(null)
const agentPlans = ref([])
const agentRun = ref(null)
const agentElapsed = ref(0)
let typingTimer = null
let lastTypingSentAt = 0
let unreadRemovers = []
let agentStartedAt = 0
let agentTimer = null
let agentClearTimer = null

const pendingForCurrentConversation = computed(() => pendingForConversation(props.conversation.id))

function normalizePlanCard(item) {
  return {
    ...item,
    plan: item.plan || item
  }
}

function mergePlanCards(items) {
  const byId = new Map(agentPlans.value.map((card) => [card.planId, card]))
  for (const item of items || []) {
    if (!item?.planId) continue
    byId.set(item.planId, normalizePlanCard(item))
  }
  agentPlans.value = [...byId.values()].slice(-10)
}

function upsertPlanCard(frame) {
  if (!frame?.planId) return
  const view = frame.plan || {}
  mergePlanCards([{
    ...view,
    planId: frame.planId,
    messageId: frame.messageId,
    askerId: frame.askerId,
    plan: { ...view, askerId: frame.askerId, askerNickname: view.askerNickname || '' }
  }])
}

function openPlan(card) {
  router.push(`/conversations/${props.conversation.id}/plans/${card.planId}`)
}

async function continuePlan(card) {
  if (!card?.planId) return
  try {
    const handoff = await chatApi.agentPlanHandoff(props.conversation.id, card.planId)
    router.push({ path: '/assistant', query: { chat: handoff.aiConversationId } })
  } catch (e) {
    ElMessage.error(e.message || '无法继续规划：仅发起人可编辑该草稿')
  }
}

watch(
  () => props.conversation.id,
  () => {
    // 换会话必须清掉上一会话的卡片与运行状态
    agentPlans.value = []
    agentRun.value = null
    stopAgentTimer()
    clearTimeout(agentClearTimer)
    // 刷新/切换会话时先同步未发送队列，断线状态下也能显示待发送数量
    syncOutbox()
    load()
  },
  { immediate: true }
)

// 订阅当前会话相关帧；组件销毁或换会话时重建订阅
watch(
  () => props.conversation.id,
  (newId, oldId) => {
    for (const off of unreadRemovers) off()
    unreadRemovers = [
      on('CHAT_MESSAGE', (frame) => {
        const msg = frame.message
        if (msg.conversationId !== props.conversation.id) return
        if (!messages.value.some((m) => m.id === msg.id)) {
          messages.value.push(msg)
          scrollBottom()
        }
        if (msg.senderId !== me) {
          markConversationRead()
        }
      }),
      on('TYPING', (frame) => {
        if (frame.conversationId !== props.conversation.id || frame.userId === me) return
        typingText.value = `${frame.nickname} 正在输入…`
        clearTimeout(typingTimer)
        typingTimer = setTimeout(() => {
          typingText.value = ''
        }, 2500)
      }),
      on('ERROR', (frame) => {
        const failed = frame.clientMsgId ? takeFailedMessage(frame.clientMsgId) : null
        if (failed) {
          if (!draft.value) draft.value = failed.content
          ElMessage.error(frame.message || '发送失败，草稿已恢复')
          return
        }
        ElMessage.error(frame.message || '发送失败')
      }),
      on('SOCKET_OPEN', () => {
        // 重连后补拉断线期间的消息，并按 id 合并避免覆盖刚发出的消息
        reloadAndMerge()
      }),
      on('AGENT_STATUS', (frame) => {
        if (frame.conversationId !== props.conversation.id) return
        agentRun.value = frame
        if (frame.elapsedMs != null) {
          agentStartedAt = Date.now() - Number(frame.elapsedMs || 0)
          agentElapsed.value = Math.max(0, Math.floor(Number(frame.elapsedMs || 0) / 1000))
        }
        if (['RUNNING', 'TOOL', 'CANCELLING'].includes(frame.status)) {
          clearTimeout(agentClearTimer)
          startAgentTimer()
        } else {
          stopAgentTimer()
          clearTimeout(agentClearTimer)
          // 终态保留几秒让用户看到结果，再清理状态条
          agentClearTimer = setTimeout(() => {
            agentRun.value = null
          }, 4000)
        }
      }),
      on('AGENT_PLAN_CARD', (frame) => {
        if (frame.conversationId !== props.conversation.id) return
        upsertPlanCard(frame)
        scrollBottom()
      })
    ]
    if (oldId !== undefined) scrollBottom()
  },
  { immediate: true }
)

onUnmounted(() => {
  for (const off of unreadRemovers) off()
  clearTimeout(typingTimer)
  clearTimeout(agentClearTimer)
  stopAgentTimer()
})

function startAgentTimer() {
  if (agentTimer) return
  agentTimer = setInterval(() => {
    if (!agentRun.value || !agentStartedAt) return
    agentElapsed.value = Math.max(0, Math.floor((Date.now() - agentStartedAt) / 1000))
  }, 1000)
}

function stopAgentTimer() {
  if (agentTimer) {
    clearInterval(agentTimer)
    agentTimer = null
  }
}

function armAi() {
  if (!draft.value.startsWith('@AI')) {
    draft.value = draft.value ? `@AI ${draft.value}` : '@AI '
  }
  inputRef.value?.focus?.()
}

function cancelAgent() {
  if (!agentRun.value?.runId) return
  send({
    type: 'AGENT_CANCEL',
    conversationId: props.conversation.id,
    runId: agentRun.value.runId
  })
}

/** 打开/停留在会话即视为已读：清服务端未读，并等 READ 回执后刷新顶栏角标 */
function markConversationRead() {
  send({ type: 'READ', conversationId: props.conversation.id })
  refreshSummarySoon()
}

async function load() {
  loading.value = true
  try {
    const [list, plans] = await Promise.all([
      chatApi.messages(props.conversation.id),
      chatApi.agentPlans(props.conversation.id).catch(() => [])
    ])
    messages.value = list
    agentPlans.value = (plans || []).map(normalizePlanCard).slice(-10)
    scrollBottom()
    markConversationRead()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function reloadAndMerge() {
  try {
    const [list, plans] = await Promise.all([
      chatApi.messages(props.conversation.id),
      chatApi.agentPlans(props.conversation.id).catch(() => [])
    ])
    messages.value = mergeMessages(messages.value, list)
    mergePlanCards(plans)
    scrollBottom()
    markConversationRead()
  } catch {
    // 重连补拉失败不打扰用户，下一轮重连会再试
  }
}

function submit() {
  const content = draft.value.trim()
  if (!content) return
  // 消息以服务端 CHAT_MESSAGE 广播为准；断线时先入待发送队列，ACK 后才算送达
  const result = send({ type: 'CHAT_SEND', conversationId: props.conversation.id, content })
  if (result === 'failed') {
    ElMessage.error('发送失败，草稿已保留')
    return
  }
  draft.value = ''
  if (result === 'queued') {
    ElMessage.info('连接中断，消息已暂存，恢复后自动发送')
  }
}

let typingThrottle = 0
function notifyTyping() {
  const now = Date.now()
  if (now - typingThrottle > 2000) {
    typingThrottle = now
    send({ type: 'TYPING', conversationId: props.conversation.id })
  }
}

function scrollBottom() {
  nextTick(() => {
    const el = listRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function toDate(value) {
  // 后端 LocalDateTime 可能序列化为 ISO 字符串或 [年,月,日,时,分,秒] 数组
  if (Array.isArray(value)) {
    return new Date(value[0], (value[1] || 1) - 1, value[2] || 1, value[3] || 0, value[4] || 0, value[5] || 0)
  }
  return new Date(value)
}

function formatTime(value) {
  if (!value) return ''
  const d = toDate(value)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  if (d.toDateString() === now.toDateString()) {
    return hm
  }
  return `${d.getMonth() + 1}/${d.getDate()} ${hm}`
}
</script>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.chat-panel__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 2px;
}
.chat-panel__empty {
  text-align: center;
  color: rgba(0, 0, 0, 0.45);
  font-size: 13px;
  padding: 24px 0;
}
.chat-msg {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  margin-bottom: 10px;
  max-width: 82%;
}
.chat-msg.mine {
  align-self: flex-end;
  align-items: flex-end;
}
.chat-msg__nick {
  font-size: 11px;
  color: rgba(0, 0, 0, 0.45);
  margin-bottom: 2px;
}
.chat-msg__bubble {
  padding: 7px 11px;
  border-radius: 12px;
  background: #f2f3f7;
  color: rgba(0, 0, 0, 0.9);
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
  white-space: pre-wrap;
}
.chat-msg.mine .chat-msg__bubble {
  background: var(--el-color-primary);
  color: #fff;
}
.chat-msg__time {
  font-size: 10px;
  color: rgba(0, 0, 0, 0.35);
  margin-top: 2px;
}
.chat-panel__typing {
  font-size: 11px;
  color: rgba(0, 0, 0, 0.45);
  padding: 0 4px 2px;
}
.chat-panel__input {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid #f0f1f5;
}
.chat-panel__input .el-input {
  flex: 1;
}

/* —— P2 群聊 AI 规划卡片 —— */
.chat-msg__bubble.agent {
  background: rgba(64, 158, 255, 0.1);
  border: 1px dashed rgba(64, 158, 255, 0.4);
  color: inherit;
}
.chat-msg__ai {
  margin-right: 2px;
}
.chat-agent-card {
  margin: 8px 0;
  padding: 10px 12px;
  background: rgba(64, 158, 255, 0.07);
  border: 1px solid rgba(64, 158, 255, 0.25);
  border-radius: 10px;
  font-size: 12px;
}
.chat-agent-card h4 {
  margin: 0 0 6px;
  font-size: 13px;
}
.chat-agent-card p {
  margin: 2px 0;
  color: rgba(0, 0, 0, 0.72);
}
.chat-agent-card__stop .src {
  display: inline-block;
  margin-right: 4px;
  padding: 0 4px;
  border-radius: 4px;
  font-size: 10px;
  background: rgba(64, 158, 255, 0.15);
  color: #2979cc;
}
.chat-agent-card__stop .src-personal-map {
  background: rgba(103, 194, 58, 0.15);
  color: #3f9b1e;
}
.chat-agent-card__stop .src-amap-poi {
  background: rgba(144, 147, 153, 0.15);
  color: rgba(0, 0, 0, 0.45);
}
.chat-agent-card__routebadge {
  margin-left: 4px;
  padding: 0 4px;
  border-radius: 4px;
  font-size: 10px;
  background: rgba(230, 162, 60, 0.18);
  color: #c0781c;
}
.chat-agent-card__routebadge.ok {
  background: rgba(103, 194, 58, 0.16);
  color: #3f9b1e;
}
.chat-agent-card__seg {
  color: rgba(0, 0, 0, 0.45);
  font-size: 11px;
}
.chat-agent-card__cov,
.chat-agent-card__reason {
  color: rgba(0, 0, 0, 0.55);
}
.chat-agent-card__badge {
  display: inline-block;
  margin: 2px 0 4px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 10px;
}
.chat-agent-card__badge.ok {
  background: rgba(103, 194, 58, 0.16);
  color: #3f9b1e;
}
.chat-agent-card__badge.tentative {
  background: rgba(230, 162, 60, 0.18);
  color: #c0781c;
}
.chat-agent-card__badge.conflict {
  background: rgba(245, 108, 108, 0.16);
  color: #c0392b;
}
.chat-agent-card__nav {
  margin-left: 4px;
  border: 1px solid rgba(64, 158, 255, 0.35);
  background: #fff;
  color: #2979cc;
  border-radius: 999px;
  font-size: 10px;
  line-height: 16px;
  padding: 0 6px;
  cursor: pointer;
}
.chat-agent-card__people {
  margin-top: 6px;
  border-top: 1px dashed rgba(64, 158, 255, 0.25);
  padding-top: 6px;
}
.chat-agent-card__people-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  font-size: 11px;
  color: rgba(0, 0, 0, 0.65);
}
.chat-agent-card__people-conflict {
  color: #c0392b;
  font-weight: 600;
}
.chat-agent-card__people-chevron {
  margin-left: auto;
  color: #2979cc;
}
.chat-agent-card__member {
  display: inline-block;
  margin-right: 6px;
}
.chat-agent-card__member em {
  font-style: normal;
  opacity: 0.6;
  margin-left: 3px;
}
.chat-agent-card__conflict {
  padding: 4px 6px;
  margin-top: 4px;
  border-radius: 6px;
  background: #fff7e6;
  color: #ad4e00;
  font-size: 11px;
}
.chat-agent-card__warn {
  color: #c0781c;
  font-size: 11px;
}
.chat-agent-card__hint {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  color: rgba(0, 0, 0, 0.42);
}
.chat-panel__offline {
  padding: 5px 10px;
  background: #fef0f0;
  color: #c45656;
  font-size: 12px;
  text-align: center;
}
.chat-panel__pending {
  padding: 4px 10px;
  background: #fdf6ec;
  color: #b88230;
  font-size: 12px;
  text-align: center;
}
.chat-panel__agent-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 10px;
  background: #f4f4f5;
  color: #606266;
  font-size: 12px;
}
.chat-panel__agent-status.is-running,
.chat-panel__agent-status.is-tool {
  background: #ecf5ff;
  color: #337ecc;
}
.chat-panel__agent-status.is-failed {
  background: #fef0f0;
  color: #c45656;
}
.chat-panel__agent-status.is-cancelled,
.chat-panel__agent-status.is-cancelling {
  background: #fdf6ec;
  color: #b88230;
}
.chat-panel__ai-btn {
  flex-shrink: 0;
}
@media (max-width: 600px) {
  .chat-msg {
    max-width: 92%;
  }
  .chat-panel__input {
    flex-wrap: wrap;
    gap: 6px;
    padding-bottom: env(safe-area-inset-bottom, 0);
  }
  .chat-panel__ai-btn {
    flex: 1 1 100%;
  }
  .chat-panel__input .el-input {
    flex: 1 1 0;
    min-width: 0;
  }
  .chat-panel__agent-status {
    flex-wrap: wrap;
    align-items: flex-start;
  }
  .chat-panel__offline,
  .chat-panel__pending {
    white-space: normal;
  }
}
</style>
