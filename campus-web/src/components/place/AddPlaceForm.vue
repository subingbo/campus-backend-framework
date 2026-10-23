<script setup>
import { computed, ref } from 'vue'
import AppIcon from '../ui/AppIcon.vue'

const props = defineProps({
  form: { type: Object, required: true },
  categories: { type: Array, default: () => [] },
  mapName: { type: String, default: '' },
  submitting: { type: Boolean, default: false },
  locating: { type: Boolean, default: false },
  mapReady: { type: Boolean, default: false }
})

const emit = defineEmits(['cancel', 'submit', 'fill-location', 'locate-pick', 'repick'])

const coordsOpen = ref(false)

const coordText = computed(() => {
  const lng = Number(props.form.longitude)
  const lat = Number(props.form.latitude)
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return ''
  return `${lng.toFixed(6)}, ${lat.toFixed(6)}`
})
</script>

<template>
  <form class="ui-place-form" @submit.prevent="$emit('submit')">
    <header class="ui-place-form__head">
      <p class="ui-eyebrow">添加地点</p>
      <button class="ui-icon-btn" type="button" aria-label="取消添加" @click="$emit('cancel')">
        <AppIcon name="x" :size="18" />
      </button>
      <h2 class="ui-place-form__title">标记一个新地点</h2>
      <p v-if="mapName" class="ui-place-form__sub">添加到「{{ mapName }}」</p>
    </header>

    <div class="ui-place-form__scroll">
      <label class="ui-field">
        <span class="ui-field__label">名称</span>
        <input v-model="form.name" class="ui-input" type="text" maxlength="80" placeholder="例如：东区图书馆" autocomplete="off">
      </label>

      <div class="ui-field">
        <span class="ui-field__label">分类</span>
        <div v-if="categories.length" class="ui-form-chips">
          <button
            v-for="c in categories"
            :key="c.id"
            class="ui-chip"
            :class="{ 'is-active': form.categoryId === c.id }"
            type="button"
            @click="form.categoryId = c.id"
          >
            {{ c.name }}
          </button>
        </div>
        <p v-else class="ui-field__hint">暂无可选分类</p>
      </div>

      <label class="ui-field">
        <span class="ui-field__label">描述</span>
        <textarea
          v-model="form.description"
          class="ui-textarea"
          rows="3"
          maxlength="500"
          placeholder="有什么值得告诉朋友的？比如插座多、桂花拿铁不错"
        />
      </label>

      <label class="ui-field">
        <span class="ui-field__label">文字地址</span>
        <input v-model="form.address" class="ui-input" type="text" maxlength="200" placeholder="选填，例如：南门内侧 12 栋旁">
      </label>

      <div class="ui-field">
        <div class="ui-field__label-row">
          <span class="ui-field__label">位置</span>
          <button class="ui-link-btn" type="button" :disabled="!mapReady || locating" @click="$emit('locate-pick')">
            以我的位置选点
          </button>
        </div>
        <div class="ui-coord-box">
          <AppIcon name="pin" :size="15" />
          <span v-if="coordText">{{ coordText }}</span>
          <span v-else class="ui-coord-box__empty">还没有选择位置</span>
        </div>
        <div class="ui-coord-actions">
          <button class="ui-link-btn" type="button" :disabled="!mapReady" @click="$emit('repick')">
            在地图上选点
          </button>
          <button class="ui-link-btn" type="button" :disabled="!mapReady || locating" @click="$emit('fill-location')">
            {{ locating ? '定位中…' : '填入当前位置' }}
          </button>
          <button class="ui-link-btn ui-link-btn--muted" type="button" @click="coordsOpen = !coordsOpen">
            {{ coordsOpen ? '收起坐标' : '手动输入坐标' }}
          </button>
        </div>

        <div v-if="coordsOpen" class="ui-coord-inputs">
          <label>
            <span>经度</span>
            <input v-model.number="form.longitude" class="ui-input" type="number" step="0.000001" placeholder="113.384128">
          </label>
          <label>
            <span>纬度</span>
            <input v-model.number="form.latitude" class="ui-input" type="number" step="0.000001" placeholder="23.045678">
          </label>
        </div>
      </div>
    </div>

    <footer class="ui-place-form__foot">
      <button class="ui-btn ui-btn--ghost" type="button" @click="$emit('cancel')">取消</button>
      <button class="ui-btn ui-btn--primary" type="submit" :disabled="submitting">
        {{ submitting ? '提交中…' : '添加地点' }}
      </button>
    </footer>
  </form>
</template>
