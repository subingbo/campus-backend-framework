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
      <el-button style="width: 100%" @click="openChat">聊天</el-button>
      <el-button style="width: 100%; margin-left: 0; margin-top: 8px" @click="router.push('/assistant')">
        AI 规划活动
      </el-button>
      <el-button style="width: 100%; margin-left: 0; margin-top: 8px" @click="router.push('/activities')">
        活动日程
      </el-button>

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
      <!-- 手机端：锚定选中标记的小浮窗，替代全屏宽抽屉 -->
      <div
        v-if="isMobile && selectedPlace"
        ref="placePopRef"
        class="place-pop"
        :class="{ 'place-pop--free': !popAnchored }"
        :style="popStyle"
      >
        <div class="place-pop__head">
          <span class="place-pop__name">{{ selectedPlace.name }}</span>
          <el-tag v-if="selectedPlace.categoryName" size="small" effect="plain">
            {{ selectedPlace.categoryName }}
          </el-tag>
          <button type="button" class="place-pop__close" @click="closePlacePop">×</button>
        </div>
        <p v-if="selectedPlace.address" class="place-pop__addr">{{ selectedPlace.address }}</p>
        <p v-if="selectedPlace.description" class="place-pop__desc">{{ selectedPlace.description }}</p>
        <div class="place-pop__actions">
          <el-button size="small" :type="popCommentsOpen ? 'primary' : ''" @click="popCommentsOpen = !popCommentsOpen">
            评论{{ comments.length ? `（${comments.length}）` : '' }}
          </el-button>
          <el-button size="small" :type="popActivitiesOpen ? 'primary' : ''" @click="togglePopActivities">
            活动{{ placeActivities.length ? `（${placeActivities.length}）` : '' }}
          </el-button>
          <el-button v-if="canEdit" size="small" @click="openEdit">编辑</el-button>
          <el-button v-if="canEdit" size="small" type="danger" plain @click="removeFromMap">移除</el-button>
          <el-button size="small" @click="openPlaceChat(selectedPlace)">聊天</el-button>
          <el-dropdown v-if="otherMaps.length" @command="shareToMap">
            <el-button size="small">分享</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="m in otherMaps" :key="m.id" :command="m.id">
                  {{ m.name }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <template v-if="popCommentsOpen">
          <div class="place-pop__comments">
            <p v-if="!comments.length" class="hint">还没有评论</p>
            <div v-for="c in comments" :key="c.id" class="comment">
              <strong>{{ c.nickname }}</strong>
              <p>{{ c.content }}</p>
            </div>
          </div>
          <div class="place-pop__input">
            <el-input v-model="commentText" size="small" placeholder="写下评论" @keyup.enter="submitComment" />
            <el-button type="primary" size="small" @click="submitComment">发送</el-button>
          </div>
        </template>
        <template v-if="popActivitiesOpen">
          <div class="place-pop__comments">
            <p v-if="!placeActivities.length" class="hint">这个地点还没有安排活动</p>
            <div v-for="a in placeActivities" :key="a.id" class="activity-entry">
              <div class="activity-entry__head">
                <strong>{{ a.title }}</strong>
                <el-tag size="small" :type="phaseTagType(a.phase)">{{ phaseText(a.phase) }}</el-tag>
              </div>
              <p class="hint">{{ fmtRange(a.startTime, a.endTime) }} · {{ a.joinedCount }}/{{ a.maxMembers }} 人</p>
              <div class="activity-entry__actions">
                <template v-if="a.joined">
                  <el-tag v-if="a.checkedIn" size="small" type="success">已签到</el-tag>
                  <template v-else-if="checkinAvailable(a)">
                    <el-input
                      v-model="checkinInputs[a.id]"
                      size="small"
                      class="checkin-input"
                      placeholder="签到码"
                      @keyup.enter="doCheckin(a)"
                    />
                    <el-button size="small" type="primary" @click="doCheckin(a)">签到</el-button>
                  </template>
                  <el-button v-if="a.phase !== 'ENDED'" size="small" @click="leaveActivity(a)">取消报名</el-button>
                </template>
                <el-button v-else-if="a.phase === 'OPEN'" size="small" type="primary" @click="joinActivity(a)">报名</el-button>
                <el-button size="small" @click="toggleMembers(a)">成员</el-button>
                <el-button v-if="isCreatorOf(a)" size="small" @click="openActivityDialog(a)">编辑</el-button>
                <el-button v-if="isCreatorOf(a) && a.phase === 'OPEN'" size="small" type="danger" plain @click="cancelActivity(a)">取消</el-button>
              </div>
              <div v-if="isCreatorOf(a) && a.checkinCode" class="checkin-code">签到码：{{ a.checkinCode }}</div>
              <div v-if="membersOpenFor === a.id" class="activity-members">
                <p v-if="!activityMembers.length" class="hint">暂无成员</p>
                <div v-for="m in activityMembers" :key="m.userId" class="activity-member">
                  <span>{{ m.nickname }}（{{ m.checkedIn ? '已签' : '未签' }}）</span>
                </div>
              </div>
            </div>
            <el-button size="small" style="width: 100%" @click="openActivityDialog(null)">在此安排活动</el-button>
          </div>
        </template>
      </div>

      <!-- 聊天浮层：桌面贴右侧，手机为视口底部停靠面板 -->
      <div v-if="chatVisible" class="chat-float">
        <div class="chat-float__head">
          <strong>聊天</strong>
          <button type="button" class="chat-float__close" @click="chatVisible = false">×</button>
        </div>
        <el-tabs v-model="chatTab" class="chat-float__tabs">
          <el-tab-pane label="地图群聊" name="map">
            <ChatPanel v-if="mapConversation" :conversation="mapConversation" />
          </el-tab-pane>
          <el-tab-pane label="地点聊天" name="place">
            <el-select
              v-model="chatPlaceId"
              filterable
              size="small"
              placeholder="选择地点进入聊天室"
              style="width: 100%; margin-bottom: 8px"
              @change="onChatPlaceChange"
            >
              <el-option v-for="p in places" :key="p.id" :value="p.id" :label="p.name" />
            </el-select>
            <ChatPanel v-if="chatPlaceConversation" :conversation="chatPlaceConversation" />
          </el-tab-pane>
        </el-tabs>
      </div>
    </main>

    <el-drawer v-model="placeDrawer" :title="selectedPlace?.name || '地点'" size="380px">
      <template v-if="selectedPlace">
        <p>{{ selectedPlace.description || '暂无介绍' }}</p>
        <p><small>{{ selectedPlace.address || '暂无地址' }}</small></p>

        <div class="drawer-actions">
          <el-button v-if="canEdit" size="small" @click="openEdit">编辑</el-button>
          <el-button v-if="canEdit" size="small" type="danger" plain @click="removeFromMap">从地图移除</el-button>
          <el-button size="small" @click="openPlaceChat(selectedPlace)">地点聊天</el-button>
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

        <el-divider>本地点活动</el-divider>
        <p v-if="!placeActivities.length" class="hint">这个地点还没有安排活动</p>
        <div v-for="a in placeActivities" :key="a.id" class="activity-entry">
          <div class="activity-entry__head">
            <strong>{{ a.title }}</strong>
            <el-tag size="small" :type="phaseTagType(a.phase)">{{ phaseText(a.phase) }}</el-tag>
          </div>
          <p class="hint">
            {{ fmtRange(a.startTime, a.endTime) }} · {{ a.joinedCount }}/{{ a.maxMembers }} 人
            <template v-if="categoryText(a.category)"> · {{ categoryText(a.category) }}</template>
          </p>
          <div class="activity-entry__actions">
            <template v-if="a.joined">
              <el-tag v-if="a.checkedIn" size="small" type="success">已签到</el-tag>
              <template v-else-if="checkinAvailable(a)">
                <el-input
                  v-model="checkinInputs[a.id]"
                  size="small"
                  class="checkin-input"
                  placeholder="签到码"
                  @keyup.enter="doCheckin(a)"
                />
                <el-button size="small" type="primary" @click="doCheckin(a)">签到</el-button>
              </template>
              <el-button v-if="a.phase !== 'ENDED'" size="small" @click="leaveActivity(a)">取消报名</el-button>
            </template>
            <el-button v-else-if="a.phase === 'OPEN'" size="small" type="primary" @click="joinActivity(a)">报名</el-button>
            <el-button size="small" @click="toggleMembers(a)">成员（{{ a.joinedCount }}）</el-button>
            <template v-if="isCreatorOf(a)">
              <el-button size="small" @click="openActivityDialog(a)">编辑</el-button>
              <el-button v-if="a.phase === 'OPEN'" size="small" type="danger" plain @click="cancelActivity(a)">取消活动</el-button>
              <span v-if="a.checkinCode" class="checkin-code">现场公布签到码：{{ a.checkinCode }}</span>
            </template>
          </div>
          <div v-if="membersOpenFor === a.id" class="activity-members">
            <p v-if="!activityMembers.length" class="hint">暂无成员</p>
            <div v-for="m in activityMembers" :key="m.userId" class="activity-member">
              <span>
                {{ m.nickname }}（{{ m.checkedIn ? '已签' + fmtClock(m.checkedInAt) : '未签' }}）
              </span>
            </div>
            <div v-if="isCreatorOf(a) && a.phase !== 'ENDED'" class="invite-row">
              <el-select v-model="inviteChoice" size="small" filterable placeholder="邀请好友参加" class="invite-select">
                <el-option v-for="f in friends" :key="f.userId" :value="f.userId" :label="f.nickname" />
              </el-select>
              <el-button size="small" @click="inviteToActivity(a)">发邀请</el-button>
            </div>
          </div>
        </div>
        <el-button style="width: 100%; margin-top: 8px" @click="openActivityDialog(null)">
          在「{{ selectedPlace.name }}」安排活动
        </el-button>

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

    <el-dialog v-model="activityDialog" :title="activityForm.id ? '编辑活动' : '安排活动'" width="460px">
      <el-form :model="activityForm" label-width="72px">
        <el-form-item label="类型">
          <el-radio-group v-model="activityForm.category">
            <el-radio value="MEETING">会议</el-radio>
            <el-radio value="COMPETITION">比赛</el-radio>
            <el-radio value="OTHER">其他</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="活动">
          <el-input v-model="activityForm.title" placeholder="如：项目组周会 / 校园编程挑战赛" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="activityForm.description" type="textarea" placeholder="集合位置、比赛规则、需要带的物资等" />
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker
            v-model="activityForm.range"
            type="datetimerange"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="人数上限">
          <el-input-number v-model="activityForm.maxMembers" :min="1" :max="500" controls-position="right" />
        </el-form-item>
        <p class="hint">创建后系统生成 6 位数字签到码，活动开始前后 15 分钟可在本面板输码签到。</p>
      </el-form>
      <template #footer>
        <el-button @click="activityDialog = false">取消</el-button>
        <el-button type="primary" :loading="submittingActivity" @click="submitActivityDialog">
          {{ activityForm.id ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createAmapLocationProvider, createPlaceMarkerController, loadAmap } from '../composables/useAmap'
import { useLocation } from '../composables/useLocation'
import { activityApi, chatApi, friendApi, mapApi, placeApi } from '../api'
import { connect, myUserId, on } from '../composables/useChatSocket'
import ChatPanel from '../components/ChatPanel.vue'

const route = useRoute()
const router = useRouter()
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
let offViewportChange = null

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

/** 本地点活动：报名 / 数字签到码签到 / 成员与签到记录 */
const myId = myUserId()
const placeActivities = ref([])
const membersOpenFor = ref(null)
const activityMembers = ref([])
const friends = ref([])
const inviteChoice = ref(null)
const checkinInputs = reactive({})
const activityDialog = ref(false)
const submittingActivity = ref(false)
const activityForm = reactive({ id: null, category: 'MEETING', title: '', description: '', range: [], maxMembers: 10 })
const popActivitiesOpen = ref(false)

const chatVisible = ref(false)
const chatTab = ref('map')
const chatPlaceId = ref(null)
const mapConversation = ref(null)
const chatPlaceConversation = ref(null)
let chatRemovers = []

/** 手机端（≤768px，与工作台断点一致）用锚定标记的小浮窗替代抽屉 */
const mobileMedia = window.matchMedia('(max-width: 768px)')
const isMobile = ref(mobileMedia.matches)
const popCommentsOpen = ref(false)
const popAnchored = ref(false)
const popStyle = ref({})
const placePopRef = ref(null)
function onMediaChange(e) {
  isMobile.value = e.matches
}
mobileMedia.addEventListener('change', onMediaChange)

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
    // 活动日程“去现场”跳转：/maps/:id?place=<placeId> 自动打开该地点的面板
    const wantPlace = Number(route.query.place)
    if (wantPlace) {
      const target = places.value.find((p) => p.id === wantPlace)
      if (target) {
        openPlace(target)
      }
    }
    try {
      amapCtx = await loadAmap(mapRef.value, onMapClick)
      markerController = createPlaceMarkerController(amapCtx.AMap, amapCtx.map, openPlace)
      locationProvider = createAmapLocationProvider(amapCtx.geolocation)
      offViewportChange = markerController.onViewportChange(() => {
        if (isMobile.value && selectedPlace.value) updatePopPos()
      })
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
  placeDrawer.value = !isMobile.value
  comments.value = await mapApi.listComments(mapId.value, place.id)
  membersOpenFor.value = null
  await loadPlaceActivities()
  scrollPlaceIntoView(place.id)
  if (isMobile.value) {
    // 手机端单列布局下侧栏占满首屏，选中地点后把地图（连同浮窗）带进视野
    mapRef.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }
}

/** 浮窗锚定选中标记；地图未就绪时退化为地图区底部居中 */
function updatePopPos() {
  const place = selectedPlace.value
  if (!isMobile.value || !place) return
  const mapEl = mapRef.value
  const pt = markerController?.projectToContainer?.(place)
  if (!mapEl || !pt) {
    popAnchored.value = false
    popStyle.value = {}
    return
  }
  popAnchored.value = true
  const w = placePopRef.value?.offsetWidth || 280
  const h = placePopRef.value?.offsetHeight || 140
  const cw = mapEl.clientWidth
  const ch = mapEl.clientHeight
  // 标记锚点在点位上，上方先让出点＋标签约 48px；放不下则翻到点位下方
  const below = pt.y - 48 - h < 8
  const x = cw > w + 16 ? Math.min(Math.max(pt.x, w / 2 + 8), cw - w / 2 - 8) : cw / 2
  const top = below ? Math.min(pt.y + 12, Math.max(ch - h - 8, 8)) : pt.y - 48
  popStyle.value = {
    left: `${Math.round(x)}px`,
    top: `${Math.round(Math.max(top, 8))}px`,
    transform: below ? 'translate(-50%, 0)' : 'translate(-50%, -100%)'
  }
}

async function openChat() {
  chatVisible.value = true
  if (!mapConversation.value) {
    try {
      mapConversation.value = await chatApi.forMap(mapId.value)
    } catch (e) {
      ElMessage.error(e.message)
    }
  }
}

async function onChatPlaceChange(placeId) {
  await loadPlaceConversation(placeId)
}

async function loadPlaceConversation(placeId) {
  try {
    chatPlaceConversation.value = await chatApi.forPlace(placeId)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openPlaceChat(place) {
  if (!place) return
  chatVisible.value = true
  chatTab.value = 'place'
  chatPlaceId.value = place.id
  loadPlaceConversation(place.id)
}

function closePlacePop() {
  selectedPlace.value = null
  markerController?.setHighlight(null)
  popCommentsOpen.value = false
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

/** ===== 本地点活动：安排 / 报名 / 数字签到码签到 ===== */
const PHASE_TEXT = { OPEN: '报名中', ONGOING: '进行中', ENDED: '已结束', CANCELLED: '已取消' }
const PHASE_TAG = { OPEN: 'primary', ONGOING: 'success', ENDED: 'info', CANCELLED: 'danger' }
const CATEGORY_TEXT = { MEETING: '会议', COMPETITION: '比赛', OTHER: '其他' }

function phaseText(phase) {
  return PHASE_TEXT[phase] || phase
}

function phaseTagType(phase) {
  return PHASE_TAG[phase] || 'info'
}

function categoryText(category) {
  return CATEGORY_TEXT[category] || ''
}

/** LocalDateTime 在响应里多为 [年,月,日,时,分,秒] 数组，兼容 ISO 字符串 */
function toDate(value) {
  if (!value) return null
  if (Array.isArray(value)) return new Date(value[0], value[1] - 1, value[2] ?? 0, value[3] ?? 0, value[4] ?? 0, value[5] ?? 0)
  return new Date(value)
}

function pad2(n) {
  return String(n).padStart(2, '0')
}

function fmt(value) {
  const d = toDate(value)
  if (!d || Number.isNaN(d.getTime())) return ''
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

function fmtRange(a, b) {
  return `${fmt(a)} ~ ${fmt(b)}`
}

function fmtClock(value) {
  const d = toDate(value)
  if (!d || Number.isNaN(d.getTime())) return ''
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** 签到窗口与后端一致：开始前 0 分钟起、checkinOpenUntil（默认开始后 15 分钟）止 — 即前后共 15 分钟 */
function checkinAvailable(a) {
  const start = toDate(a.startTime)
  const end = toDate(a.checkinOpenUntil)
  if (!start || !end) return false
  const now = Date.now()
  return now >= start.getTime() && now <= end.getTime()
}

function isCreatorOf(a) {
  return myId != null && a.creatorId === myId
}

async function loadPlaceActivities() {
  if (!selectedPlace.value) return
  try {
    placeActivities.value = await activityApi.listByPlace(selectedPlace.value.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function togglePopActivities() {
  popActivitiesOpen.value = !popActivitiesOpen.value
  if (popActivitiesOpen.value) {
    loadPlaceActivities()
  }
}

function replaceActivity(updated) {
  placeActivities.value = placeActivities.value.map((a) => (a.id === updated.id ? updated : a))
}

async function joinActivity(a) {
  try {
    replaceActivity(await activityApi.join(a.id))
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function leaveActivity(a) {
  try {
    replaceActivity(await activityApi.leave(a.id))
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doCheckin(a) {
  const code = String(checkinInputs[a.id] || '').trim()
  if (!code) {
    ElMessage.warning('请输入签到码')
    return
  }
  try {
    replaceActivity(await activityApi.checkin(a.id, code))
    delete checkinInputs[a.id]
    ElMessage.success('签到成功')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function toggleMembers(a) {
  if (membersOpenFor.value === a.id) {
    membersOpenFor.value = null
    return
  }
  try {
    activityMembers.value = await activityApi.members(a.id)
    membersOpenFor.value = a.id
    if (isCreatorOf(a) && !friends.value.length) {
      friends.value = await friendApi.list()
    }
    inviteChoice.value = null
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function inviteToActivity(a) {
  if (!inviteChoice.value) {
    ElMessage.warning('先选择要邀请的好友')
    return
  }
  try {
    await activityApi.invite(a.id, inviteChoice.value)
    ElMessage.success('已发出召集邀请，对方接受后加入成员')
    inviteChoice.value = null
    activityMembers.value = await activityApi.members(a.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function cancelActivity(a) {
  await ElMessageBox.confirm('取消后所有成员都会收到通知，操作不可撤销', '取消活动')
  try {
    await activityApi.cancel(a.id)
    placeActivities.value = placeActivities.value.filter((x) => x.id !== a.id)
    if (membersOpenFor.value === a.id) {
      membersOpenFor.value = null
    }
    ElMessage.success('活动已取消，在线成员会收到提醒')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openActivityDialog(a) {
  if (a) {
    activityForm.id = a.id
    activityForm.category = a.category || 'MEETING'
    activityForm.title = a.title
    activityForm.description = a.description || ''
    activityForm.range = [toDate(a.startTime), toDate(a.endTime)]
    activityForm.maxMembers = a.maxMembers
  } else {
    activityForm.id = null
    activityForm.category = 'MEETING'
    activityForm.title = ''
    activityForm.description = ''
    activityForm.range = []
    activityForm.maxMembers = 10
  }
  activityDialog.value = true
}

function toLocalIso(date) {
  const d = toDate(date)
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}

async function submitActivityDialog() {
  if (!activityForm.title.trim()) {
    ElMessage.warning('填一个活动标题')
    return
  }
  if (!activityForm.range?.length || !activityForm.range[0] || !activityForm.range[1]) {
    ElMessage.warning('选择起止时间')
    return
  }
  const payload = {
    placeId: selectedPlace.value.id,
    category: activityForm.category,
    title: activityForm.title,
    description: activityForm.description,
    startTime: toLocalIso(activityForm.range[0]),
    endTime: toLocalIso(activityForm.range[1]),
    maxMembers: activityForm.maxMembers
  }
  submittingActivity.value = true
  try {
    if (activityForm.id) {
      replaceActivity(await activityApi.update(activityForm.id, payload))
      ElMessage.success('活动已更新')
    } else {
      const created = await activityApi.create(payload)
      placeActivities.value = [...placeActivities.value, created].sort((x, y) => {
        const dx = toDate(x.startTime)?.getTime() ?? 0
        const dy = toDate(y.startTime)?.getTime() ?? 0
        return dx - dy
      })
      ElMessage.success(`已安排活动，签到码 ${created.checkinCode}（开始后 15 分钟内有效）`)
    }
    activityDialog.value = false
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    submittingActivity.value = false
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

watch([selectedPlace, popCommentsOpen], () => nextTick(updatePopPos))
watch(isMobile, (mobile) => {
  if (!selectedPlace.value) return
  if (mobile) {
    placeDrawer.value = false
    nextTick(updatePopPos)
  } else {
    placeDrawer.value = true
  }
})

onMounted(() => {
  connect()
  initPage()
  chatRemovers = [
    on('PLACE_CHANGED', (frame) => {
      ElMessage.info(`${frame.actorNickname} ${frame.summary}「${frame.placeName}」`)
      if (frame.mapId === mapId.value) {
        refresh()
      }
    }),
    on('ACTIVITY_REMINDER', (frame) => {
      ElMessage.warning(frame.summary)
      if (frame.placeName) {
        ElMessage.info(`地点：${frame.placeName}`)
      }
    }),
    on('ACTIVITY_INVITED', (frame) => {
      ElMessage.warning(frame.summary)
    }),
    on('ACTIVITY_CANCELLED', (frame) => {
      ElMessage.info(frame.summary)
      if (selectedPlace.value) {
        loadPlaceActivities()
      }
    })
  ]
})
onUnmounted(() => {
  for (const off of chatRemovers) off()
  mobileMedia.removeEventListener('change', onMediaChange)
  offViewportChange?.()
  amapCtx?.map?.destroy()
})
</script>

<style scoped>
.workspace {
  display: grid;
  grid-template-columns: 320px 1fr;
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
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.place-item.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: 0 0 0 1px rgba(0, 82, 217, 0.2);
}
.place-item__name {
  font-weight: 600;
  font-size: 0.9rem;
  line-height: 1.3;
}
.place-item__cat {
  margin-top: 4px;
  font-size: 11px;
  color: var(--el-color-primary);
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
  background: var(--el-color-primary);
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
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);
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
.place-pop {
  position: absolute;
  z-index: 30;
  box-sizing: border-box;
  width: min(300px, calc(100vw - 24px));
  padding: 10px 12px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(31, 56, 88, 0.18);
  font-size: 13px;
}
.place-pop--free {
  right: 12px;
  bottom: 16px;
}
.place-pop__head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.place-pop__name {
  flex: 1;
  min-width: 0;
  font-weight: 600;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.place-pop__close {
  border: none;
  background: none;
  padding: 2px 4px;
  font-size: 18px;
  line-height: 1;
  color: rgba(0, 0, 0, 0.45);
  cursor: pointer;
}
.place-pop__addr {
  margin: 4px 0 0;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.place-pop__desc {
  margin: 4px 0 0;
  color: rgba(0, 0, 0, 0.72);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.place-pop__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.place-pop__actions .el-button + .el-button {
  margin-left: 0;
}
.place-pop__comments {
  max-height: 30vh;
  overflow-y: auto;
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px solid #f0f0f0;
}
.place-pop__input {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 8px;
}
.place-pop__input .el-input {
  flex: 1;
}
.chat-float {
  position: absolute;
  top: 12px;
  right: 12px;
  bottom: 12px;
  z-index: 31;
  display: flex;
  flex-direction: column;
  width: min(340px, calc(100% - 24px));
  padding: 10px 12px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(31, 56, 88, 0.18);
}
.chat-float__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 6px;
}
.chat-float__close {
  border: none;
  background: none;
  padding: 2px 6px;
  font-size: 18px;
  line-height: 1;
  color: rgba(0, 0, 0, 0.45);
  cursor: pointer;
}
.chat-float__tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.chat-float__tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.chat-float__tabs :deep(.el-tab-pane) {
  height: 100%;
}
/* 手机端：脱离地图容器改为视口停靠，不随页面滚动、不被裁切；
   用媒体查询而非 JS 断点，窗口跨断点即时生效 */
@media (max-width: 768px) {
  .chat-float {
    position: fixed;
    top: auto;
    left: 12px;
    right: 12px;
    bottom: 12px;
    width: auto;
    height: 62vh;
    height: 62dvh;
  }
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

/* ===== 本地点活动 ===== */
.activity-entry {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
}
.activity-entry__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.activity-entry__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.activity-entry__actions .el-button + .el-button {
  margin-left: 0;
}
.checkin-input {
  width: 96px;
}
.checkin-code {
  font-variant-numeric: tabular-nums;
  color: var(--el-color-primary);
  font-weight: 600;
}
.activity-members {
  margin-top: 6px;
  border-top: 1px dashed var(--el-border-color-lighter);
  padding-top: 6px;
}
.activity-member {
  font-size: 12px;
  color: var(--el-text-color-regular);
  line-height: 1.8;
}
.invite-row {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}
.invite-select {
  flex: 1;
}
</style>
