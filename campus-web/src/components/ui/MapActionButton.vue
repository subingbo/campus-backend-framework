<script setup>
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'

const emit = defineEmits(['click'])

const props = defineProps({
  icon: { type: String, required: true },
  label: { type: String, required: true },
  variant: { type: String, default: 'ghost' },
  size: { type: String, default: 'md' },
  badge: { type: [Boolean, Number], default: false }
})

const iconSize = computed(() => (props.size === 'lg' ? 20 : 18))
const badgeText = computed(() => {
  if (typeof props.badge === 'number' && props.badge > 0) {
    return props.badge > 99 ? '99+' : String(props.badge)
  }
  return ''
})
</script>

<template>
  <button
    type="button"
    class="ui-map-action"
    :class="[`ui-map-action--${variant}`, `ui-map-action--${size}`]"
    :aria-label="label"
    :title="label"
    @click="emit('click', $event)"
  >
    <AppIcon :name="icon" :size="iconSize" />
    <span v-if="badge" class="ui-map-action__badge" aria-hidden="true">{{ badgeText }}</span>
  </button>
</template>
