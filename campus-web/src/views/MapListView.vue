<template>
  <div class="home ui-v2">
    <div ref="mapRef" class="home-map" />

    <div v-if="!mapReady && !mapError" class="home-map-loading">
      <span class="ui-spinner" />
      正在加载地图…
    </div>

    <!-- 地图加载失败：列表、创建、加入、导航继续可用 -->
    <div v-if="mapError" class="map-error-ui">
      <span class="map-error-ui__icon">
        <AppIcon name="alert-circle" :size="20" />
      </span>
      <strong>地图没能加载</strong>
      <p>{{ mapError }}</p>
      <div class="map-error-ui__actions">
        <button class="ui-btn ui-btn--ghost" type="button" @click="retryMap">重试</button>
      </div>
    </div>

    <!-- 桌面：地图列表 -->
    <FloatingPanel v-if="!isMobile" class="home-list-panel" as="aside">
      <template #header>
        <div class="home-panel__head">
          <div class="home-panel__head-main">
            <p class="ui-eyebrow">校园地图</p>
            <h1 class="home-panel__title">我的地图</h1>
            <p class="home-panel__sub">{{ maps.length }} 张共享地图</p>
          </div>
          <button class="ui-icon-btn" type="button" aria-label="创建地图" @click="showCreate = true">
            <AppIcon name="plus" :size="18" />
          </button>
        </div>
        <SearchBar
          v-model="mapSearch"
          class="home-panel__search"
          placeholder="搜索地图"
        />
      </template>

      <div class="home-map-list">
        <div v-if="loadingMaps" class="home-list-loading">
          <span class="ui-spinner" />
          正在加载地图…
        </div>
        <button
          v-for="m in filteredMaps"
          :key="m.id"
          type="button"
          class="home-map-row"
          :class="{ 'is-active': selectedMapId === m.id }"
          @click="selectMap(m)"
        >
          <span class="home-map-row__body">
            <span class="home-map-row__top">
              <span class="home-map-row__name">{{ m.name }}</span>
              <span class="ui-pill ui-pill--muted">{{ roleText(m.myRole) }}</span>
            </span>
            <span class="home-map-row__desc">{{ m.description || '暂无描述' }}</span>
            <span class="home-map-row__meta">
              {{ countOf(m.memberCount) }} 位成员 · {{ countOf(m.placeCount) }} 个地点
            </span>
          </span>
          <AppIcon name="chevron-right" :size="14" class="home-map-row__chev" />
        </button>

        <EmptyState
          v-if="mapsError"
          icon="alert-circle"
          title="地图列表加载失败"
          :description="mapsError"
          action="重试"
          @action="loadMaps"
        />
        <EmptyState
          v-else-if="!loadingMaps && !maps.length"
          icon="pin"
          title="还没有地图"
          description="创建一张地图，或输入邀请码加入朋友的共享地图。"
          action="创建地图"
          @action="showCreate = true"
        />
        <EmptyState
          v-else-if="!loadingMaps && maps.length && !filteredMaps.length"
          icon="search"
          title="没有匹配的地图"
          description="换个关键词试试。"
          action="清除搜索"
          @action="mapSearch = ''"
        />
      </div>

      <template #footer>
        <div class="home-panel__foot">
          <button class="ui-btn ui-btn--primary" type="button" :disabled="!selectedMap" @click="enterSelectedMap">
            进入地图
          </button>
          <button class="ui-text-action ui-text-action--muted" type="button" @click="showJoin = true">
            用邀请码加入
          </button>
        </div>
      </template>
    </FloatingPanel>

    <!-- 桌面：当前地图摘要（内容高度，视觉权重低于左列表） -->
    <FloatingPanel
      v-if="summaryVisible"
      class="home-summary"
      :class="{ 'home-summary--compact': !isWide }"
      as="aside"
    >
      <div class="home-summary__body">
        <div class="home-summary__head">
          <span class="home-summary__eyebrow">当前地图</span>
          <button class="ui-icon-btn ui-icon-btn--sm" type="button" aria-label="收起当前地图摘要" @click="summaryOpen = false">
            <AppIcon name="x" :size="14" />
          </button>
        </div>
        <h2 class="home-summary__title">{{ selectedMap?.name }}</h2>
        <p class="home-summary__desc">{{ selectedMap?.description || '暂无描述' }}</p>

        <div v-if="selectedMembers.length" class="home-summary__people">
          <span class="home-avatars">
            <span v-for="member in selectedMembers.slice(0, 3)" :key="member.userId" :title="member.nickname || member.username">
              {{ initialOf(member) }}
            </span>
            <span v-if="selectedMembers.length > 3">+{{ selectedMembers.length - 3 }}</span>
          </span>
          <span class="home-summary__people-text">{{ selectedMemberCount }} 位成员</span>
        </div>
        <p v-else-if="detailError" class="home-summary__hint">成员信息暂不可用</p>

        <p class="home-summary__facts">
          {{ selectedPlaceCount }} 个地点 · {{ roleText(selectedMap?.myRole) }}
        </p>

        <div class="home-summary__actions">
          <button class="ui-text-action" type="button" @click="enterSelectedMap">
            进入地图
            <AppIcon name="chevron-right" :size="14" />
          </button>
          <button v-if="inviteCode" class="ui-text-action ui-text-action--muted" type="button" @click="copyInvite">
            复制邀请码
          </button>
        </div>
      </div>
    </FloatingPanel>

    <!-- 1100–1439：摘要默认折叠为小胶囊 -->
    <button v-if="summaryPillVisible" class="home-summary-pill" type="button" @click="summaryOpen = true">
      <AppIcon name="layers" :size="15" />
      当前地图
      <span class="home-summary-pill__name">{{ selectedMap?.name }}</span>
      <AppIcon name="chevron-down" :size="14" class="home-summary-pill__chev" />
    </button>

    <!-- 地图提示 / 地点异常 -->
    <template v-if="!isMobile && mapReady && !mapError">
      <div v-if="placesError" class="map-hint-ui home-map-hint home-map-hint--error">
        <AppIcon name="alert-circle" :size="14" />
        地点加载失败
        <button class="ui-link-btn" type="button" @click="reloadPlaces">重试</button>
      </div>
      <div v-else class="map-hint-ui home-map-hint">
        <AppIcon name="layers" :size="14" />
        {{ selectedPlaces.length }} 个地点 · 点击标记查看
      </div>
    </template>

    <div v-if="!isMobile && mapReady && !mapError" class="map-controls-ui">
      <MapActionButton icon="plus" label="放大" @click="zoomIn" />
      <MapActionButton icon="minus" label="缩小" @click="zoomOut" />
    </div>

    <!-- 移动端：底部触发条 -->
    <button v-if="isMobile && !mobileSheetOpen" class="mobile-place-trigger" type="button" @click="mobileSheetOpen = true">
      <span class="mobile-place-trigger__icon">
        <AppIcon name="panel" :size="18" />
      </span>
      <span class="mobile-place-trigger__main">
        <span class="mobile-place-trigger__title">我的地图 · {{ maps.length }}</span>
        <span class="mobile-place-trigger__sub">查看共享地图</span>
      </span>
      <AppIcon name="chevron-down" :size="16" class="mobile-place-trigger__chev" />
    </button>

    <!-- 移动端：地图列表 Sheet -->
    <BottomSheet
      v-if="isMobile"
      v-model="mobileSheetOpen"
      label="我的地图"
      height="68dvh"
      :teleport="false"
      :animated="false"
      panel-class="ui-v2"
    >
      <div class="home-mobile-sheet">
        <header class="home-mobile-head">
          <div class="home-mobile-head__main">
            <p class="ui-eyebrow">共享地图</p>
            <h2 class="home-mobile-head__title">我的地图</h2>
            <p class="home-mobile-head__sub">{{ maps.length }} 张共享地图</p>
          </div>
          <button class="ui-icon-btn" type="button" aria-label="创建地图" @click="openCreateFromSheet">
            <AppIcon name="plus" :size="18" />
          </button>
          <button class="ui-icon-btn" type="button" aria-label="关闭地图列表" @click="mobileSheetOpen = false">
            <AppIcon name="x" :size="18" />
          </button>
        </header>

        <SearchBar
          v-model="mapSearch"
          class="home-mobile-head__search"
          size="lg"
          placeholder="搜索地图"
        />

        <div class="home-mobile-list">
          <button
            v-for="m in filteredMaps"
            :key="m.id"
            type="button"
            class="home-map-row"
            :class="{ 'is-active': selectedMapId === m.id }"
            @click="selectMap(m)"
          >
            <span class="home-map-row__body">
              <span class="home-map-row__top">
                <span class="home-map-row__name">{{ m.name }}</span>
                <span class="ui-pill ui-pill--muted">{{ roleText(m.myRole) }}</span>
              </span>
              <span class="home-map-row__desc">{{ m.description || '暂无描述' }}</span>
              <span class="home-map-row__meta">
                {{ countOf(m.memberCount) }} 位成员 · {{ countOf(m.placeCount) }} 个地点
              </span>
            </span>
            <AppIcon name="chevron-right" :size="14" class="home-map-row__chev" />
          </button>

          <EmptyState
            v-if="mapsError"
            icon="alert-circle"
            title="地图列表加载失败"
            :description="mapsError"
            action="重试"
            @action="loadMaps"
          />
          <EmptyState
            v-else-if="!maps.length"
            icon="pin"
            title="还没有地图"
            description="创建一张地图，或输入邀请码加入朋友的共享地图。"
            action="创建地图"
            @action="openCreateFromSheet"
          />
          <EmptyState
            v-else-if="!filteredMaps.length"
            icon="search"
            title="没有匹配的地图"
            action="清除搜索"
            @action="mapSearch = ''"
          />
        </div>

        <footer class="home-mobile-foot">
          <button class="ui-btn ui-btn--primary home-mobile-foot__enter" type="button" :disabled="!selectedMap" @click="enterSelectedMap">
            {{ selectedMap ? `进入「${selectedMap.name}」` : '选择一张地图' }}
          </button>
          <button class="ui-text-action ui-text-action--muted" type="button" @click="openJoinFromSheet">
            用邀请码加入
          </button>
        </footer>
      </div>
    </BottomSheet>

    <!-- 创建地图 -->
    <el-dialog v-model="showCreate" class="ui-dialog" title="创建共享地图" width="min(460px, 94vw)">
      <div class="ui-dialog-form">
        <label class="ui-dialog-field">
          <span>名称</span>
          <input v-model="createForm.name" class="ui-input" type="text" maxlength="80" placeholder="例如：周末去哪儿" @keyup.enter="createMap">
        </label>
        <label class="ui-dialog-field">
          <span>描述</span>
          <textarea v-model="createForm.description" class="ui-textarea" rows="3" maxlength="500" placeholder="这张地图用来收集什么地点？" />
        </label>
      </div>
      <template #footer>
        <button class="ui-btn ui-btn--ghost" type="button" @click="showCreate = false">取消</button>
        <button class="ui-btn ui-btn--primary" type="button" :disabled="creating" @click="createMap">
          {{ creating ? '创建中…' : '创建' }}
        </button>
      </template>
    </el-dialog>

    <!-- 创建成功：邀请码 -->
    <el-dialog v-model="createdDialog" class="ui-dialog" title="地图已创建" width="min(420px, 94vw)">
      <p class="ui-dialog__hint">把邀请码发给朋友，对方凭码即可加入这张地图。</p>
      <div class="home-invite-code">
        <strong>{{ createdInviteCode }}</strong>
        <button class="ui-btn ui-btn--secondary ui-btn--sm" type="button" @click="copyCreatedCode">
          <AppIcon name="copy" :size="14" />
          复制
        </button>
      </div>
      <template #footer>
        <button class="ui-btn ui-btn--ghost" type="button" @click="createdDialog = false">留在本页</button>
        <button class="ui-btn ui-btn--primary" type="button" @click="enterCreatedMap">进入地图</button>
      </template>
    </el-dialog>

    <!-- 邀请码加入 -->
    <el-dialog v-model="showJoin" class="ui-dialog" title="邀请码加入" width="min(420px, 94vw)">
      <label class="ui-dialog-field">
        <span>邀请码</span>
        <input v-model="joinCode" class="ui-input" type="text" maxlength="16" placeholder="输入 8 位邀请码" @keyup.enter="joinMap">
      </label>
      <template #footer>
        <button class="ui-btn ui-btn--ghost" type="button" @click="showJoin = false">取消</button>
        <button class="ui-btn ui-btn--primary" type="button" :disabled="joining" @click="joinMap">
          {{ joining ? '加入中…' : '加入' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { mapApi } from '../api'
import { createPlaceMarkerController, loadAmap } from '../composables/useAmap'
import AppIcon from '../components/ui/AppIcon.vue'
import FloatingPanel from '../components/ui/FloatingPanel.vue'
import SearchBar from '../components/ui/SearchBar.vue'
import EmptyState from '../components/ui/EmptyState.vue'
import MapActionButton from '../components/ui/MapActionButton.vue'
import BottomSheet from '../components/ui/BottomSheet.vue'

const router = useRouter()
const campusLng = Number(import.meta.env.VITE_MAP_CENTER_LNG || 113.384128)
const campusLat = Number(import.meta.env.VITE_MAP_CENTER_LAT || 23.045678)

const maps = ref([])
const loadingMaps = ref(false)
const mapsError = ref('')
const mapSearch = ref('')
const selectedMapId = ref(null)
const selectedPlaces = ref([])
const selectedDetail = ref(null)
const placesError = ref('')
const detailError = ref('')
const requestSeq = ref(0)
const mobileSheetOpen = ref(false)
const summaryOpen = ref(false)

const mapRef = ref(null)
const mapReady = ref(false)
const mapError = ref('')
let amapCtx = null
let markerController = null
let resizeObserver = null
let disposed = false

const mobileMedia = window.matchMedia('(max-width: 768px)')
const narrowMedia = window.matchMedia('(max-width: 1099px)')
const wideMedia = window.matchMedia('(min-width: 1440px)')
const isMobile = ref(window.innerWidth <= 768)
const isNarrow = ref(window.innerWidth > 768 && window.innerWidth <= 1099)
const isWide = ref(window.innerWidth >= 1440)
if (isWide.value) summaryOpen.value = true

const showCreate = ref(false)
const showJoin = ref(false)
const creating = ref(false)
const joining = ref(false)
const joinCode = ref('')
const createdDialog = ref(false)
const createdInviteCode = ref('')
const createdMapId = ref(null)
const createForm = reactive({ name: '', description: '' })

const selectedMap = computed(() => maps.value.find((m) => m.id === selectedMapId.value) || null)
const filteredMaps = computed(() => {
  const q = mapSearch.value.trim().toLowerCase()
  if (!q) return maps.value
  return maps.value.filter((m) =>
    [m.name, m.description, m.myRole].filter(Boolean).join(' ').toLowerCase().includes(q)
  )
})
const selectedMembers = computed(() => selectedDetail.value?.members ?? [])
const inviteCode = computed(() => selectedDetail.value?.inviteCode || '')
const selectedMemberCount = computed(
  () => Number(selectedDetail.value?.memberCount ?? selectedMap.value?.memberCount) || 0
)
const selectedPlaceCount = computed(
  () => Number(selectedDetail.value?.placeCount ?? selectedMap.value?.placeCount) || 0
)
const summaryVisible = computed(
  () => !isMobile.value && !isNarrow.value && summaryOpen.value && !!selectedMap.value
)
const summaryPillVisible = computed(
  () => !isMobile.value && !isNarrow.value && !summaryOpen.value && !!selectedMap.value
)

function countOf(value) {
  return Number(value) || 0
}

const ROLE_TEXT = { OWNER: '群主', EDITOR: '编辑', VIEWER: '查看' }
const ROLE_ORDER = { OWNER: 0, EDITOR: 1, VIEWER: 2 }

function roleText(role) {
  return ROLE_TEXT[role] || '成员'
}

function initialOf(member) {
  const name = member?.nickname || member?.username || '?'
  return name.slice(0, 1).toUpperCase()
}

function errorMessage(reason) {
  return reason?.message || '请求失败'
}

function clearHomeMarkers() {
  markerController?.render([], { preserveOtherOverlays: true })
}

function syncHomeMarkers(fitView = false) {
  markerController?.render(selectedPlaces.value, { fitView, preserveOtherOverlays: true })
}

function onMarkerClick(place) {
  const map = selectedMap.value
  if (!map || !place) return
  router.push({ path: `/maps/${map.id}`, query: { place: place.id } })
}

function enterSelectedMap() {
  if (selectedMap.value) router.push(`/maps/${selectedMap.value.id}`)
}

function zoomIn() {
  amapCtx?.map?.zoomIn?.()
}

function zoomOut() {
  amapCtx?.map?.zoomOut?.()
}

function centerCampus() {
  amapCtx?.map?.setZoomAndCenter?.(16, [campusLng, campusLat])
}

async function loadSelectedPlaces(id) {
  const seq = requestSeq.value
  if (!id) return
  placesError.value = ''
  selectedPlaces.value = []
  clearHomeMarkers()
  try {
    const places = await mapApi.listPlaces(id)
    if (seq !== requestSeq.value) return
    selectedPlaces.value = Array.isArray(places) ? places : []
    syncHomeMarkers(true)
    if (!selectedPlaces.value.length) centerCampus()
  } catch (e) {
    if (seq !== requestSeq.value) return
    placesError.value = errorMessage(e)
    selectedPlaces.value = []
    clearHomeMarkers()
  }
}

async function loadSelectedDetail(id) {
  const seq = requestSeq.value
  if (!id) return
  detailError.value = ''
  selectedDetail.value = null
  try {
    const detail = await mapApi.detail(id)
    if (seq !== requestSeq.value) return
    selectedDetail.value = detail || null
  } catch (e) {
    if (seq !== requestSeq.value) return
    detailError.value = errorMessage(e)
    selectedDetail.value = null
  }
}

async function loadSelected(id) {
  requestSeq.value += 1
  await Promise.all([loadSelectedPlaces(id), loadSelectedDetail(id)])
}

function reloadPlaces() {
  if (selectedMapId.value) {
    requestSeq.value += 1
    loadSelectedPlaces(selectedMapId.value)
  }
}

function selectMap(map) {
  if (!map) return
  if (selectedMapId.value === map.id) return
  selectedMapId.value = map.id
  if (isWide.value) summaryOpen.value = true
  loadSelected(map.id)
}

async function loadMaps() {
  loadingMaps.value = true
  mapsError.value = ''
  try {
    const list = await mapApi.list()
    maps.value = Array.isArray(list)
      ? [...list].sort((a, b) => (ROLE_ORDER[a.myRole] ?? 9) - (ROLE_ORDER[b.myRole] ?? 9))
      : []
    if (!maps.value.length) {
      selectedMapId.value = null
      selectedPlaces.value = []
      selectedDetail.value = null
      clearHomeMarkers()
      return
    }
    const still = maps.value.find((m) => m.id === selectedMapId.value)
    const target = still || maps.value[0]
    selectedMapId.value = target.id
    if (isWide.value) summaryOpen.value = true
    await loadSelected(target.id)
  } catch (e) {
    mapsError.value = errorMessage(e)
    ElMessage.error(mapsError.value)
  } finally {
    loadingMaps.value = false
  }
}

async function initMap() {
  if (!mapRef.value || disposed) return
  mapError.value = ''
  mapReady.value = false
  if (amapCtx?.map) {
    amapCtx.map.destroy()
    amapCtx = null
    markerController = null
  }
  try {
    const ctx = await loadAmap(mapRef.value, null, { controls: false })
    if (disposed) {
      ctx.map.destroy()
      return
    }
    amapCtx = ctx
    markerController = createPlaceMarkerController(ctx.AMap, ctx.map, onMarkerClick)
    mapReady.value = true
    if (resizeObserver) resizeObserver.disconnect()
    resizeObserver = new ResizeObserver(() => {
      amapCtx?.map?.resize?.()
    })
    resizeObserver.observe(mapRef.value)
    if (selectedPlaces.value.length) syncHomeMarkers(true)
  } catch (e) {
    mapError.value = errorMessage(e)
  }
}

function retryMap() {
  initMap()
}

async function createMap() {
  if (!createForm.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  creating.value = true
  try {
    const res = await mapApi.createGroup(createForm)
    showCreate.value = false
    createdInviteCode.value = res.inviteCode || ''
    createdMapId.value = res.mapId
    createdDialog.value = true
    await loadMaps()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    creating.value = false
  }
}

async function copyCreatedCode() {
  if (!createdInviteCode.value) return
  try {
    await navigator.clipboard?.writeText(createdInviteCode.value)
    ElMessage.success('已复制邀请码')
  } catch {
    ElMessage.error('复制失败，请手动选择')
  }
}

function enterCreatedMap() {
  createdDialog.value = false
  if (createdMapId.value) router.push(`/maps/${createdMapId.value}`)
}

async function joinMap() {
  if (!joinCode.value.trim()) {
    ElMessage.warning('请输入邀请码')
    return
  }
  joining.value = true
  try {
    const detail = await mapApi.joinGroup({ inviteCode: joinCode.value.trim().toUpperCase() })
    ElMessage.success('加入成功')
    showJoin.value = false
    await loadMaps()
    if (detail?.id) router.push(`/maps/${detail.id}`)
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    joining.value = false
  }
}

async function copyInvite() {
  if (!inviteCode.value) return
  try {
    await navigator.clipboard?.writeText(inviteCode.value)
    ElMessage.success('已复制邀请码')
  } catch {
    ElMessage.error('复制失败，请手动选择')
  }
}

function openCreateFromSheet() {
  mobileSheetOpen.value = false
  showCreate.value = true
}

function openJoinFromSheet() {
  mobileSheetOpen.value = false
  showJoin.value = true
}

function updateMedia() {
  const width = window.innerWidth
  isMobile.value = width <= 768
  isWide.value = width >= 1440
  isNarrow.value = width > 768 && width <= 1099
  if (isMobile.value) {
    summaryOpen.value = false
    mobileSheetOpen.value = false
  } else if (isNarrow.value) {
    summaryOpen.value = false
  } else if (isWide.value) {
    summaryOpen.value = true
  } else {
    summaryOpen.value = false
  }
}

onMounted(() => {
  mobileMedia.addEventListener('change', updateMedia)
  narrowMedia.addEventListener('change', updateMedia)
  wideMedia.addEventListener('change', updateMedia)
  window.addEventListener('resize', updateMedia)
  initMap()
  loadMaps()
})

onUnmounted(() => {
  disposed = true
  mobileMedia.removeEventListener('change', updateMedia)
  narrowMedia.removeEventListener('change', updateMedia)
  wideMedia.removeEventListener('change', updateMedia)
  window.removeEventListener('resize', updateMedia)
  resizeObserver?.disconnect()
  resizeObserver = null
  markerController = null
  if (amapCtx?.map) {
    amapCtx.map.destroy()
    amapCtx = null
  }
})
</script>
