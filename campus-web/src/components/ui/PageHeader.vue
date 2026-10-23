<template>
  <header class="ui-page-header">
    <div class="ui-page-header__main">
      <h1 class="ui-page-header__title">{{ title }}</h1>
      <p v-if="description" class="ui-page-header__description">{{ description }}</p>
    </div>
    <div v-if="$slots.summary" class="ui-page-header__summary">
      <slot name="summary" />
    </div>
    <div v-if="$slots.actions" class="ui-page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  description: { type: String, default: '' }
})
</script>
