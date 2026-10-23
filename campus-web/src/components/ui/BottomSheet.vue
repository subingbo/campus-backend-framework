<script>
// 多个 Sheet 切换时共享 body 滚动锁计数，避免关闭旧 Sheet 把新 Sheet 的锁解开
let openSheetCount = 0
let sheetPreviousOverflow = ''
</script>

<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  label: { type: String, default: '面板' },
  height: { type: String, default: '68dvh' },
  maxHeight: { type: String, default: '88dvh' },
  closeOnOverlay: { type: Boolean, default: true },
  teleport: { type: Boolean, default: true },
  animated: { type: Boolean, default: true },
  panelClass: { type: [String, Array, Object], default: '' }
})
const emit = defineEmits(['update:modelValue'])

const panelRef = ref(null)
const dragY = ref(0)
const dragging = ref(false)
let startY = 0
let previousFocus = null
let active = false

function close() {
  emit('update:modelValue', false)
}

function onPointerDown(event) {
  if (event.pointerType === 'mouse' && event.button !== 0) return
  dragging.value = true
  startY = event.clientY
  dragY.value = 0
  try {
    event.currentTarget.setPointerCapture?.(event.pointerId)
  } catch {
    // 合成事件/部分浏览器不支持 capture，不阻断拖拽
  }
}

function onPointerMove(event) {
  if (!dragging.value) return
  dragY.value = Math.max(0, event.clientY - startY)
}

function onPointerUp(event) {
  if (!dragging.value) return
  dragging.value = false
  try {
    event.currentTarget.releasePointerCapture?.(event.pointerId)
  } catch {
    // 同上
  }
  if (dragY.value > 96) close()
  dragY.value = 0
}

function onOverlay() {
  if (props.closeOnOverlay) close()
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab') return
  const nodes = panelRef.value?.querySelectorAll(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )
  if (!nodes?.length) return
  const first = nodes[0]
  const last = nodes[nodes.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function activate() {
  if (active) return
  active = true
  if (openSheetCount === 0) {
    sheetPreviousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  }
  openSheetCount += 1
  previousFocus = document.activeElement
  document.addEventListener('keydown', onKeydown)
  nextTick(() => panelRef.value?.focus())
}

function deactivate() {
  if (!active) return
  active = false
  openSheetCount = Math.max(0, openSheetCount - 1)
  document.removeEventListener('keydown', onKeydown)
  if (openSheetCount === 0) {
    document.body.style.overflow = sheetPreviousOverflow
    if (previousFocus && typeof previousFocus.focus === 'function') previousFocus.focus()
  }
  previousFocus = null
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) activate()
    else deactivate()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  deactivate()
})
</script>

<template>
  <Teleport to="body" :disabled="!teleport">
    <Transition name="ui-sheet" :css="animated">
      <div
        v-if="modelValue"
        class="ui-sheet-layer ui-v2"
        :class="{ 'ui-sheet-layer--inline': !teleport }"
      >
        <div class="ui-sheet-overlay" @click="onOverlay" />
        <section
          ref="panelRef"
          class="ui-sheet"
          :class="[{ 'is-dragging': dragging }, panelClass]"
          :style="{ height, maxHeight, transform: dragY ? `translateY(${dragY}px)` : undefined }"
          tabindex="-1"
          role="dialog"
          aria-modal="true"
          :aria-label="label"
        >
          <div
            class="ui-sheet__grab"
            @pointerdown="onPointerDown"
            @pointermove="onPointerMove"
            @pointerup="onPointerUp"
            @pointercancel="onPointerUp"
          >
            <span class="ui-sheet__handle" />
          </div>
          <div class="ui-sheet__content">
            <slot />
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
