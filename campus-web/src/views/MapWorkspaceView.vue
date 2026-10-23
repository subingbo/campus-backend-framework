<template>
  <div class="workspace ui-v2">
    <div ref="mapRef" class="map-canvas" />

    <div v-if="pageLoading" class="workspace-loading">
      <div class="workspace-loading__inner">
        <span class="ui-spinner" />
        <span>正在加载地图…</span>
      </div>
    </div>

    <!-- 桌面端：地点列表面板 -->
    <FloatingPanel
      v-if="!isMobile && panelOpen"
      class="place-panel"
      as="aside"
    >
      <template #header>
        <div class="place-panel__head">
          <button class="ui-icon-btn" type="button" aria-label="返回我的地图" @click="goHome">
            <AppIcon name="arrow-left" :size="18" />
          </button>
          <button class="place-panel__group" type="button" @click="openGroup">
            <span class="place-panel__group-name">{{ detail?.name || '加载中' }}</span>
            <span class="place-panel__group-sub">
              {{ detail?.memberCount ?? 0 }} 位成员 · {{ detail?.placeCount ?? 0 }} 个地点
            </span>
          </button>
          <button class="ui-icon-btn" type="button" aria-label="地图群聊" @click="openChat">
            <AppIcon name="message" :size="18" />
          </button>
          <button class="ui-icon-btn" type="button" aria-label="收起地点列表" @click="panelOpen = false">
            <AppIcon name="panel" :size="18" />
          </button>
        </div>

        <SearchBar
          v-model="placeSearchQuery"
          class="place-panel__search"
          placeholder="搜索名称、地址或分类"
        />

        <div class="ui-chips place-panel__chips">
          <button
            class="ui-chip"
            :class="{ 'is-active': !categoryFilter }"
            type="button"
            @click="categoryFilter = ''"
          >
            全部
          </button>
          <button
            v-for="cat in placeCategoryFilters"
            :key="cat"
            class="ui-chip"
            :class="{ 'is-active': categoryFilter === cat }"
            type="button"
            @click="toggleCategoryFilter(cat)"
          >
            {{ cat }}
          </button>
        </div>

        <div class="place-panel__meta">
          <span>地点</span>
          <span>{{ filteredPlaces.length }} / {{ places.length }}</span>
        </div>
      </template>

      <div ref="placeListRef" class="place-list">
        <PlaceListItem
          v-for="p in filteredPlaces"
          :key="p.id"
          :place="p"
          :data-place-id="p.id"
          :active="selectedPlace?.id === p.id"
          @select="selectPlaceFromList"
        />
        <EmptyState
          v-if="!places.length"
          icon="pin"
          title="还没有地点"
          description="点击地图添加第一个地点，或使用右上角的添加按钮。"
        />
        <EmptyState
          v-else-if="!filteredPlaces.length"
          icon="search"
          title="没有匹配的地点"
          description="换个关键词，或清除分类筛选。"
          action="清除筛选"
          @action="clearFilters"
        />
      </div>

      <template #footer>
        <div class="place-panel__foot">
          <button class="ui-text-action" type="button" :disabled="!canEdit || !mapReady" @click="startAdd">
            <AppIcon name="plus" :size="14" />
            添加地点
          </button>
          <button class="ui-text-action ui-text-action--muted" type="button" :disabled="!mapReady" @click="locateMe">
            <AppIcon name="crosshair" :size="14" />
            定位
          </button>
        </div>
      </template>
    </FloatingPanel>

    <!-- 列表收起后的群组胶囊 -->
    <div v-if="!isMobile && !panelOpen && !groupOpen" class="group-pill" :class="{ 'is-guarded': pillGuard }">
      <button class="ui-icon-btn" type="button" aria-label="返回我的地图" @click="goHome">
        <AppIcon name="arrow-left" :size="18" />
      </button>
      <button class="group-pill__main" type="button" @click="openGroup">
        <span class="group-pill__name">{{ detail?.name || '共享地图' }}</span>
        <span class="group-pill__sub">
          {{ detail?.memberCount ?? 0 }} 位成员 · {{ detail?.placeCount ?? 0 }} 个地点
        </span>
      </button>
      <button class="group-pill__places" type="button" @click="openPanelFromPill">
        <AppIcon name="panel" :size="14" />
        地点 <span class="ui-count">{{ places.length }}</span>
      </button>
      <button class="ui-icon-btn" type="button" aria-label="地图群聊" @click="openChat">
        <AppIcon name="message" :size="18" />
      </button>
    </div>

    <!-- 桌面端：地点详情抽屉 -->
    <DetailDrawer
      v-if="!isMobile"
      :model-value="placeDrawer"
      label="地点详情"
      @update:model-value="onPlaceDrawerToggle"
    >
      <PlaceDetailPanel
        v-if="selectedPlace"
        :place="selectedPlaceDetail || selectedPlace"
        :comments="comments"
        :activities="placeActivities"
        :other-maps="otherMaps"
        :can-edit="canEdit"
        :comment-text="commentText"
        @update:comment-text="commentText = $event"
        @close="closePlace"
        @edit="openEdit"
        @remove="removeFromMap"
        @chat="openPlaceChat(selectedPlace)"
        @navigate="navigateSelectedPlace"
        @share="shareToMap"
        @submit-comment="submitComment"
        @create-activity="openActivityForm(null)"
        @edit-activity="openActivityForm"
        @activity-updated="replaceActivity"
        @activity-cancelled="onActivityCancelled"
      />
    </DetailDrawer>

    <!-- 桌面端：添加地点抽屉 -->
    <DetailDrawer
      v-if="!isMobile"
      :model-value="addDialog"
      label="添加地点"
      @update:model-value="onAddDrawerToggle"
    >
      <AddPlaceForm
        v-if="addDialog"
        :form="addForm"
        :categories="categories"
        :map-name="detail?.name || ''"
        :submitting="submitting"
        :locating="locating"
        :map-ready="mapReady"
        @cancel="cancelAdd"
        @submit="submitAdd"
        @fill-location="fillFormWithLocation"
        @locate-pick="locateAndPick"
        @repick="repick"
      />
    </DetailDrawer>

    <!-- 桌面端：群组信息 -->
    <div v-if="!isMobile && groupOpen" class="ui-popover-backdrop" @click="closeGroup" />
    <FloatingPanel
      v-if="!isMobile && groupOpen"
      class="group-panel-ui"
      as="aside"
      :scroll="false"
    >
      <GroupInfoPanel
        v-if="detail"
        :detail="detail"
        :can-manage="detail.myRole === 'OWNER'"
        :show-guide="showGuide"
        @close="closeGroup"
        @copy-invite="copyInvite"
        @change-role="changeRole"
        @dismiss-guide="dismissGuide"
      />
    </FloatingPanel>

    <!-- 桌面端：聊天浮层 -->
    <FloatingPanel
      v-if="!isMobile && chatVisible"
      class="chat-panel-ui"
      as="aside"
      :scroll="false"
    >
      <template #header>
        <h2 class="chat-panel-ui__title">聊天</h2>
        <button class="ui-icon-btn" type="button" aria-label="关闭聊天" @click="chatVisible = false">
          <AppIcon name="x" :size="18" />
        </button>
      </template>

      <div class="ui-tabs">
        <button
          class="ui-tab"
          :class="{ 'is-active': chatTab === 'map' }"
          type="button"
          @click="chatTab = 'map'"
        >
          地图群聊
        </button>
        <button
          class="ui-tab"
          :class="{ 'is-active': chatTab === 'place' }"
          type="button"
          @click="chatTab = 'place'"
        >
          地点聊天
        </button>
      </div>

      <select
        v-if="chatTab === 'place'"
        v-model="chatPlaceId"
        class="ui-select chat-place-select"
        aria-label="选择地点进入聊天室"
        @change="onChatPlaceChange(chatPlaceId)"
      >
        <option :value="null">选择地点进入聊天室</option>
        <option v-for="p in places" :key="p.id" :value="p.id">{{ p.name }}</option>
      </select>

      <div class="chat-panel-ui__body">
        <ChatPanel v-if="chatTab === 'map' && mapConversation" :conversation="mapConversation" />
        <ChatPanel v-else-if="chatTab === 'place' && chatPlaceConversation" :conversation="chatPlaceConversation" />
        <p v-else class="ui-detail-hint">
          {{ chatTab === 'map' ? '正在打开群聊…' : '选择地点后进入聊天室' }}
        </p>
      </div>
    </FloatingPanel>

    <!-- 桌面端：地图控件与提示 -->
    <div
      v-if="!isMobile"
      class="map-controls-ui"
      :class="{ 'is-shifted': placeDrawer || addDialog || chatVisible }"
    >
      <MapActionButton icon="crosshair" label="定位到我" @click="locateMe" />
      <MapActionButton icon="plus" label="放大" @click="zoomIn" />
      <MapActionButton icon="minus" label="缩小" @click="zoomOut" />
    </div>

    <div
      v-if="!isMobile && !mapError"
      class="map-hint-ui"
      :class="{ 'is-shifted': panelOpen }"
    >
      <AppIcon name="layers" :size="14" />
      {{ places.length }} 个地点 · 点击标记查看详情
    </div>

    <!-- 选点提示 -->
    <div v-if="addMode" class="pick-hint">
      <AppIcon name="crosshair" :size="15" />
      点击地图选择位置
      <button class="ui-text-action" type="button" @click="toggleAddMode">取消</button>
    </div>

    <!-- 地图加载失败 -->
    <div v-if="mapError" class="map-error-ui">
      <span class="map-error-ui__icon">
        <AppIcon name="alert-circle" :size="20" />
      </span>
      <strong>地图没能加载</strong>
      <p>{{ mapError }}</p>
      <div class="map-error-ui__actions">
        <button v-if="canEdit" class="ui-btn ui-btn--primary" type="button" @click="openAddWithDefaultCenter">
          手动添加
        </button>
        <button class="ui-btn ui-btn--ghost" type="button" @click="retryMap">重试</button>
      </div>
    </div>

    <!-- 移动端：紧凑顶栏 -->
    <header v-if="isMobile" class="mobile-topbar-ui">
      <button class="ui-icon-btn" type="button" aria-label="返回我的地图" @click="goHome">
        <AppIcon name="arrow-left" :size="18" />
      </button>
      <button class="mobile-topbar__title" type="button" @click="openGroup">
        {{ detail?.name || '共享地图' }}
      </button>
      <button class="ui-icon-btn" type="button" aria-label="搜索地点" @click="openMobileSearch">
        <AppIcon name="search" :size="18" />
      </button>
      <button class="ui-icon-btn" type="button" aria-label="地图群聊" @click="openChat">
        <AppIcon name="message" :size="18" />
      </button>
    </header>

    <!-- 移动端：底部触发条与 FAB -->
    <template v-if="isMobile && !mobileSheetOpen && !mapError">
      <button class="mobile-place-trigger" type="button" @click="openMobileList()">
        <span class="mobile-place-trigger__icon">
          <AppIcon name="panel" :size="18" />
        </span>
        <span class="mobile-place-trigger__main">
          <span class="mobile-place-trigger__title">地点 · {{ places.length }}</span>
          <span class="mobile-place-trigger__sub">查看附近地点与筛选</span>
        </span>
        <AppIcon name="chevron-down" :size="16" class="mobile-place-trigger__chev" />
      </button>

      <div class="mobile-fabs">
        <button class="ui-fab" type="button" aria-label="定位到我" @click="locateMe">
          <AppIcon name="crosshair" :size="20" />
        </button>
        <button v-if="canEdit" class="ui-fab ui-fab--primary" type="button" aria-label="添加地点" @click="startAdd">
          <AppIcon name="plus" :size="22" />
        </button>
      </div>
    </template>

    <!-- 移动端：地点列表 Sheet -->
    <BottomSheet
      v-if="isMobile"
      v-model="mobileListOpen"
      label="地点列表"
      height="68dvh"
      :teleport="false"
      :animated="false"
      panel-class="ui-v2"
    >
      <div class="ui-mobile-list">
        <header class="ui-mobile-list__head">
          <div class="ui-mobile-list__head-main">
            <p class="ui-eyebrow">地点</p>
            <h2 class="ui-mobile-list__title">{{ detail?.name || '共享地图' }}</h2>
            <p class="ui-mobile-list__sub">
              {{ filteredPlaces.length }} 个地点 · {{ detail?.memberCount ?? 0 }} 位成员
            </p>
          </div>
          <button class="ui-icon-btn" type="button" aria-label="关闭地点列表" @click="mobileListOpen = false">
            <AppIcon name="x" :size="18" />
          </button>
        </header>

        <SearchBar
          v-model="placeSearchQuery"
          class="ui-mobile-list__search"
          size="lg"
          placeholder="搜索名称、地址或分类"
          :autofocus="mobileSearchFocus"
        />

        <div class="ui-chips ui-mobile-list__chips">
          <button
            class="ui-chip"
            :class="{ 'is-active': !categoryFilter }"
            type="button"
            @click="categoryFilter = ''"
          >
            全部
          </button>
          <button
            v-for="cat in placeCategoryFilters"
            :key="cat"
            class="ui-chip"
            :class="{ 'is-active': categoryFilter === cat }"
            type="button"
            @click="toggleCategoryFilter(cat)"
          >
            {{ cat }}
          </button>
        </div>

        <div class="ui-mobile-list__body">
          <PlaceListItem
            v-for="p in filteredPlaces"
            :key="p.id"
            :place="p"
            :data-place-id="p.id"
            :active="selectedPlace?.id === p.id"
            @select="selectPlaceFromList"
          />
          <EmptyState
            v-if="!places.length"
            icon="pin"
            title="还没有地点"
            description="点击地图添加第一个地点。"
          />
          <EmptyState
            v-else-if="!filteredPlaces.length"
            icon="search"
            title="没有匹配的地点"
            description="换个关键词，或清除分类筛选。"
            action="清除筛选"
            @action="clearFilters"
          />
        </div>

        <footer class="ui-mobile-list__foot">
          <button class="ui-text-action" type="button" :disabled="!canEdit || !mapReady" @click="startAddFromMobile">
            <AppIcon name="plus" :size="14" />
            添加地点
          </button>
          <button class="ui-text-action ui-text-action--muted" type="button" :disabled="!mapReady" @click="locateMe">
            <AppIcon name="crosshair" :size="14" />
            定位
          </button>
        </footer>
      </div>
    </BottomSheet>

    <!-- 移动端：地点详情 Sheet -->
    <BottomSheet
      v-if="isMobile"
      v-model="placeDrawer"
      label="地点详情"
      height="72dvh"
      :teleport="false"
      :animated="false"
      panel-class="ui-v2"
    >
      <PlaceDetailPanel
        v-if="selectedPlace"
        :place="selectedPlaceDetail || selectedPlace"
        :comments="comments"
        :activities="placeActivities"
        :other-maps="otherMaps"
        :can-edit="canEdit"
        :compact="true"
        :comment-text="commentText"
        @update:comment-text="commentText = $event"
        @close="closePlace"
        @edit="openEdit"
        @remove="removeFromMap"
        @chat="openPlaceChat(selectedPlace)"
        @navigate="navigateSelectedPlace"
        @share="shareToMap"
        @submit-comment="submitComment"
        @create-activity="openActivityForm(null)"
        @edit-activity="openActivityForm"
        @activity-updated="replaceActivity"
        @activity-cancelled="onActivityCancelled"
      />
    </BottomSheet>

    <!-- 移动端：添加地点 Sheet -->
    <BottomSheet
      v-if="isMobile"
      v-model="addDialog"
      label="添加地点"
      height="88dvh"
      :teleport="false"
      :animated="false"
      panel-class="ui-v2"
    >
      <AddPlaceForm
        v-if="addDialog"
        :form="addForm"
        :categories="categories"
        :map-name="detail?.name || ''"
        :submitting="submitting"
        :locating="locating"
        :map-ready="mapReady"
        @cancel="cancelAdd"
        @submit="submitAdd"
        @fill-location="fillFormWithLocation"
        @locate-pick="locateAndPick"
        @repick="repick"
      />
    </BottomSheet>

    <!-- 移动端：群组信息 Sheet -->
    <BottomSheet
      v-if="isMobile"
      v-model="groupOpen"
      label="群组信息"
      height="70dvh"
      :teleport="false"
      :animated="false"
      panel-class="ui-v2"
    >
      <GroupInfoPanel
        v-if="detail"
        :detail="detail"
        :can-manage="detail.myRole === 'OWNER'"
        :show-guide="showGuide"
        @close="closeGroup"
        @copy-invite="copyInvite"
        @change-role="changeRole"
        @dismiss-guide="dismissGuide"
      />
    </BottomSheet>

    <!-- 移动端：聊天 Sheet -->
    <BottomSheet
      v-if="isMobile"
      v-model="chatVisible"
      label="聊天"
      height="88dvh"
      :teleport="false"
      :animated="false"
      panel-class="ui-v2"
    >
      <div class="ui-chat-shell">
        <header class="ui-chat-head">
          <h2 class="ui-chat-head__title">聊天</h2>
          <button class="ui-icon-btn" type="button" aria-label="关闭聊天" @click="chatVisible = false">
            <AppIcon name="x" :size="18" />
          </button>
        </header>

        <div class="ui-tabs">
          <button
            class="ui-tab"
            :class="{ 'is-active': chatTab === 'map' }"
            type="button"
            @click="chatTab = 'map'"
          >
            地图群聊
          </button>
          <button
            class="ui-tab"
            :class="{ 'is-active': chatTab === 'place' }"
            type="button"
            @click="chatTab = 'place'"
          >
            地点聊天
          </button>
        </div>

        <select
          v-if="chatTab === 'place'"
          v-model="chatPlaceId"
          class="ui-select chat-place-select"
          aria-label="选择地点进入聊天室"
          @change="onChatPlaceChange(chatPlaceId)"
        >
          <option :value="null">选择地点进入聊天室</option>
          <option v-for="p in places" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>

        <div class="ui-chat-body">
          <ChatPanel v-if="chatTab === 'map' && mapConversation" :conversation="mapConversation" />
          <ChatPanel v-else-if="chatTab === 'place' && chatPlaceConversation" :conversation="chatPlaceConversation" />
          <p v-else class="ui-detail-hint">
            {{ chatTab === 'map' ? '正在打开群聊…' : '选择地点后进入聊天室' }}
          </p>
        </div>
      </div>
    </BottomSheet>

    <!-- 添加地点：重复检测 -->
    <el-dialog v-model="dupDialog" class="ui-dialog" title="附近已有相近地点" width="min(480px, 94vw)">
      <p class="ui-dialog__hint">是否补充已有地点，避免重复？</p>
      <div class="ui-radio-list">
        <label v-for="c in dupCandidates" :key="c.id" class="ui-radio">
          <input v-model="dupChoice" type="radio" :value="c.id">
          <span>{{ c.name }}（约 {{ c.distanceMeters?.toFixed(1) }}m）</span>
        </label>
        <label class="ui-radio">
          <input v-model="dupChoice" type="radio" :value="-1">
          <span>仍要新建地点</span>
        </label>
      </div>
      <template #footer>
        <button class="ui-btn ui-btn--ghost" type="button" @click="cancelDuplicate">取消</button>
        <button class="ui-btn ui-btn--primary" type="button" @click="confirmDuplicate">确定</button>
      </template>
    </el-dialog>

    <!-- 编辑地点 -->
    <el-dialog v-model="editDialog" class="ui-dialog" title="编辑地点" width="min(460px, 94vw)">
      <form class="ui-dialog-form" @submit.prevent="saveEdit">
        <label class="ui-dialog-field">
          <span>名称</span>
          <input v-model="editForm.name" class="ui-input" type="text" maxlength="80">
        </label>
        <label class="ui-dialog-field">
          <span>分类</span>
          <select v-model="editForm.categoryId" class="ui-select">
            <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </label>
        <label class="ui-dialog-field">
          <span>描述</span>
          <textarea v-model="editForm.description" class="ui-textarea" rows="3" maxlength="500" />
        </label>
        <label class="ui-dialog-field">
          <span>地址</span>
          <input v-model="editForm.address" class="ui-input" type="text" maxlength="200">
        </label>
      </form>
      <template #footer>
        <button class="ui-btn ui-btn--ghost" type="button" @click="editDialog = false">取消</button>
        <button class="ui-btn ui-btn--primary" type="button" @click="saveEdit">保存</button>
      </template>
    </el-dialog>

    <el-dialog v-model="removeDialog" class="ui-dialog" title="从地图移除" width="min(400px, 94vw)">
      <p class="ui-dialog__hint">仅从当前地图移除，不会删除地点本体。</p>
      <template #footer>
        <button class="ui-btn ui-btn--ghost" type="button" @click="removeDialog = false">取消</button>
        <button class="ui-btn ui-btn--danger" type="button" @click="confirmRemove">移除</button>
      </template>
    </el-dialog>

    <ActivityFormDialog
      v-model="activityDialogOpen"
      :mode="activityDialogMode"
      :place-id="selectedPlace?.id"
      :place-name="selectedPlace?.name"
      :activity="activityEditTarget"
      @created="onActivityCreated"
      @updated="replaceActivity"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createAmapLocationProvider, createPlaceMarkerController, loadAmap } from '../composables/useAmap'
import { useLocation } from '../composables/useLocation'
import { activityApi, chatApi, mapApi, placeApi } from '../api'
import { on } from '../composables/useChatSocket'
import { toDate } from '../utils/format'
import { openAmapNavigation } from '../utils/amapNavigation'
import ChatPanel from '../components/ChatPanel.vue'
import ActivityFormDialog from '../components/ActivityFormDialog.vue'
import AppIcon from '../components/ui/AppIcon.vue'
import FloatingPanel from '../components/ui/FloatingPanel.vue'
import SearchBar from '../components/ui/SearchBar.vue'
import MapActionButton from '../components/ui/MapActionButton.vue'
import PlaceListItem from '../components/ui/PlaceListItem.vue'
import DetailDrawer from '../components/ui/DetailDrawer.vue'
import BottomSheet from '../components/ui/BottomSheet.vue'
import EmptyState from '../components/ui/EmptyState.vue'
import PlaceDetailPanel from '../components/place/PlaceDetailPanel.vue'
import AddPlaceForm from '../components/place/AddPlaceForm.vue'
import GroupInfoPanel from '../components/place/GroupInfoPanel.vue'

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
let addPreviewMarker = null

const placeSearchQuery = ref('')
const categoryFilter = ref('')
const placeListRef = ref(null)
const panelOpen = ref(true)
const groupOpen = ref(false)
const mobileListOpen = ref(false)
const mobileSearchFocus = ref(false)
const selectedPlaceDetail = ref(null)

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
const removeDialog = ref(false)
const selectedPlace = ref(null)
const comments = ref([])
const commentText = ref('')

/** 本地点活动：报名/签到/成员/邀请在共享操作卡内完成；这里只管列表与表单弹窗 */
const placeActivities = ref([])
const activityDialogOpen = ref(false)
const activityDialogMode = ref('create')
const activityEditTarget = ref(null)

/** 首次进入的三步引导：可关闭，关闭后记住 */
const GUIDE_KEY = 'campus.guide.workspace.v1'
const showGuide = ref(!localStorage.getItem(GUIDE_KEY))
function dismissGuide() {
  localStorage.setItem(GUIDE_KEY, '1')
  showGuide.value = false
}

const chatVisible = ref(false)
const chatTab = ref('map')
const chatPlaceId = ref(null)
const mapConversation = ref(null)
const chatPlaceConversation = ref(null)
let chatRemovers = []

const mobileMedia = window.matchMedia('(max-width: 768px)')
const compactMedia = window.matchMedia('(max-width: 1279px)')
const isMobile = ref(mobileMedia.matches)
const isCompact = ref(compactMedia.matches)
if (isCompact.value) panelOpen.value = false

const dupDialog = ref(false)
const dupCandidates = ref([])
const dupChoice = ref(null)
const pendingAddPayload = ref(null)

const editDialog = ref(false)
const editForm = reactive({ name: '', description: '', address: '', categoryId: null })

const canEdit = computed(() => ['OWNER', 'EDITOR'].includes(detail.value?.myRole))
const otherMaps = computed(() => allMaps.value.filter((m) => m.id !== mapId.value))
let restorePanelOnGroupClose = false
const pillGuard = ref(false)
let pillGuardTimer = null

const mobileSheetOpen = computed(
  () =>
    mobileListOpen.value ||
    placeDrawer.value ||
    addDialog.value ||
    groupOpen.value ||
    chatVisible.value
)

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

function clearFilters() {
  placeSearchQuery.value = ''
  categoryFilter.value = ''
}

function scrollPlaceIntoView(placeId) {
  const root = placeListRef.value
  if (!root || !placeId) return
  const el = root.querySelector(`[data-place-id="${placeId}"]`)
  el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

function openPanelFromPill() {
  if (chatVisible.value) chatVisible.value = false
  if (isCompact.value) closePlace()
  panelOpen.value = true
}

function refreshAddPreview() {
  if ((!addDialog.value && !addMode.value) || addForm.longitude == null || addForm.latitude == null) return
  setAddPreview(Number(addForm.longitude), Number(addForm.latitude))
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
      closePlace()
    }
  }
  refreshAddPreview()
}

async function loadSelectedPlaceDetail(id) {
  if (!id) {
    selectedPlaceDetail.value = null
    return
  }
  try {
    selectedPlaceDetail.value = await placeApi.get(id)
  } catch {
    selectedPlaceDetail.value = null
  }
}

async function refresh() {
  detail.value = await mapApi.detail(mapId.value)
  places.value = await mapApi.listPlaces(mapId.value)
  syncMarkers(false)
  if (selectedPlace.value) {
    await loadSelectedPlaceDetail(selectedPlace.value.id)
  }
}

async function selectPlaceFromList(place) {
  markerController?.focusPlace(place)
  await openPlace(place)
}

async function initMap() {
  if (!mapRef.value) return
  if (amapCtx?.map) {
    amapCtx.map.destroy()
    amapCtx = null
    markerController = null
    mapReady.value = false
  }
  mapError.value = ''
  try {
    amapCtx = await loadAmap(mapRef.value, onMapClick, { controls: false })
    markerController = createPlaceMarkerController(amapCtx.AMap, amapCtx.map, openPlace)
    locationProvider = createAmapLocationProvider(amapCtx.geolocation)
    mapReady.value = true
    syncMarkers(true)
  } catch (e) {
    mapError.value = e.message
  }
}

async function retryMap() {
  await initMap()
}

async function initPage() {
  pageLoading.value = true
  try {
    categories.value = await placeApi.categories()
    allMaps.value = await mapApi.list()
    await refresh()
    await initMap()
    // 活动日程“去现场”跳转：/maps/:id?place=<placeId> 自动打开该地点的面板
    const wantPlace = Number(route.query.place)
    if (wantPlace) {
      const target = places.value.find((p) => p.id === wantPlace)
      if (target) {
        openPlace(target)
      }
    }
    // 消息中心深链：/maps/:id?chat=1 直接打开本图群聊
    if (route.query.chat === '1') {
      await openChat()
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
  if (!addMode.value) clearAddPreview()
}

function startAdd() {
  if (!canEdit.value) {
    ElMessage.warning('需要编辑或群主权限才能添加地点')
    return
  }
  if (!mapReady.value) {
    ElMessage.warning('地图还没准备好，请稍后再试')
    return
  }
  addMode.value = true
  placeDrawer.value = false
  addDialog.value = false
  mobileListOpen.value = false
  groupOpen.value = false
  chatVisible.value = false
  clearAddPreview()
}

function startAddFromMobile() {
  mobileListOpen.value = false
  startAdd()
}

function openAddWithDefaultCenter() {
  addForm.longitude = defaultLng
  addForm.latitude = defaultLat
  addForm.categoryId = categories.value[0]?.id ?? null
  addDialog.value = true
  addMode.value = false
  setAddPreview(defaultLng, defaultLat)
}

function onMapClick(lng, lat) {
  if (!addMode.value) return
  addForm.longitude = lng
  addForm.latitude = lat
  if (!addForm.categoryId) addForm.categoryId = categories.value[0]?.id ?? null
  setAddPreview(lng, lat)
  addDialog.value = true
  addMode.value = false
}

function setAddPreview(lng, lat) {
  if (!amapCtx?.AMap || !amapCtx.map) return
  if (!addPreviewMarker) {
    addPreviewMarker = new amapCtx.AMap.Marker({
      position: [lng, lat],
      content: '<div class="campus-add-preview"><span></span></div>',
      anchor: 'center',
      zIndex: 400,
      bubble: false
    })
    amapCtx.map.add(addPreviewMarker)
  } else {
    addPreviewMarker.setPosition([lng, lat])
  }
}

function clearAddPreview() {
  if (addPreviewMarker && amapCtx?.map) {
    amapCtx.map.remove(addPreviewMarker)
  }
  addPreviewMarker = null
}

function cancelAdd() {
  addDialog.value = false
  addMode.value = false
  clearAddPreview()
}

function repick() {
  addDialog.value = false
  addMode.value = true
  clearAddPreview()
}

function resetAddForm() {
  addForm.name = ''
  addForm.description = ''
  addForm.address = ''
  addForm.categoryId = null
  addForm.longitude = null
  addForm.latitude = null
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

/** 高德导航：优先用实时定位作起点，定位失败则打开终点地点页 */
async function navigateSelectedPlace() {
  const place = selectedPlace.value
  if (!place || place.longitude == null || place.latitude == null) return
  const loc = await locate()
  const from = loc ? { lng: loc.lng, lat: loc.lat, name: '我的位置' } : null
  openAmapNavigation({
    from,
    to: { lng: Number(place.longitude), lat: Number(place.latitude), name: place.name }
  })
}

async function locateAndPick() {
  addDialog.value = false
  const result = await locateToMap({ actionText: '已定位，请在地图上点击您附近的位置' })
  if (!result) return
  addMode.value = true
  clearAddPreview()
}

async function locateAndMark() {
  const result = await locateToMap({ actionText: '已定位，经纬度已填入表单' })
  if (!result) return
  applyMyLocationToAddForm(result)
  addMode.value = false
  addDialog.value = true
  setAddPreview(result.lng, result.lat)
}

async function fillFormWithLocation() {
  const result = await locateToMap({ recenter: false, actionText: '已填入当前位置' })
  if (!result) return
  applyMyLocationToAddForm(result)
  setAddPreview(result.lng, result.lat)
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
      pendingAddPayload.value = payload
      dupCandidates.value = res.candidates || []
      dupChoice.value = dupCandidates.value[0]?.id ?? -1
      addDialog.value = false
      addMode.value = false
      dupDialog.value = true
      return
    }
    ElMessage.success('已添加')
    addDialog.value = false
    addMode.value = false
    clearAddPreview()
    resetAddForm()
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

function cancelDuplicate() {
  dupDialog.value = false
  addDialog.value = true
  if (addForm.longitude != null && addForm.latitude != null) {
    setAddPreview(Number(addForm.longitude), Number(addForm.latitude))
  }
}

function onPlaceDrawerToggle(value) {
  if (value) {
    placeDrawer.value = true
  } else {
    closePlace()
  }
}

function onAddDrawerToggle(value) {
  if (value) {
    addDialog.value = true
  } else {
    cancelAdd()
  }
}

async function openPlace(place) {
  selectedPlace.value = place
  selectedPlaceDetail.value = null
  commentText.value = ''
  comments.value = []
  placeActivities.value = []
  markerController?.setHighlight(place.id)
  placeDrawer.value = true
  mobileListOpen.value = false
  addDialog.value = false
  groupOpen.value = false
  chatVisible.value = false
  if (isCompact.value && !isMobile.value) panelOpen.value = false
  nextTick(() => scrollPlaceIntoView(place.id))
  try {
    comments.value = await mapApi.listComments(mapId.value, place.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
  await loadPlaceActivities()
  loadSelectedPlaceDetail(place.id)
}

function closePlace() {
  selectedPlace.value = null
  selectedPlaceDetail.value = null
  placeDrawer.value = false
  markerController?.setHighlight(null)
}

function openEdit() {
  if (!selectedPlace.value) return
  editForm.name = selectedPlace.value.name
  editForm.description = selectedPlace.value.description
  editForm.address = selectedPlace.value.address
  editForm.categoryId = selectedPlace.value.categoryId
  editDialog.value = true
}

async function saveEdit() {
  if (!selectedPlace.value) return
  try {
    await placeApi.update(selectedPlace.value.id, { ...editForm })
    ElMessage.success('已更新')
    editDialog.value = false
    await refresh()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function removeFromMap() {
  if (!selectedPlace.value) return
  removeDialog.value = true
}

async function confirmRemove() {
  if (!selectedPlace.value) return
  try {
    await mapApi.removePlace(mapId.value, selectedPlace.value.id)
    ElMessage.success('已移除')
    removeDialog.value = false
    closePlace()
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
  const content = commentText.value.trim()
  if (!content || !selectedPlace.value) return
  try {
    await mapApi.addComment(mapId.value, selectedPlace.value.id, { content })
    commentText.value = ''
    comments.value = await mapApi.listComments(mapId.value, selectedPlace.value.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** ===== 本地点活动：列表与表单弹窗；报名/签到/成员/邀请在共享操作卡 ActivityEntryCard 内 ===== */
async function loadPlaceActivities() {
  if (!selectedPlace.value) return
  try {
    placeActivities.value = await activityApi.listByPlace(selectedPlace.value.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function replaceActivity(updated) {
  placeActivities.value = placeActivities.value.map((a) => (a.id === updated.id ? updated : a))
}

/** 操作卡内取消活动：从地点面板列表移除（在线成员经 WS 另行收到通知） */
function onActivityCancelled(a) {
  placeActivities.value = placeActivities.value.filter((x) => x.id !== a.id)
}

/** 操作卡「编辑」/ 底部「安排活动」→ 共享表单弹窗（创建与编辑同一入口） */
function openActivityForm(a) {
  activityEditTarget.value = a || null
  activityDialogMode.value = a ? 'edit' : 'create'
  activityDialogOpen.value = true
}

function onActivityCreated(created) {
  placeActivities.value = [...placeActivities.value, created].sort((x, y) => {
    const dx = toDate(x.startTime)?.getTime() ?? 0
    const dy = toDate(y.startTime)?.getTime() ?? 0
    return dx - dy
  })
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

async function openChat() {
  placeDrawer.value = false
  mobileListOpen.value = false
  addDialog.value = false
  groupOpen.value = false
  panelOpen.value = false
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
  if (placeId == null) {
    chatPlaceConversation.value = null
    return
  }
  await loadPlaceConversation(placeId)
}

async function loadPlaceConversation(placeId) {
  chatPlaceConversation.value = null
  try {
    chatPlaceConversation.value = await chatApi.forPlace(placeId)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openPlaceChat(place) {
  if (!place) return
  placeDrawer.value = false
  mobileListOpen.value = false
  addDialog.value = false
  groupOpen.value = false
  panelOpen.value = false
  chatVisible.value = true
  chatTab.value = 'place'
  chatPlaceId.value = place.id
  loadPlaceConversation(place.id)
}

function openGroup() {
  placeDrawer.value = false
  mobileListOpen.value = false
  addDialog.value = false
  chatVisible.value = false
  restorePanelOnGroupClose = panelOpen.value
  panelOpen.value = false
  groupOpen.value = true
}

function closeGroup() {
  groupOpen.value = false
  if (restorePanelOnGroupClose) {
    panelOpen.value = true
  }
  restorePanelOnGroupClose = false
}

function openMobileList({ focusSearch = false } = {}) {
  placeDrawer.value = false
  addDialog.value = false
  groupOpen.value = false
  chatVisible.value = false
  mobileSearchFocus.value = focusSearch
  mobileListOpen.value = true
}

function openMobileSearch() {
  openMobileList({ focusSearch: true })
}

function goHome() {
  router.push('/')
}

function zoomIn() {
  amapCtx?.map?.zoomIn?.()
}

function zoomOut() {
  amapCtx?.map?.zoomOut?.()
}

function onKeydown(event) {
  if (event.defaultPrevented) return
  if (removeDialog.value || editDialog.value || dupDialog.value || activityDialogOpen.value) return
  if (event.key !== 'Escape') return
  if (addMode.value) {
    toggleAddMode()
  } else if (addDialog.value) {
    cancelAdd()
  } else if (dupDialog.value) {
    cancelDuplicate()
  } else if (groupOpen.value) {
    closeGroup()
  } else if (chatVisible.value) {
    chatVisible.value = false
  } else if (placeDrawer.value) {
    closePlace()
  } else if (!isMobile.value && panelOpen.value && isCompact.value) {
    panelOpen.value = false
  }
}

function onMediaChange(e) {
  isMobile.value = e.matches
  if (e.matches) {
    panelOpen.value = false
    placeDrawer.value = false
    addDialog.value = false
    groupOpen.value = false
    chatVisible.value = false
    mobileListOpen.value = false
  } else {
    mobileListOpen.value = false
    panelOpen.value = !isCompact.value
  }
}

function onCompactChange(e) {
  isCompact.value = e.matches
  if (e.matches) {
    panelOpen.value = false
  } else {
    panelOpen.value = true
  }
}

watch(panelOpen, (open, wasOpen) => {
  if (!open && wasOpen) {
    pillGuard.value = true
    clearTimeout(pillGuardTimer)
    pillGuardTimer = setTimeout(() => {
      pillGuard.value = false
    }, 300)
  }
})

watch(
  () => selectedPlace.value?.id,
  () => {
    commentText.value = ''
  }
)

watch(placeDrawer, (open) => {
  if (!open && selectedPlace.value) {
    selectedPlace.value = null
    selectedPlaceDetail.value = null
    markerController?.setHighlight(null)
  }
})

watch(addDialog, (open) => {
  if (!open) clearAddPreview()
})

onMounted(() => {
  initPage()
  mobileMedia.addEventListener('change', onMediaChange)
  compactMedia.addEventListener('change', onCompactChange)
  window.addEventListener('keydown', onKeydown)
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
  compactMedia.removeEventListener('change', onCompactChange)
  window.removeEventListener('keydown', onKeydown)
  clearTimeout(pillGuardTimer)
  clearAddPreview()
  amapCtx?.map?.destroy()
})
</script>
