<script setup>
import { ref } from 'vue'
import AppIcon from './AppIcon.vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '搜索' },
  autofocus: { type: Boolean, default: false },
  size: { type: String, default: 'md' }
})
const emit = defineEmits(['update:modelValue', 'submit', 'clear'])
const inputRef = ref(null)

function onInput(event) {
  emit('update:modelValue', event.target.value)
}

function clear() {
  emit('update:modelValue', '')
  emit('clear')
  inputRef.value?.focus()
}

function focus() {
  inputRef.value?.focus()
}

defineExpose({ focus })
</script>

<template>
  <div class="ui-search" :class="`ui-search--${size}`">
    <AppIcon name="search" :size="15" class="ui-search__icon" />
    <input
      ref="inputRef"
      class="ui-search__input"
      type="search"
      :value="modelValue"
      :placeholder="placeholder"
      :autofocus="autofocus"
      @input="onInput"
      @keydown.enter.prevent="emit('submit', modelValue)"
    >
    <button
      v-if="modelValue"
      type="button"
      class="ui-search__clear"
      aria-label="清空搜索"
      @click="clear"
    >
      <AppIcon name="x" :size="13" />
    </button>
    <slot name="trailing" />
  </div>
</template>
