<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    :width="showVenuePicker ? 'min(560px, 94vw)' : 'min(500px, 94vw)'"
    class="activity-form-dialog"
    @update:model-value="(v) => emit('update:modelValue', v)"
    @closed="onDialogClosed"
  >
    <el-form :model="form" label-width="76px">
      <!-- 主场地：仅活动页手动创建（未传 placeId）时显示；工作台从地点进入时沿用原行为 -->
      <template v-if="showVenuePicker">
        <el-form-item label="主场地">
          <el-radio-group v-model="venue.mode" size="small">
            <el-radio-button value="existing">选已有地点</el-radio-button>
            <el-radio-button value="point" :disabled="!editableMaps.length">地图点选新建</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="venue.mode === 'existing'">
          <el-form-item label="来源地图">
            <el-select v-model="venue.mapId" placeholder="选择地图" style="width: 100%" @change="onVenueMapChange">
              <el-option v-for="m in maps" :key="m.id" :label="m.name" :value="m.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="地点">
            <el-select
              v-model="venue.placeId"
              filterable
              placeholder="选择活动地点"
              style="width: 100%"
              :disabled="!venue.mapId"
            >
              <el-option v-for="p in venuePlaces" :key="p.id" :label="p.name" :value="p.id">
                <span>{{ p.name }}</span>
                <span v-if="p.categoryName || p.address" class="opt-hint">
                  {{ [p.categoryName, p.address].filter(Boolean).join(' · ') }}
                </span>
              </el-option>
            </el-select>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="来源地图">
            <el-select v-model="venue.mapId" placeholder="选择可编辑的地图" style="width: 100%" @change="onVenueMapChange">
              <el-option v-for="m in editableMaps" :key="m.id" :label="m.name" :value="m.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="地图选点">
            <div class="pick-map-wrap">
              <div ref="pickMapRef" class="pick-map"></div>
              <p class="hint">
                {{ venue.longitude != null
                  ? `已选点：${Number(venue.longitude).toFixed(6)}, ${Number(venue.latitude).toFixed(6)}`
                  : '在地图上点一下，选活动主场地' }}
              </p>
            </div>
          </el-form-item>
          <el-form-item label="名称">
            <el-input v-model="venue.name" maxlength="128" placeholder="新地点名称，如：图书馆北门草坪" />
          </el-form-item>
          <el-form-item label="地点分类">
            <el-select v-model="venue.categoryId" placeholder="选择分类" style="width: 100%">
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="文字地址">
            <el-input v-model="venue.address" maxlength="256" placeholder="选填，如：广外北门附近" />
          </el-form-item>
        </template>
      </template>

      <el-form-item label="类型">
        <el-radio-group v-model="form.category">
          <el-radio value="MEETING">会议</el-radio>
          <el-radio value="COMPETITION">比赛</el-radio>
          <el-radio value="OTHER">其他</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="活动">
        <el-input v-model="form.title" placeholder="如：项目组周会 / 校园编程挑战赛" />
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="form.description" type="textarea" placeholder="集合位置、比赛规则、需要带的物资等" />
      </el-form-item>
      <el-form-item label="时间">
        <el-date-picker
          v-model="form.range"
          type="datetimerange"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="人数上限">
        <el-input-number v-model="form.maxMembers" :min="1" :max="500" controls-position="right" />
      </el-form-item>

      <!-- 行程站点（可选）：与 AI 活动一致的多站时间线；纯坐标站只读保留 -->
      <p class="stops-title">行程站点（可选，最多 8 个）</p>
      <div v-for="(s, idx) in stops" :key="s.key" class="stop-row">
        <div class="stop-row__head">
          <span class="stop-row__seq">{{ idx + 1 }}</span>
          <template v-if="s.coordOnly">
            <span class="stop-row__name">{{ s.name }}</span>
            <el-tag size="small" effect="plain">坐标站</el-tag>
          </template>
          <el-select v-else v-model="s.placeId" filterable size="small" placeholder="选择地点" class="stop-row__place">
            <el-option v-for="p in stopOptions" :key="p.id" :label="p.label" :value="p.id" />
          </el-select>
          <span class="stop-row__ops">
            <el-button size="small" text :disabled="idx === 0" title="上移" @click="moveStop(idx, -1)">↑</el-button>
            <el-button size="small" text :disabled="idx === stops.length - 1" title="下移" @click="moveStop(idx, 1)">↓</el-button>
            <el-button size="small" text type="danger" title="删除" @click="stops.splice(idx, 1)">✕</el-button>
          </span>
        </div>
        <div class="stop-row__line">
          <el-date-picker
            v-model="s.range"
            type="datetimerange"
            size="small"
            start-placeholder="到达"
            end-placeholder="离开"
            style="flex: 1"
          />
          <el-select v-model="s.transportMode" size="small" placeholder="交通" clearable style="width: 96px">
            <el-option value="WALK" label="步行" />
            <el-option value="RIDE" label="骑行" />
            <el-option value="DRIVE" label="驾车" />
            <el-option value="TAXI" label="打车" />
            <el-option value="TRANSIT" label="公交" />
          </el-select>
        </div>
        <el-input v-model="s.note" size="small" maxlength="256" placeholder="备注（可选）" class="stop-row__note" />
      </div>
      <el-button v-if="stops.length < 8" size="small" class="stops-add" @click="addStop">＋ 添加站点</el-button>
      <p class="hint">站点时间须落在活动起止内、按序不倒退；时间留空则由参与者自行安排。</p>

      <p v-if="!isEdit" class="hint">创建后系统生成 6 位数字签到码；活动开始前 15 分钟至开始后 15 分钟内可输码签到。</p>
      <p v-else class="hint">改开始时间会顺延签到窗口；成员会在取消时收到通知。</p>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ isEdit ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { activityApi, mapApi, placeApi } from '../api'
import { loadAmap, createMarkerContent } from '../composables/useAmap'
import { toDate, toLocalIso } from '../utils/format'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** create = 新建；edit = 编辑已有活动 */
  mode: { type: String, default: 'create' },
  /** 从地点进入创建时传入：活动挂在这个地点上（活动页手动创建时不传，弹窗内自行选择） */
  placeId: { type: Number, default: null },
  placeName: { type: String, default: '' },
  /** edit 模式：被编辑的活动对象 */
  activity: { type: Object, default: null }
})

const emit = defineEmits(['update:modelValue', 'created', 'updated'])

const isEdit = computed(() => props.mode === 'edit')
const showVenuePicker = computed(() => !isEdit.value && props.placeId == null)
const dialogTitle = computed(() =>
  isEdit.value ? '编辑活动' : (props.placeName ? `安排活动 · ${props.placeName}` : '新建活动'))

const submitting = ref(false)
const form = reactive({ id: null, category: 'MEETING', title: '', description: '', range: [], maxMembers: 10 })

/** ===== 主场地：选已有地点 / 地图点选新建 ===== */
const maps = ref([])
const categories = ref([])
const placesByMap = ref(new Map())
const allPlaces = ref([])
const venuePlaces = computed(() => placesByMap.value.get(venue.mapId) || [])
const editableMaps = computed(() => maps.value.filter((m) => ['OWNER', 'EDITOR'].includes(m.myRole)))

const venue = reactive({
  mode: 'existing',
  mapId: null,
  placeId: null,
  name: '',
  categoryId: null,
  address: '',
  longitude: null,
  latitude: null
})

const stops = ref([])
const stopOptions = computed(() => {
  // 预填的站点可能引用了当前不可见的地点：补合成选项，避免下拉显示原始 ID
  const known = new Set(allPlaces.value.map((p) => p.id))
  const extra = stops.value
    .filter((s) => !s.coordOnly && s.placeId != null && !known.has(s.placeId))
    .map((s) => ({ id: s.placeId, label: `${s.name || '站点'}（原地点）` }))
  const seen = new Set()
  return [...allPlaces.value, ...extra].filter((p) => (seen.has(p.id) ? false : (seen.add(p.id), true)))
})

async function loadRefs() {
  try {
    const list = (await mapApi.list()) || []
    maps.value = list
    try {
      categories.value = (await placeApi.categories()) || []
    } catch (e) {
      categories.value = []
    }
    const byMap = new Map()
    const flat = []
    const seen = new Set()
    await Promise.all(list.map(async (m) => {
      try {
        const ps = (await mapApi.listPlaces(m.id)) || []
        byMap.set(m.id, ps)
        for (const p of ps) {
          if (seen.has(p.id)) continue
          seen.add(p.id)
          flat.push({
            id: p.id,
            name: p.name,
            categoryName: p.categoryName,
            address: p.address,
            mapId: m.id,
            label: `${p.name} · ${m.name}`
          })
        }
      } catch (e) {
        /* 单张地图加载失败忽略 */
      }
    }))
    placesByMap.value = byMap
    allPlaces.value = flat
    if (!venue.mapId && list.length) venue.mapId = list[0].id
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function onVenueMapChange() {
  venue.placeId = null
}

/** 地图点选：内嵌 mini 地图（打开时初始化，关闭对话框时销毁） */
const pickMapRef = ref(null)
let pickCtx = null
let pickMarker = null
let pickLoading = false

async function ensurePickMap() {
  if (pickCtx || pickLoading || !pickMapRef.value) return
  pickLoading = true
  try {
    pickCtx = await loadAmap(pickMapRef.value, (lng, lat) => {
      venue.longitude = Number(lng)
      venue.latitude = Number(lat)
      drawPickMarker()
    })
    setTimeout(() => pickCtx?.map?.resize?.(), 120)
    drawPickMarker()
  } catch (e) {
    ElMessage.error(e.message || '地图加载失败')
  } finally {
    pickLoading = false
  }
}

function drawPickMarker() {
  if (!pickCtx || venue.longitude == null || venue.latitude == null) return
  const { AMap, map } = pickCtx
  const pos = [Number(venue.longitude), Number(venue.latitude)]
  if (!pickMarker) {
    pickMarker = new AMap.Marker({
      position: pos,
      content: createMarkerContent(venue.name.trim() || '活动主场地', true),
      anchor: 'bottom-center',
      zIndex: 200
    })
    map.add(pickMarker)
  } else {
    pickMarker.setPosition(pos)
    pickMarker.setContent(createMarkerContent(venue.name.trim() || '活动主场地', true))
  }
}

function onDialogClosed() {
  if (pickCtx) {
    try {
      pickCtx.map.destroy()
    } catch (e) {
      /* 忽略销毁异常 */
    }
    pickCtx = null
    pickMarker = null
  }
}

/** 点选新建：先建地点（5 米内查重命中则直接复用），返回 placeId */
async function resolveVenuePlace() {
  if (venue.mode === 'existing') {
    if (!venue.placeId) {
      ElMessage.warning('请选择活动地点')
      return null
    }
    return venue.placeId
  }
  if (!venue.mapId) {
    ElMessage.warning('请选择可编辑的地图')
    return null
  }
  if (!venue.name.trim()) {
    ElMessage.warning('给新地点起个名字')
    return null
  }
  if (!venue.categoryId) {
    ElMessage.warning('选择地点分类')
    return null
  }
  if (venue.longitude == null || venue.latitude == null) {
    ElMessage.warning('在地图上点选活动位置')
    return null
  }
  const payload = {
    name: venue.name.trim(),
    categoryId: venue.categoryId,
    address: venue.address || null,
    longitude: Number(venue.longitude),
    latitude: Number(venue.latitude)
  }
  const res = await mapApi.addPlace(venue.mapId, payload)
  if (res?.duplicatePending) {
    const cand = res.candidates?.[0]
    if (cand?.id) {
      ElMessage.info(`附近已有「${cand.name}」，直接使用该地点`)
      return cand.id
    }
    const retry = await mapApi.addPlace(venue.mapId, { ...payload, confirmNew: true })
    return retry?.place?.id ?? null
  }
  return res?.place?.id ?? null
}

/** ===== 行程站点编辑器 ===== */
function addStop() {
  if (stops.value.length >= 8) return
  stops.value.push({
    key: `s-${Date.now()}-${stops.value.length}`,
    placeId: null,
    coordOnly: false,
    name: '',
    longitude: null,
    latitude: null,
    range: [],
    transportMode: '',
    note: ''
  })
}

function moveStop(idx, dir) {
  const j = idx + dir
  if (j < 0 || j >= stops.value.length) return
  const arr = stops.value
  const tmp = arr[idx]
  arr[idx] = arr[j]
  arr[j] = tmp
}

function buildStopsPayload() {
  return stops.value.map((s) => {
    const place = allPlaces.value.find((p) => p.id === s.placeId)
    const name = (s.name || place?.name || '').trim()
    const row = {
      name: name || '站点',
      transportMode: s.transportMode || null,
      note: (s.note || '').trim() || null
    }
    if (s.coordOnly) {
      row.placeId = null
      row.longitude = s.longitude
      row.latitude = s.latitude
    } else {
      row.placeId = s.placeId
    }
    if (s.range?.length === 2 && s.range[0] && s.range[1]) {
      row.startTime = toLocalIso(s.range[0])
      row.endTime = toLocalIso(s.range[1])
    }
    return row
  })
}

function prefillStops(activity) {
  return (activity.stops || []).map((s, i) => ({
    key: `s-${i}`,
    placeId: s.placeId ?? null,
    coordOnly: s.placeId == null && s.longitude != null,
    name: s.name || s.placeName || '',
    longitude: s.longitude != null ? Number(s.longitude) : null,
    latitude: s.latitude != null ? Number(s.latitude) : null,
    range: s.startTime
      ? [toDate(s.startTime), s.endTime ? toDate(s.endTime) : toDate(s.startTime)]
      : [],
    transportMode: s.transportMode || '',
    note: s.note || ''
  }))
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    if (props.mode === 'edit' && props.activity) {
      form.id = props.activity.id
      form.category = props.activity.category || 'MEETING'
      form.title = props.activity.title
      form.description = props.activity.description || ''
      form.range = [toDate(props.activity.startTime), toDate(props.activity.endTime)]
      form.maxMembers = props.activity.maxMembers
      stops.value = prefillStops(props.activity)
    } else {
      form.id = null
      form.category = 'MEETING'
      form.title = ''
      form.description = ''
      form.range = []
      form.maxMembers = 10
      stops.value = []
    }
    venue.mode = 'existing'
    venue.placeId = null
    venue.name = ''
    venue.categoryId = null
    venue.address = ''
    venue.longitude = null
    venue.latitude = null
    venue.mapId = null
    loadRefs()
  }
)

watch(
  () => venue.mode,
  (mode) => {
    if (mode !== 'point') return
    if (!editableMaps.value.some((m) => m.id === venue.mapId)) {
      venue.mapId = editableMaps.value[0]?.id ?? null
    }
    nextTick(() => ensurePickMap())
  }
)

async function submit() {
  if (!form.title.trim()) {
    ElMessage.warning('填一个活动标题')
    return
  }
  if (!form.range?.length || !form.range[0] || !form.range[1]) {
    ElMessage.warning('选择起止时间')
    return
  }
  const startMs = toDate(form.range[0]).getTime()
  const endMs = toDate(form.range[1]).getTime()
  if (endMs <= startMs) {
    ElMessage.warning('结束时间需晚于开始时间')
    return
  }
  for (let i = 0; i < stops.value.length; i++) {
    const s = stops.value[i]
    if (!s.coordOnly && !s.placeId) {
      ElMessage.warning(`第 ${i + 1} 站：请选择地点`)
      return
    }
    if (s.coordOnly && (s.longitude == null || s.latitude == null)) {
      ElMessage.warning(`第 ${i + 1} 站：缺少坐标`)
      return
    }
    if (s.range?.length === 2 && s.range[0] && s.range[1]) {
      const st = toDate(s.range[0]).getTime()
      const en = toDate(s.range[1]).getTime()
      if (st < startMs || en > endMs) {
        ElMessage.warning(`第 ${i + 1} 站的时间需落在活动起止内`)
        return
      }
    }
  }
  submitting.value = true
  try {
    let placeId = props.placeId
    if (showVenuePicker.value) {
      placeId = await resolveVenuePlace()
      if (!placeId) return
    }
    const payload = {
      category: form.category,
      title: form.title,
      description: form.description,
      startTime: toLocalIso(form.range[0]),
      endTime: toLocalIso(form.range[1]),
      maxMembers: form.maxMembers
    }
    const stopsPayload = buildStopsPayload()
    if (isEdit.value) {
      // 编辑：整体替换站点（空数组即清空）
      payload.stops = stopsPayload
      emit('updated', await activityApi.update(props.activity.id, payload))
      ElMessage.success('活动已更新')
    } else {
      payload.placeId = placeId
      if (stopsPayload.length) payload.stops = stopsPayload
      const created = await activityApi.create(payload)
      ElMessage.success(`已安排活动，签到码 ${created.checkinCode}（开始前 15 分钟至开始后 15 分钟内可签到）`)
      emit('created', created)
    }
    emit('update:modelValue', false)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.opt-hint {
  float: right;
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
.pick-map-wrap {
  width: 100%;
}
.pick-map {
  width: 100%;
  height: 220px;
  border-radius: 6px;
  overflow: hidden;
  background: #eef2f5;
}
.pick-map-wrap .hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: #909399;
}
.stops-title {
  margin: 10px 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
}
.stop-row {
  margin-bottom: 10px;
  padding: 8px;
  background: #f7f9fb;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}
.stop-row__head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.stop-row__seq {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  background: var(--el-color-primary);
  border-radius: 50%;
  flex-shrink: 0;
}
.stop-row__name {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.stop-row__place {
  flex: 1;
}
.stop-row__ops {
  margin-left: auto;
  flex-shrink: 0;
}
.stop-row__ops .el-button + .el-button {
  margin-left: 2px;
}
.stop-row__line {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}
.stop-row__note {
  margin-top: 6px;
}
.stops-add {
  margin-bottom: 8px;
}
@media (max-width: 600px) {
  :global(.activity-form-dialog .el-form-item) {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
  }
  :global(.activity-form-dialog .el-form-item__label) {
    width: auto !important;
    padding: 0 0 4px;
    line-height: 1.4;
  }
  :global(.activity-form-dialog .el-form-item__content) {
    width: 100%;
    margin-left: 0 !important;
  }
  :global(.activity-form-dialog .el-dialog__body) {
    max-height: 66vh;
    overflow: auto;
  }
  :global(.activity-form-dialog .el-dialog__footer) {
    position: sticky;
    bottom: 0;
    background: #fff;
    padding-top: 8px;
  }
  .stop-row__head {
    flex-wrap: wrap;
  }
  .stop-row__place {
    flex: 1 1 100%;
    order: 2;
  }
  .stop-row__ops {
    order: 1;
  }
  .stop-row__line {
    flex-wrap: wrap;
  }
  .stop-row__line > * {
    flex: 1 1 100% !important;
    width: 100% !important;
  }
  .pick-map {
    height: 180px;
  }
}
</style>
