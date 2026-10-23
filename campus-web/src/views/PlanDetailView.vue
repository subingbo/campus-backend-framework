<template>
  <div class="page content-page plan-detail">
    <PageHeader
      title="群规划方案"
      description="群成员可查看方案摘要与公开站点地图；完整 AI 对话、私人地图站点与编辑保存仅发起人可见。"
    >
      <template #actions>
        <button type="button" class="ui-btn ui-btn--secondary" @click="router.back()">
          <AppIcon name="arrow-left" :size="14" />
          返回群聊
        </button>
      </template>
    </PageHeader>

    <div v-if="error" class="plan-detail__error">
      <AppIcon name="alert-circle" :size="15" />
      <span>{{ error }}</span>
    </div>
    <div v-else-if="loading" class="plan-detail__loading">
      <span class="ui-spinner" />
      正在加载方案…
    </div>

    <template v-else-if="card">
      <div class="plan-detail__map-card">
        <div v-if="mapStops.length" ref="mapRef" v-loading="mapLoading" class="plan-detail__map" />
        <p v-else class="hint plan-detail__map-empty">该方案没有可公开的地点坐标</p>
        <p v-if="mapError" class="hint plan-detail__map-error">地图加载失败：{{ mapError }}</p>
        <p v-if="hiddenStopCount" class="hint plan-detail__locked">
          <AppIcon name="alert-circle" :size="12" />
          {{ hiddenStopCount }} 个站点来自私人地图或来源不可核实，地点详情与相关路线仅发起人可见
        </p>
        <p v-if="routeSegments.length" class="plan-detail__legend">
          <span v-if="hasRealRoute"><i class="legend-line legend-line--real" />路线预览（高德实际路线）</span>
          <span v-if="hasFallbackRoute"><i class="legend-line legend-line--dashed" />示意连线，非实际行走路线</span>
        </p>
      </div>

      <GroupPlanCard
        :card="card"
        :me="me"
        :show-view-action="false"
        @continue="continuePlan"
      />
      <p class="hint plan-detail__foot">
        规划卡是生成时快照；“已保存为活动”状态会随发起人保存/修订动态更新。
      </p>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { assistantApi, chatApi } from '../api'
import { myUserId } from '../composables/useChatSocket'
import GroupPlanCard from '../components/GroupPlanCard.vue'
import AppIcon from '../components/ui/AppIcon.vue'
import PageHeader from '../components/ui/PageHeader.vue'
import {
  createPlaceMarkerController,
  createRouteLineController,
  loadAmap
} from '../composables/useAmap'
import { buildPublicRoutePairs, hiddenStops, publicMapStops } from '../utils/groupPlanMap'

const route = useRoute()
const router = useRouter()
const me = myUserId()
const loading = ref(true)
const error = ref('')
const card = ref(null)

const mapRef = ref(null)
const mapLoading = ref(false)
const mapError = ref('')
const mapStops = ref([])
const hiddenStopCount = computed(() => hiddenStops(card.value?.stops).length)
const routeSegments = ref([])
const hasRealRoute = computed(() => routeSegments.value.some((seg) => !seg.dashed))
const hasFallbackRoute = computed(() => routeSegments.value.some((seg) => seg.dashed))
let mapInstance = null
let markerController = null
let routeController = null

const conversationId = Number(route.params.conversationId)
const planId = Number(route.params.planId)

onMounted(async () => {
  if (!Number.isFinite(conversationId) || !Number.isFinite(planId)) {
    error.value = '规划地址无效'
    loading.value = false
    return
  }
  try {
    const view = await chatApi.agentPlan(conversationId, planId)
    card.value = { ...view, plan: view }
    // 先结束 loading，让 v-else-if="card" 的地图容器进入 DOM，再初始化 AMap
    loading.value = false
    await initMap(view.stops)
  } catch (e) {
    error.value = e.message || '无法查看该规划：仅当前群成员可访问'
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  routeController?.clear()
  mapInstance?.destroy?.()
  mapInstance = null
  markerController = null
  routeController = null
})

/**
 * 只初始化公开站点。加载失败仅降级地图，不影响卡片与方案文本。
 * 路线严格按原始相邻站点对绘制；隐藏站点不参与任何连线。
 */
async function initMap(stops) {
  mapStops.value = publicMapStops(stops)
  if (!mapStops.value.length) return
  mapLoading.value = true
  try {
    await nextTick()
    const { AMap, map } = await loadAmap(mapRef.value)
    mapInstance = map
    markerController = createPlaceMarkerController(AMap, map)
    routeController = createRouteLineController(AMap, map)
    markerController.render(mapStops.value, { fitView: mapStops.value.length > 1 })
    if (mapStops.value.length === 1) {
      const only = mapStops.value[0]
      map.setZoomAndCenter(16, [only.longitude, only.latitude])
    }
    await drawRoutes(stops)
  } catch (e) {
    mapError.value = e.message || '地图加载失败'
  } finally {
    mapLoading.value = false
  }
}

async function drawRoutes(stops) {
  const pairs = buildPublicRoutePairs(stops)
  const segments = await Promise.all(pairs.map(async (pair) => {
    try {
      const info = await assistantApi.route({
        fromLng: pair.from.longitude,
        fromLat: pair.from.latitude,
        toLng: pair.to.longitude,
        toLat: pair.to.latitude,
        mode: pair.mode
      })
      if (info?.polyline?.length) {
        return { mode: pair.mode, polyline: info.polyline, dashed: false }
      }
    } catch {
      // 路线服务不可用时降级为示意连线，不阻塞方案展示
    }
    return {
      mode: pair.mode,
      dashed: true,
      polyline: [
        [pair.from.longitude, pair.from.latitude],
        [pair.to.longitude, pair.to.latitude]
      ]
    }
  }))
  routeSegments.value = segments
  routeController?.draw(segments)
}

async function continuePlan(target) {
  try {
    const handoff = await chatApi.agentPlanHandoff(conversationId, target.planId)
    router.push({ path: '/assistant', query: { chat: handoff.aiConversationId } })
  } catch (e) {
    ElMessage.error(e.message || '仅发起人可继续规划')
  }
}
</script>

<style scoped>
.plan-detail__error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-radius: var(--ui-radius-md);
  background: var(--ui-danger-tint);
  color: var(--ui-danger);
  font-size: 12.5px;
}

.plan-detail__loading {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 160px;
  color: var(--ui-ink-3);
  font-size: 12.5px;
}

.plan-detail__map-card {
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius-lg);
  background: var(--ui-surface);
}

.plan-detail__map {
  height: 280px;
  border-radius: var(--ui-radius-md);
  overflow: hidden;
  background: var(--ui-surface-2);
}

.plan-detail__map-empty,
.plan-detail__map-error,
.plan-detail__locked {
  margin: 8px 0 0;
  color: var(--ui-ink-3);
  font-size: 12px;
  line-height: 1.55;
}

.plan-detail__locked {
  display: flex;
  align-items: flex-start;
  gap: 4px;
}

.plan-detail__locked :deep(svg) {
  flex: none;
  margin-top: 2px;
}

.plan-detail__map-error {
  color: var(--ui-danger);
}

.plan-detail__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin: 10px 0 0;
  color: var(--ui-ink-2);
  font-size: 12px;
}

.plan-detail__legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-line {
  display: inline-block;
  width: 26px;
  height: 0;
  border-top: 3px solid var(--ui-success);
}

.legend-line--dashed {
  border-top-style: dashed;
  border-top-color: var(--ui-ink-3);
}

.plan-detail__foot {
  margin-top: 10px;
  color: var(--ui-ink-3);
  font-size: 12px;
  line-height: 1.55;
}

@media (max-width: 768px) {
  .plan-detail__map {
    height: 210px;
  }
}
</style>
