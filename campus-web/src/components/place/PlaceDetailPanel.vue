<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppIcon from '../ui/AppIcon.vue'
import SectionTitle from '../ui/SectionTitle.vue'
import ActivityEntryCard from '../ActivityEntryCard.vue'
import { fmt } from '../../utils/format'

const props = defineProps({
  place: { type: Object, required: true },
  comments: { type: Array, default: () => [] },
  activities: { type: Array, default: () => [] },
  otherMaps: { type: Array, default: () => [] },
  canEdit: { type: Boolean, default: false },
  compact: { type: Boolean, default: false },
  commentText: { type: String, default: '' }
})

const emit = defineEmits([
  'close',
  'edit',
  'remove',
  'chat',
  'navigate',
  'share',
  'update:commentText',
  'submit-comment',
  'create-activity',
  'edit-activity',
  'activity-updated',
  'activity-cancelled'
])

const menu = ref('')
const copied = ref(false)
let copiedTimer = null

const authText = computed(() =>
  props.place?.creatorNickname ? `由 ${props.place.creatorNickname} 添加` : ''
)

const coordText = computed(() => {
  const lng = Number(props.place?.longitude)
  const lat = Number(props.place?.latitude)
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return ''
  return `${lng.toFixed(6)}, ${lat.toFixed(6)}`
})

function toggleMenu(name) {
  menu.value = menu.value === name ? '' : name
}

function onDocumentClick(event) {
  if (!event.target.closest('.ui-menu-anchor')) menu.value = ''
}

async function copyCoord() {
  if (!coordText.value) return
  try {
    await navigator.clipboard?.writeText(coordText.value)
    copied.value = true
    clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => {
      copied.value = false
    }, 1500)
  } catch {
    // 剪贴板不可用时不影响详情浏览
  }
}

function onShare(mapId) {
  menu.value = ''
  emit('share', mapId)
}

function onEdit() {
  menu.value = ''
  emit('edit')
}

function onRemove() {
  menu.value = ''
  emit('remove')
}

function submitComment() {
  if (!props.commentText.trim()) return
  emit('submit-comment')
}

watch(
  () => props.place?.id,
  () => {
    menu.value = ''
    copied.value = false
  }
)

onMounted(() => document.addEventListener('click', onDocumentClick))
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  clearTimeout(copiedTimer)
})
</script>

<template>
  <div class="ui-detail-panel" :class="{ 'ui-detail-panel--compact': compact }">
    <header class="ui-detail-panel__head">
      <div class="ui-detail-panel__eyebrow">
        <span v-if="place.categoryName" class="ui-pill">{{ place.categoryName }}</span>
        <button class="ui-icon-btn ui-detail-panel__close" type="button" aria-label="关闭详情" @click="$emit('close')">
          <AppIcon name="x" :size="18" />
        </button>
      </div>

      <h2 class="ui-detail-panel__title">{{ place.name }}</h2>

      <p v-if="place.address" class="ui-detail-panel__address">
        <AppIcon name="pin" :size="14" />
        <span>{{ place.address }}</span>
      </p>

      <p v-if="authText || coordText" class="ui-detail-panel__auth">
        <span v-if="authText">{{ authText }}</span>
        <button v-if="coordText" class="ui-link-btn" type="button" @click="copyCoord">
          {{ copied ? '已复制坐标' : coordText }}
        </button>
      </p>

      <div class="ui-detail-panel__actions">
        <button class="ui-btn ui-btn--primary" type="button" @click="$emit('navigate')">
          <AppIcon name="navigation" :size="15" />
          导航
        </button>
        <button class="ui-icon-btn" type="button" aria-label="地点聊天" @click="$emit('chat')">
          <AppIcon name="message" :size="18" />
        </button>

        <div class="ui-menu-anchor">
          <button class="ui-icon-btn" type="button" aria-label="分享到其他地图" @click.stop="toggleMenu('share')">
            <AppIcon name="share" :size="18" />
          </button>
          <div v-if="menu === 'share'" class="ui-menu" @click.stop>
            <p class="ui-menu__label">分享到其他地图</p>
            <button
              v-for="m in otherMaps"
              :key="m.id"
              class="ui-menu__item"
              type="button"
              @click="onShare(m.id)"
            >
              {{ m.name }}
            </button>
            <p v-if="!otherMaps.length" class="ui-menu__empty">暂无其他地图</p>
          </div>
        </div>

        <div class="ui-menu-anchor">
          <button class="ui-icon-btn" type="button" aria-label="更多操作" @click.stop="toggleMenu('more')">
            <AppIcon name="more" :size="18" />
          </button>
          <div v-if="menu === 'more'" class="ui-menu" @click.stop>
            <button v-if="canEdit" class="ui-menu__item" type="button" @click="onEdit">编辑地点</button>
            <button v-if="coordText" class="ui-menu__item" type="button" @click="copyCoord">
              {{ copied ? '已复制坐标' : '复制坐标' }}
            </button>
            <button v-if="canEdit" class="ui-menu__item ui-menu__item--danger" type="button" @click="onRemove">
              从地图移除
            </button>
          </div>
        </div>
      </div>
    </header>

    <div class="ui-detail-panel__scroll">
      <p class="ui-detail-panel__desc">{{ place.description || '暂无介绍' }}</p>

      <div class="ui-rule" />

      <section class="ui-detail-section">
        <SectionTitle title="本地点活动" :count="activities.length" />
        <p v-if="!activities.length" class="ui-detail-hint">这个地点还没有安排活动</p>
        <ActivityEntryCard
          v-for="a in activities"
          :key="a.id"
          :activity="a"
          :compact="compact"
          @updated="$emit('activity-updated', $event)"
          @cancelled="$emit('activity-cancelled', $event)"
          @edit="$emit('edit-activity', $event)"
        />
        <button v-if="canEdit" class="ui-text-action" type="button" @click="$emit('create-activity')">
          在此安排活动
        </button>
      </section>

      <div class="ui-rule" />

      <section class="ui-detail-section">
        <SectionTitle title="评论" :count="comments.length" />
        <p v-if="!comments.length" class="ui-detail-hint">还没有评论，来说第一句</p>
        <article v-for="c in comments" :key="c.id" class="ui-comment">
          <div class="ui-comment__head">
            <strong>{{ c.nickname }}</strong>
            <span>{{ fmt(c.createdAt) }}</span>
          </div>
          <p>{{ c.content }}</p>
        </article>
      </section>

      <div class="ui-detail-panel__scroll-pad" />
    </div>

    <div class="ui-detail-panel__composer">
      <input
        :value="commentText"
        type="text"
        placeholder="写下评论…"
        aria-label="写下评论"
        @input="$emit('update:commentText', $event.target.value)"
        @keydown.enter.prevent="submitComment"
      >
      <button
        class="ui-send"
        type="button"
        aria-label="发送评论"
        :disabled="!commentText.trim()"
        @click="submitComment"
      >
        <AppIcon name="send" :size="16" />
      </button>
    </div>
  </div>
</template>
