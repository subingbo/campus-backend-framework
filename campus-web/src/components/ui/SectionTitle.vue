<script setup>
defineProps({
  title: { type: String, required: true },
  count: { type: [Number, String], default: null },
  action: { type: String, default: '' }
})
defineEmits(['action'])
</script>

<template>
  <div class="ui-section-title">
    <h3 class="ui-section-title__text">{{ title }}</h3>
    <span v-if="count !== null && count !== undefined" class="ui-count">{{ count }}</span>
    <button
      v-if="action"
      type="button"
      class="ui-section-action"
      @click="$emit('action')"
    >
      {{ action }}
    </button>
  </div>
</template>
