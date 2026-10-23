<script setup>
import AppIcon from './AppIcon.vue'

defineProps({
  place: { type: Object, required: true },
  active: { type: Boolean, default: false }
})
defineEmits(['select'])
</script>

<template>
  <button
    type="button"
    class="ui-place-row"
    :class="{ 'is-active': active }"
    :aria-current="active ? 'true' : undefined"
    @click="$emit('select', place)"
  >
    <span class="ui-place-row__body">
      <span class="ui-place-row__name">{{ place.name }}</span>
      <span v-if="place.address" class="ui-place-row__addr">{{ place.address }}</span>
      <span v-if="place.categoryName" class="ui-place-row__meta">{{ place.categoryName }}</span>
    </span>
    <AppIcon name="chevron-right" :size="14" class="ui-place-row__chev" />
  </button>
</template>
