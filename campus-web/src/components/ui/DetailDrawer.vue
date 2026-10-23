<script setup>
defineProps({
  modelValue: { type: Boolean, default: false },
  label: { type: String, default: '详情' },
  width: { type: String, default: '' }
})
defineEmits(['update:modelValue'])
</script>

<template>
  <Transition name="ui-drawer">
    <aside
      v-if="modelValue"
      class="ui-drawer"
      :style="width ? { width } : undefined"
      :aria-label="label"
    >
      <slot />
    </aside>
  </Transition>
</template>
