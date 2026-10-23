<template>
  <div v-if="revealing" class="reply-stream" aria-live="off"><span v-for="chunk in chunks" :key="chunk.id" class="reply-fragment">{{ chunk.text }}</span><span v-if="streaming" class="reply-caret" aria-hidden="true">▍</span></div>
  <MarkdownText v-else :text="text" />
</template>

<script setup>
import { onUnmounted, ref, watch } from 'vue'
import MarkdownText from './MarkdownText.vue'

const props = defineProps({
  text: { type: String, default: '' },
  streaming: Boolean,
  animate: Boolean
})
const emit = defineEmits(['reveal'])
const revealing = ref(props.animate)
const chunks = ref([])
const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
let rendered = ''
let frame = 0
let settleTimer = 0
let serial = 0

function advance() {
  frame = 0
  const pending = Array.from(props.text.slice(rendered.length))
  if (pending.length) {
    const count = reduceMotion ? pending.length : Math.max(4, Math.ceil(pending.length / 18))
    const text = pending.slice(0, count).join('')
    chunks.value.push({ id: serial++, text })
    rendered += text
    emit('reveal')
  }
  if (rendered.length < props.text.length) {
    frame = requestAnimationFrame(advance)
  } else if (!props.streaming) {
    // Let the last fragment finish before changing to formatted Markdown.
    settleTimer = window.setTimeout(() => { revealing.value = false; emit('reveal') }, reduceMotion ? 0 : 480)
  }
}

watch(() => [props.text, props.streaming], () => {
  if (!props.animate) return
  clearTimeout(settleTimer)
  if (!props.text.startsWith(rendered)) {
    rendered = ''
    chunks.value = []
  }
  revealing.value = true
  if (!frame) frame = requestAnimationFrame(advance)
}, { immediate: true })

onUnmounted(() => { cancelAnimationFrame(frame); clearTimeout(settleTimer) })
</script>

<style scoped>
.reply-stream { white-space: pre-wrap; overflow-wrap: anywhere; }
.reply-fragment { animation: reply-reveal 460ms cubic-bezier(.2,.65,.3,1) both; }
.reply-caret { color: #0052d9; animation: reply-caret 1.1s ease-in-out infinite; }
@keyframes reply-reveal {
  from { opacity: 0; filter: blur(4px); }
  to { opacity: 1; filter: blur(0); }
}
@keyframes reply-caret { 50% { opacity: .25; } }
@media (prefers-reduced-motion: reduce) {
  .reply-fragment, .reply-caret { animation: none; }
}
</style>
