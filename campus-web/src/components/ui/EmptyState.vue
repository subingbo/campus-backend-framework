<script setup>
import AppIcon from './AppIcon.vue'

defineProps({
  icon: { type: String, default: 'pin' },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  action: { type: String, default: '' },
  compact: { type: Boolean, default: false }
})
defineEmits(['action'])
</script>

<template>
  <div class="ui-empty" :class="{ 'ui-empty--compact': compact }">
    <span class="ui-empty__icon">
      <AppIcon :name="icon" :size="18" />
    </span>
    <p class="ui-empty__title">{{ title }}</p>
    <p v-if="description" class="ui-empty__desc">{{ description }}</p>
    <button v-if="action" type="button" class="ui-text-action" @click="$emit('action')">
      {{ action }}
    </button>
  </div>
</template>
