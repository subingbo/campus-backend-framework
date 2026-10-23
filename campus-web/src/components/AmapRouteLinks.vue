<template>
  <div v-if="links.length" class="route-links" aria-label="高德路线">
    <a v-for="link in links" :key="link.key" :href="link.url" target="_blank" rel="noopener noreferrer" class="route-link">
      <span class="route-link__route">{{ link.fromName }} <span aria-hidden="true">→</span> {{ link.toName }}</span>
      <span class="route-link__detail">
        {{ link.modeLabel }}<template v-if="link.distanceMeters"> · {{ link.distanceMeters }} 米</template><template v-if="link.minutes"> · 约 {{ link.minutes }} 分钟</template>{{ link.estimated ? '（估算）' : '' }}
      </span>
      <span class="route-link__action">在高德中打开 ↗</span>
    </a>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { buildGeoRouteLinks } from '../utils/amapNavigation'

const props = defineProps({ events: { type: Array, default: () => [] } })
const links = computed(() => buildGeoRouteLinks(props.events))
</script>

<style scoped>
.route-links { display: grid; gap: 8px; margin-top: 12px; white-space: normal; }
.route-link { display: grid; gap: 4px; padding: 10px 12px; border: 1px solid #dbe6fa; border-radius: 10px; background: #fff; text-decoration: none; color: #263654; transition: border-color 180ms, background 180ms; }
.route-link:hover { border-color: #91b2ee; background: #f7faff; }
.route-link:focus-visible { outline: 2px solid #0052d9; outline-offset: 2px; }
.route-link__route { font-weight: 600; }
.route-link__detail { color: #69758a; font-size: 12px; }
.route-link__action { color: #0052d9; font-size: 12px; margin-top: 3px; }
</style>
