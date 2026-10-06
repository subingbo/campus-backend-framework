<template>
  <div class="chat-panel">
    <div ref="listRef" class="chat-panel__list">
      <p v-if="!messages.length && !loading" class="chat-panel__empty">还没有消息，打个招呼吧</p>
      <div v-for="m in messages" :key="m.id" class="chat-msg" :class="{ mine: m.senderId === me }">
        <span v-if="conversation.type !== 'PRIVATE' && m.senderId !== me" class="chat-msg__nick">
          {{ m.senderNickname }}
        </span>
        <div class="chat-msg__bubble">{{ m.content }}</div>
        <span class="chat-msg__time">{{ formatTime(m.createdAt) }}</span>
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
import { nextTick, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { chatApi } from '../api'
import { myUserId, on, send } from '../composables/useChatSocket'

const props = defineProps({
  conversation: { type: Object, required: true }
})

const me = myUserId()
const messages = ref([])
const draft = ref('')
const loading = ref(false)
const typingText = ref('')
const listRef = ref(null)
let typingTimer = null
let lastTypingSentAt = 0
let unreadRemovers = []

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
</style>
