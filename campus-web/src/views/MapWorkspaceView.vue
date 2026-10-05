<template>
  <div class="workspace" v-loading="pageLoading">
    <aside class="sidebar">
      <el-button link type="primary" @click="$router.push('/')">← 我的地图</el-button>
      <h2>{{ detail?.name || '加载中' }}</h2>
      <p class="desc">{{ detail?.description }}</p>
      <div class="stats">
        <span>成员 {{ detail?.memberCount ?? 0 }}</span>
        <span>地点 {{ detail?.placeCount ?? 0 }}</span>
        <el-tag size="small">{{ detail?.myRole }}</el-tag>
      </div>
      <div v-if="detail?.inviteCode" class="invite">
        <span>邀请码</span>
        <el-tag type="success" @click="copyInvite">{{ detail.inviteCode }}</el-tag>
      </div>

      <el-divider />
      <div class="member-head">
        <strong>成员</strong>
      </div>
      <div v-for="m in detail?.members || []" :key="m.userId" class="member-row">
        <span>{{ m.nickname || m.username }}</span>
        <el-select
          v-if="detail?.myRole === 'OWNER' && m.role !== 'OWNER'"
          :model-value="m.role"
          size="small"
          style="width: 96px"
          @change="(val) => changeRole(m.userId, val)"
        >
          <el-option label="编辑" value="EDITOR" />
          <el-option label="查看" value="VIEWER" />
        </el-select>
        <el-tag v-else size="small">{{ m.role }}</el-tag>
      </div>

      <el-divider />
      <el-button
        v-if="canEdit"
        type="primary"
        style="width: 100%"
        :plain="addMode"
        @click="toggleAddMode"
      >
        {{ addMode ? '取消选点' : '添加地点' }}
      </el-button>
      <el-button
        v-if="canEdit && mapError"
        style="width: 100%; margin-top: 8px"
        @click="openAddWithDefaultCenter"
      >
        地图未加载：手动添加（默认中心）
      </el-button>
      <p v-if="!canEdit" class="hint">当前为查看权限，无法添加或编辑地点</p>
      <p v-if="addMode" class="hint">在地图上点击选择位置</p>

      <el-divider />
      <div class="locate-actions">
        <el-button :loading="locating" :disabled="!mapReady" @click="locateMe">定位到我</el-button>
        <el-button v-if="canEdit" :loading="locating" :disabled="!mapReady" @click="locateAndPick">
          以我的位置选点
        </el-button>
        <el-button v-if="canEdit" :loading="locating" :disabled="!mapReady" @click="locateAndMark">
          标记我的位置
        </el-button>
      </div>
      <p v-if="myLocationSummary" class="hint">我的位置：{{ myLocationSummary }}</p>

      <el-divider />
      <div class="place-head">
        <strong>地点列表</strong>
        <span v-if="places.length" class="hint">{{ filteredPlaces.length }}/{{ places.length }}</span>
      </div>
      <el-input
        v-model="placeSearchQuery"
        clearable
        size="small"
        placeholder="搜索名称、地址、分类"
        class="place-search"
      />
      <div v-if="placeCategoryFilters.length" class="category-chips">
        <el-tag
          size="small"
          :type="!categoryFilter ? 'primary' : 'info'"
          effect="plain"
          class="chip"
          @click="categoryFilter = ''"
        >
          全部
        </el-tag>
        <el-tag
          v-for="cat in placeCategoryFilters"
          :key="cat"
          size="small"
          :type="categoryFilter === cat ? 'primary' : 'info'"
          effect="plain"
          class="chip"
          @click="toggleCategoryFilter(cat)"
        >
          {{ cat }}
        </el-tag>
      </div>
      <div ref="placeListRef" class="place-list">
        <p v-if="places.length === 0" class="hint empty-hint">暂无地点，可在地图上添加</p>
        <p v-else-if="filteredPlaces.length === 0" class="hint empty-hint">无匹配结果</p>
        <button
          v-for="p in filteredPlaces"
          :key="p.id"
          type="button"
          class="place-item"
          :class="{ active: selectedPlace?.id === p.id }"
          :data-place-id="p.id"
          @click="selectPlaceFromList(p)"
        >
          <span class="place-item__name">{{ p.name }}</span>
          <span v-if="p.categoryName" class="place-item__cat">{{ p.categoryName }}</span>
          <span v-if="p.address" class="place-item__addr">{{ p.address }}</span>
        </button>
      </div>
    </aside>

    <main class="map-panel">
      <div ref="mapRef" class="map-container"></div>
      <el-alert
        v-if="mapError"
        :title="mapError"
        type="warning"
        show-icon
        class="map-alert"
      />
    </main>

    <el-drawer v-model="placeDrawer" :title="selectedPlace?.name || '地点'" size="380px">
      <template v-if="selectedPlace">
        <p>{{ selectedPlace.description || '暂无介绍' }}</p>
        <p><small>{{ selectedPlace.address || '暂无地址' }}</small></p>

        <div class="drawer-actions">
          <el-button v-if="canEdit" size="small" @click="openEdit">编辑</el-button>
          <el-button v-if="canEdit" size="small" type="danger" plain @click="removeFromMap">从地图移除</el-button>
          <el-dropdown v-if="otherMaps.length" @command="shareToMap">
            <el-button size="small">分享到其他地图</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="m in otherMaps" :key="m.id" :command="m.id">
                  {{ m.name }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <el-divider>评论</el-divider>
        <div v-for="c in comments" :key="c.id" class="comment">
          <strong>{{ c.nickname }}</strong>
          <p>{{ c.content }}</p>
        </div>
        <el-input v-model="commentText" type="textarea" rows="2" placeholder="写下评论" />
        <el-button type="primary" size="small" style="margin-top: 8px" @click="submitComment">发送</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="addDialog" title="添加地点" width="460px">
      <el-form :model="addForm" label-width="72px">
        <el-form-item label="名称"><el-input v-model="addForm.name" /></el-form-item>
        <el-form-item label="分类">
          <el-select v-model="addForm.categoryId" style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="addForm.description" type="textarea" /></el-form-item>
        <el-form-item label="文字地址">
          <el-input v-model="addForm.address" placeholder="选填，如：广外北门附近" />
        </el-form-item>
        <el-form-item label="坐标">
          <div class="coord-row">
            <el-input-number v-model="addForm.longitude" :precision="6" :step="0.0001" controls-position="right" />
            <el-input-number v-model="addForm.latitude" :precision="6" :step="0.0001" controls-position="right" />
          </div>
          <div class="coord-actions">
            <el-button size="small" :loading="locating" :disabled="!mapReady" @click="fillFormWithLocation">
              填入当前位置
            </el-button>
            <span class="hint">可地图选点，或直接修改经纬度</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="() => submitAdd()">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dupDialog" title="附近已有相近地点" width="480px">
      <p>是否补充已有地点，避免重复？</p>
      <el-radio-group v-model="dupChoice">
        <el-radio v-for="c in dupCandidates" :key="c.id" :value="c.id">
          {{ c.name }}（约 {{ c.distanceMeters?.toFixed(1) }}m）
        </el-radio>
        <el-radio :value="-1">仍要新建地点</el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="dupDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmDuplicate">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editDialog" title="编辑地点" width="460px">
      <el-form :model="editForm" label-width="72px">
        <el-form-item label="名称"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="分类">
          <el-select v-model="editForm.categoryId" style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="editForm.description" type="textarea" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="editForm.address" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createAmapLocationProvider, createPlaceMarkerController, loadAmap } from '../composables/useAmap'
import { useLocation } from '../composables/useLocation'
import { mapApi, placeApi } from '../api'

const route = useRoute()
const mapId = computed(() => Number(route.params.id))
const defaultLng = Number(import.meta.env.VITE_MAP_CENTER_LNG || 113.384128)
const defaultLat = Number(import.meta.env.VITE_MAP_CENTER_LAT || 23.045678)

const pageLoading = ref(true)
const detail = ref(null)
const places = ref([])
const categories = ref([])
const allMaps = ref([])
const mapRef = ref(null)
const mapError = ref('')
const mapReady = ref(false)
let amapCtx = null
let markerController = null
let locationProvider = null

const placeSearchQuery = ref('')
const categoryFilter = ref('')
const placeListRef = ref(null)

const addMode = ref(false)
const addDialog = ref(false)
const addForm = reactive({
  name: '',
  description: '',
  address: '',
  categoryId: null,
  longitude: null,
  latitude: null
})
const submitting = ref(false)

const placeDrawer = ref(false)
const selectedPlace = ref(null)
const comments = ref([])
const commentText = ref('')

const dupDialog = ref(false)
const dupCandidates = ref([])
const dupChoice = ref(null)
const pendingAddPayload = ref(null)

const editDialog = ref(false)
const editForm = reactive({ name: '', description: '', address: '', categoryId: null })

const canEdit = computed(() => ['OWNER', 'EDITOR'].includes(detail.value?.myRole))
const otherMaps = computed(() => allMaps.value.filter((m) => m.id !== mapId.value))

const {
  locating,
  error: locateError,
  summary: myLocationSummary,
  isCoarse: myLocationCoarse,
  locate
} = useLocation(() => locationProvider)

const placeCategoryFilters = computed(() => {
  const names = places.value.map((p) => p.categoryName).filter(Boolean)
  return [...new Set(names)]
})

const filteredPlaces = computed(() => {
  const q = placeSearchQuery.value.trim().toLowerCase()
  return places.value.filter((p) => {
    if (categoryFilter.value && p.categoryName !== categoryFilter.value) return false
    if (!q) return true
    const hay = [p.name, p.address, p.categoryName, p.description]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return hay.includes(q)
  })
})

function toggleCategoryFilter(cat) {
  categoryFilter.value = categoryFilter.value === cat ? '' : cat
}

function scrollPlaceIntoView(placeId) {
  const root = placeListRef.value
  if (!root || !placeId) return
  const el = root.querySelector(`[data-place-id="${placeId}"]`)
  el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

function syncMarkers(fitView = false) {
  if (!markerController) return
  markerController.render(places.value, { fitView })
  if (selectedPlace.value) {
    const still = places.value.find((p) => p.id === selectedPlace.value.id)
    if (still) {
      selectedPlace.value = still
      markerController.setHighlight(still.id)
    } else {
      selectedPlace.value = null
      markerController.setHighlight(null)
    }
  }
}

async function refresh() {
  detail.value = await mapApi.detail(mapId.value)
  places.value = await mapApi.listPlaces(mapId.value)
  syncMarkers(false)
}

async function selectPlaceFromList(place) {
  markerController?.focusPlace(place)
  await openPlace(place)
  scrollPlaceIntoView(place.id)
}

async function initPage() {
  pageLoading.value = true
  try {
    categories.value = await placeApi.categories()
    allMaps.value = await mapApi.list()
    await refresh()
    try {
      amapCtx = await loadAmap(mapRef.value, onMapClick)
      markerController = createPlaceMarkerController(amapCtx.AMap, amapCtx.map, openPlace)
      locationProvider = createAmapLocationProvider(amapCtx.geolocation)
      mapReady.value = true
      syncMarkers(true)
    } catch (e) {
      mapError.value = e.message
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    pageLoading.value = false
  }
}

function toggleAddMode() {
  if (!canEdit.value) {
    ElMessage.warning('需要编辑或群主权限才能添加地点')
    return
  }
  addMode.value = !addMode.value
}

function openAddWithDefaultCenter() {
  addForm.longitude = defaultLng
  addForm.latitude = defaultLat
  addForm.categoryId = categories.value[0]?.id ?? null
  addDialog.value = true
  addMode.value = false
}

function onMapClick(lng, lat) {
  if (!addMode.value) return
  addForm.longitude = lng
  addForm.latitude = lat
  addForm.categoryId = categories.value[0]?.id ?? null
  addDialog.value = true
  addMode.value = false
}

/** 定位 → 标出「我的位置」→ 提示精度；失败时说明原因并返回 null */
async function locateToMap({ recenter = true, actionText = '已定位到当前位置' } = {}) {
  const result = await locate()
  if (!result) {
    if (locateError.value) ElMessage.error(locateError.value)
    return null
  }
  markerController?.setMyLocation(result, { panTo: recenter })
  const text = `${actionText}（${myLocationSummary.value}）`
  if (myLocationCoarse.value) {
    ElMessage.warning(`${text}，精度较低，请确认位置是否准确`)
  } else {
    ElMessage.success(text)
  }
  return result
}

async function locateMe() {
  await locateToMap()
}

async function locateAndPick() {
  const result = await locateToMap({ actionText: '已定位，请在地图上点击您附近的位置' })
  if (!result) return
  addMode.value = true
}

async function locateAndMark() {
  const result = await locateToMap({ actionText: '已定位，经纬度已填入表单' })
  if (!result) return
  applyMyLocationToAddForm(result)
  addMode.value = false
  addDialog.value = true
}

async function fillFormWithLocation() {
  const result = await locateToMap({ recenter: false, actionText: '已填入当前位置' })
  if (!result) return
  applyMyLocationToAddForm(result)
}

/** 定位结果填入添加表单，不覆盖用户已选的分类 */
function applyMyLocationToAddForm(result) {
  addForm.longitude = result.lng
  addForm.latitude = result.lat
  if (!addForm.categoryId) {
    addForm.categoryId = categories.value[0]?.id ?? null
  }
}

async function submitAdd(extra = {}) {
  const opts =
    extra && typeof extra === 'object' && extra.constructor === Object ? extra : {}
  if (!addForm.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!addForm.categoryId) {
    ElMessage.warning('请选择分类')
    return
  }
  if (addForm.longitude == null || addForm.latitude == null) {
    ElMessage.warning('请先在地图上选点，或填写经纬度')
    return
  }
  submitting.value = true
  try {
    const payload = {
      name: addForm.name,
      description: addForm.description,
      address: addForm.address,
      categoryId: addForm.categoryId,
      longitude: addForm.longitude,
      latitude: addForm.latitude,
      confirmNew: opts.confirmNew,
      confirmLinkPlaceId: opts.confirmLinkPlaceId
    }
    const res = await mapApi.addPlace(mapId.value, payload)
    if (res.duplicatePending) {
      dupCandidates.value = res.candidates || []
      dupChoice.value = dupCandidates.value[0]?.id
      pendingAddPayload.value = payload
      dupDialog.value = true
      return
    }
    ElMessage.success('已添加')
    addDialog.value = false
    await refresh()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

async function confirmDuplicate() {
  dupDialog.value = false
  const base = pendingAddPayload.value
  if (!base) return
  if (dupChoice.value === -1) {
    await submitAdd({ confirmNew: true })
  } else {
    await submitAdd({ confirmLinkPlaceId: dupChoice.value })
  }
}

async function openPlace(place) {
  selectedPlace.value = place
  markerController?.setHighlight(place.id)
  placeDrawer.value = true
  comments.value = await mapApi.listComments(mapId.value, place.id)
  scrollPlaceIntoView(place.id)
}

function openEdit() {
  editForm.name = selectedPlace.value.name
  editForm.description = selectedPlace.value.description
  editForm.address = selectedPlace.value.address
  editForm.categoryId = selectedPlace.value.categoryId
  editDialog.value = true
}

async function saveEdit() {
  try {
    await placeApi.update(selectedPlace.value.id, { ...editForm })
    ElMessage.success('已更新')
    editDialog.value = false
    await refresh()
    selectedPlace.value = places.value.find((p) => p.id === selectedPlace.value.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeFromMap() {
  await ElMessageBox.confirm('仅从当前地图移除，不会删除地点本体', '确认')
  try {
    await mapApi.removePlace(mapId.value, selectedPlace.value.id)
    ElMessage.success('已移除')
    placeDrawer.value = false
    await refresh()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function shareToMap(targetMapId) {
  try {
    const res = await mapApi.sharePlace(targetMapId, selectedPlace.value.id, {})
    if (res.duplicatePending) {
      ElMessage.warning('目标地图 5 米内有相近地点，请在目标地图工作台处理')
      return
    }
    ElMessage.success('已分享')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitComment() {
  if (!commentText.value.trim()) return
  try {
    await mapApi.addComment(mapId.value, selectedPlace.value.id, { content: commentText.value })
    commentText.value = ''
    comments.value = await mapApi.listComments(mapId.value, selectedPlace.value.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function changeRole(userId, role) {
  try {
    await mapApi.updateMemberRole(mapId.value, userId, { role })
    ElMessage.success('角色已更新')
    detail.value = await mapApi.detail(mapId.value)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function copyInvite() {
  navigator.clipboard.writeText(detail.value.inviteCode)
  ElMessage.success('已复制邀请码')
}

onMounted(initPage)
onUnmounted(() => {
  amapCtx?.map?.destroy()
})
</script>

<style scoped>
.workspace {
  display: grid;
  grid-template-columns: 280px 1fr;
  min-height: 100vh;
}
.sidebar {
  background: #fff;
  border-right: 1px solid #e5e7eb;
  padding: 16px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  max-height: 100vh;
}
.place-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.place-search {
  margin-bottom: 8px;
}
.category-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.category-chips .chip {
  cursor: pointer;
}
.place-list {
  flex: 1;
  min-height: 120px;
  max-height: 42vh;
  overflow-y: auto;
  margin: 0 -4px;
  padding: 0 4px;
}
.empty-hint {
  text-align: center;
  padding: 12px 0;
}
.place-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  margin-bottom: 6px;
  padding: 10px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fafafa;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  transition: border-color 0.15s, background 0.15s;
}
.place-item:hover {
  border-color: #409eff;
  background: #f0f7ff;
}
.place-item.active {
  border-color: #409eff;
  background: #ecf5ff;
  box-shadow: 0 0 0 1px #409eff33;
}
.place-item__name {
  font-weight: 600;
  font-size: 0.9rem;
  line-height: 1.3;
}
.place-item__cat {
  margin-top: 4px;
  font-size: 11px;
  color: #409eff;
}
.place-item__addr {
  margin-top: 2px;
  font-size: 11px;
  color: #888;
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.map-container :deep(.campus-place-marker) {
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: pointer;
}
.map-container :deep(.campus-place-marker__dot) {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #409eff;
  border: 2px solid #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
  flex-shrink: 0;
}
.map-container :deep(.campus-place-marker--active .campus-place-marker__dot) {
  width: 18px;
  height: 18px;
  background: #f56c6c;
  border-width: 3px;
}
.map-container :deep(.campus-place-marker__label) {
  margin-top: 2px;
  max-width: 120px;
  padding: 2px 6px;
  font-size: 11px;
  line-height: 1.2;
  color: #303133;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.map-container :deep(.campus-place-marker--active .campus-place-marker__label) {
  color: #fff;
  background: #409eff;
  border-color: #409eff;
  font-weight: 600;
}
.map-container :deep(.campus-my-location-marker) {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
}
.map-container :deep(.campus-my-location-marker__dot) {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #07c160;
  border: 2px solid #fff;
  box-shadow: 0 0 0 6px rgba(7, 193, 96, 0.22), 0 1px 4px rgba(0, 0, 0, 0.35);
  flex-shrink: 0;
}
.map-container :deep(.campus-my-location-marker__label) {
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  margin-top: 4px;
  padding: 2px 6px;
  font-size: 11px;
  line-height: 1.2;
  font-weight: 600;
  color: #fff;
  background: #07c160;
  border-radius: 4px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
  white-space: nowrap;
}
.locate-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}
.locate-actions .el-button + .el-button {
  margin-left: 0;
}
.coord-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.desc {
  color: #666;
  font-size: 0.9rem;
}
.stats {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-top: 8px;
}
.invite {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  align-items: center;
}
.member-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 0.9rem;
}
.map-panel {
  position: relative;
}
.map-container {
  width: 100%;
  height: 100vh;
}
.map-alert {
  position: absolute;
  top: 12px;
  left: 12px;
  right: 12px;
  z-index: 10;
}
.drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}
.comment {
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid #eee;
}
.hint {
  font-size: 12px;
  color: #888;
}
.coord-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.coord-row .el-input-number {
  flex: 1;
}
@media (max-width: 768px) {
  .workspace {
    grid-template-columns: 1fr;
  }
  .sidebar {
    border-right: none;
    border-bottom: 1px solid #e5e7eb;
    max-height: none;
  }
  .place-list {
    max-height: 28vh;
  }
  .map-container {
    height: 60vh;
  }
}
</style>
