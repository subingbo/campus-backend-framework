<template>
  <div class="chat-panel">
    <div ref="listRef" class="chat-panel__list">
      <p v-if="!messages.length && !loading" class="chat-panel__empty">还没有消息，打个招呼吧</p>
      <div v-for="m in messages" :key="m.id" class="chat-msg" :class="{ mine: m.senderId === me }">
        <span v-if="conversation.type !== 'PRIVATE' && m.senderId !== me" class="chat-msg__nick">
          {{ m.senderNickname }}
        </span>
        <div class="chat-msg__bubble" :class="{ agent: m.type === 'AGENT' }">
          <template v-if="m.type === 'AGENT'">
            <span class="chat-msg__ai">🤖</span>
            <MarkdownText :text="m.content" />
          </template>
          <template v-else>{{ m.content }}</template>
        </div>
        <span class="chat-msg__time">{{ formatTime(m.createdAt) }}</span>
      </div>
      <div v-if="agentPlan" class="chat-agent-card">
        <h4>🧭 {{ agentPlan.title || 'AI 规划（未确认）' }}</h4>
        <span v-if="agentPlan.constraintStatus === 'TENTATIVE'" class="chat-agent-card__badge tentative">
          暂定：含未授权忙闲成员
        </span>
        <span v-else-if="agentPlan.constraintStatus === 'FEASIBLE'" class="chat-agent-card__badge ok">
          忙闲已核验
        </span>
        <p v-for="(s, i) in (agentPlan.stops || []).slice(0, 6)" :key="i" class="chat-agent-card__stop">
          <span class="src" :class="sourceClass(s.source)">{{ sourceLabel(s.source) }}</span>
          {{ s.seq || i + 1 }}. {{ s.name }}
          <span v-if="s.startTime">（{{ formatTime(s.startTime) }}-{{ formatTime(s.endTime) }}<template
            v-if="s.stayMinutes">，停留 {{ s.stayMinutes }} 分钟</template>）</span>
        </p>
        <p v-for="(seg, i) in segmentList" :key="'seg' + i" class="chat-agent-card__seg">
          ↳ {{ modeLabel(seg.transportMode) }} {{ seg.durationMinutes }} 分钟
        </p>
        <p v-if="agentPlan.totalTravelMinutes">🚦 全程交通约 {{ agentPlan.totalTravelMinutes }} 分钟
          <span v-if="agentPlan.routeConfidence === 'ESTIMATED'" class="chat-agent-card__routebadge">估算（未验证）</span>
          <span v-else-if="agentPlan.routeConfidence === 'AMAP'" class="chat-agent-card__routebadge ok">高德实测</span>
        </p>
        <p v-if="coverageNote" class="chat-agent-card__cov">{{ coverageNote }}</p>
        <div v-if="agentPlan.reason" class="chat-agent-card__reason">💡 <MarkdownText :text="agentPlan.reason" /></div>
        <span class="chat-agent-card__hint">
          已生成规划草稿；到页面顶部「AI 助手」可编辑并保存为活动。含未授权忙闲的方案仅为暂定，需相关成员确认。
        </span>
      </div>
    </div>
    <div v-if="typingText" class="chat-panel__typing">{{ typingText }}</div>
    <div class="chat-panel__input">
      <el-input
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
import { chatApi } from '../api'
import { myUserId, on, send } from '../composables/useChatSocket'
import MarkdownText from './MarkdownText.vue'

const props = defineProps({
  conversation: { type: Object, required: true }
})

const me = myUserId()
const messages = ref([])
const draft = ref('')
const loading = ref(false)
const typingText = ref('')
const listRef = ref(null)
const agentPlan = ref(null)
let typingTimer = null
let lastTypingSentAt = 0
let unreadRemovers = []

// P1.5 卡片扩展：来源徽标 / 交通段 / 忙闲覆盖
const segmentList = computed(() => (agentPlan.value?.routeSegments || []).slice(0, 8))
const coverageNote = computed(() => {
  const coverage = agentPlan.value?.availabilityCoverage || []
  if (!coverage.length) return ''
  const known = coverage.filter((c) => c.status === 'KNOWN').length
  const unknown = coverage.filter((c) => c.status === 'UNKNOWN').length
  if (!unknown) return `已核验 ${known} 位成员忙闲`
  return `忙闲已核验 ${known} 人，${unknown} 人未授权（方案暂定）`
})

function sourceLabel(source) {
  if (source === 'GROUP_MAP') return '共建'
  if (source === 'PERSONAL_MAP') return '收藏'
  if (source === 'AMAP_POI') return 'POI'
  return ''
}

function sourceClass(source) {
  return source ? `src-${String(source).toLowerCase().replace('_', '-')}` : ''
}

function modeLabel(mode) {
  if (mode === 'RIDE') return '骑行'
  if (mode === 'DRIVE') return '驾车'
  if (mode === 'TRANSIT') return '公交'
  return '步行'
}

watch(
  () => props.conversation.id,
  () => {
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
          send({ type: 'READ', conversationId: props.conversation.id })
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
        ElMessage.error(frame.message || '发送失败')
      }),
      on('AGENT_PLAN_CARD', (frame) => {
        if (frame.conversationId !== props.conversation.id) return
        agentPlan.value = frame.plan
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
})

async function load() {
  loading.value = true
  try {
    messages.value = await chatApi.messages(props.conversation.id)
    scrollBottom()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function submit() {
  const content = draft.value.trim()
  if (!content) return
  // 消息以服务端 CHAT_MESSAGE 广播为准，这里只负责发出
  send({ type: 'CHAT_SEND', conversationId: props.conversation.id, content })
  draft.value = ''
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
.chat-agent-card__hint {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  color: rgba(0, 0, 0, 0.42);
}
</style>
