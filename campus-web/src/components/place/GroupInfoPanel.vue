<script setup>
import { computed } from 'vue'
import AppIcon from '../ui/AppIcon.vue'
import SectionTitle from '../ui/SectionTitle.vue'

const props = defineProps({
  detail: { type: Object, required: true },
  canManage: { type: Boolean, default: false },
  showGuide: { type: Boolean, default: false }
})

defineEmits(['close', 'copy-invite', 'change-role', 'dismiss-guide'])

const members = computed(() => props.detail?.members || [])

const ROLE_LABEL = { OWNER: '群主', EDITOR: '编辑', VIEWER: '查看' }

function roleLabel(role) {
  return ROLE_LABEL[role] || role || ''
}

function initial(member) {
  const name = member.nickname || member.username || '?'
  return name.slice(0, 1).toUpperCase()
}
</script>

<template>
  <div class="ui-group-panel">
    <header class="ui-group-panel__head">
      <div class="ui-group-panel__head-main">
        <p class="ui-eyebrow">群组信息</p>
        <h2 class="ui-group-panel__title">{{ detail.name }}</h2>
        <p class="ui-group-panel__sub">
          {{ detail.memberCount ?? members.length }} 位成员 · {{ detail.placeCount ?? 0 }} 个地点
          <span v-if="detail.myRole" class="ui-pill ui-pill--muted">{{ roleLabel(detail.myRole) }}</span>
        </p>
      </div>
      <button class="ui-icon-btn" type="button" aria-label="关闭群组信息" @click="$emit('close')">
        <AppIcon name="x" :size="18" />
      </button>
    </header>

    <div class="ui-group-panel__scroll">
      <p class="ui-group-panel__desc">{{ detail.description || '暂无简介' }}</p>

      <div v-if="detail.inviteCode" class="ui-invite-row">
        <div>
          <span class="ui-invite-row__label">邀请码</span>
          <strong class="ui-invite-row__code">{{ detail.inviteCode }}</strong>
        </div>
        <button class="ui-btn ui-btn--secondary ui-btn--sm" type="button" @click="$emit('copy-invite')">
          <AppIcon name="copy" :size="14" />
          复制
        </button>
      </div>

      <section class="ui-group-section">
        <SectionTitle title="成员" :count="members.length" />
        <div v-for="m in members" :key="m.userId" class="ui-member-row">
          <span class="ui-member-avatar">{{ initial(m) }}</span>
          <span class="ui-member-name">{{ m.nickname || m.username }}</span>
          <select
            v-if="canManage && m.role !== 'OWNER'"
            class="ui-select ui-select--sm"
            :value="m.role"
            :aria-label="`修改 ${m.nickname || m.username} 的角色`"
            @change="$emit('change-role', m.userId, $event.target.value)"
          >
            <option value="EDITOR">编辑</option>
            <option value="VIEWER">查看</option>
          </select>
          <span v-else class="ui-pill ui-pill--muted">{{ roleLabel(m.role) }}</span>
        </div>
      </section>

      <section v-if="showGuide" class="ui-guide">
        <div class="ui-guide__head">
          <strong>三步上手</strong>
          <button class="ui-icon-btn ui-icon-btn--sm" type="button" aria-label="关闭引导" @click="$emit('dismiss-guide')">
            <AppIcon name="x" :size="14" />
          </button>
        </div>
        <p>① 点左侧地点或地图标记，查看详情与评论</p>
        <p>② 在地点详情底部「安排活动」发起，或报名已有活动</p>
        <p>③ 活动开始前 15 分钟到开始后 15 分钟内输码签到</p>
      </section>
    </div>
  </div>
</template>
