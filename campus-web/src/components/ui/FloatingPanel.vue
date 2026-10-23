<script setup>
defineProps({
  as: { type: String, default: 'section' },
  scroll: { type: Boolean, default: true },
  padded: { type: Boolean, default: false }
})
</script>

<template>
  <component
    :is="as"
    class="ui-panel"
    :class="{ 'ui-panel--padded': padded, 'ui-panel--fixed': !scroll }"
  >
    <header v-if="$slots.header" class="ui-panel__header">
      <slot name="header" />
    </header>
    <div class="ui-panel__body">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="ui-panel__footer">
      <slot name="footer" />
    </footer>
  </component>
</template>
