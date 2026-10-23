<template>
  <div v-if="view" class="chat-agent-card">
    <h4>
      <AppIcon name="route" :size="14" />
      {{ view.title || 'AI 规划（未确认）' }}
      <span v-if="view.planVersion" class="chat-agent-card__ver">v{{ view.planVersion }}</span>
    </h4>
    <span v-if="view.constraintStatus === 'CONFLICT'" class="chat-agent-card__badge conflict">有成员时间冲突</span>
    <span v-else-if="view.constraintStatus === 'TENTATIVE'" class="chat-agent-card__badge tentative">暂定：含未授权忙闲成员</span>
    <span v-else-if="view.constraintStatus === 'FEASIBLE'" class="chat-agent-card__badge ok">忙闲已核验</span>
    <span v-if="view.savedActivityId" class="chat-agent-card__badge ok">已保存为活动</span>

    <p v-for="(s, i) in (view.stops || []).slice(0, 8)" :key="i" class="chat-agent-card__stop">
      {{ s.seq || i + 1 }}. {{ s.name }}
      <span v-if="s.startTime">（{{ fmtClock(s.startTime) }}<template v-if="s.endTime">-{{ fmtClock(s.endTime) }}</template><template
        v-if="s.stayMinutes">，停留 {{ s.stayMinutes }} 分钟</template>）</span>
      <span v-if="!s.locationVisible" class="chat-agent-card__hidden">
        <AppIcon name="alert-circle" :size="11" />
        地点详情仅发起人可见
      </span>
      <button
        v-if="s.longitude != null && s.latitude != null"
        type="button"
        class="chat-agent-card__nav"
        title="在高德中导航到这里"
        @click.stop="navigateStop(s)"
      >
        <AppIcon name="navigation" :size="11" />
        导航
      </button>
    </p>
    <p v-for="(seg, i) in (view.routeSegments || []).slice(0, 8)" :key="'seg' + i" class="chat-agent-card__seg">
      ↳ {{ modeLabel(seg.transportMode) }} {{ seg.durationMinutes }} 分钟
    </p>
    <p v-if="view.totalTravelMinutes" class="chat-agent-card__seg chat-agent-card__seg--total">
      <AppIcon name="route" :size="11" />
      全程交通约 {{ view.totalTravelMinutes }} 分钟
      <span v-if="view.routeConfidence === 'ESTIMATED'" class="chat-agent-card__routebadge">估算（未验证）</span>
      <span v-else-if="view.routeConfidence === 'AMAP'" class="chat-agent-card__routebadge ok">高德实测</span>
      <span v-else-if="view.routeConfidence === 'PARTIAL'" class="chat-agent-card__routebadge">部分未核验</span>
    </p>
    <p v-if="coverageNote" class="chat-agent-card__cov">{{ coverageNote }}</p>
    <div v-if="members.length || conflicts.length || view.warnings?.length" class="chat-agent-card__people">
      <button type="button" class="chat-agent-card__people-toggle" @click="peopleOpen = !peopleOpen">
        <span>
          <AppIcon name="users" :size="11" />
          成员 {{ knownMemberCount }}/{{ members.length }}
        </span>
        <span v-if="conflicts.length" class="chat-agent-card__people-conflict">
          <AppIcon name="alert-circle" :size="11" />
          {{ conflicts.length }} 个冲突
        </span>
        <span class="chat-agent-card__people-chevron">{{ peopleOpen ? '收起' : '展开' }}</span>
      </button>
      <template v-if="peopleOpen">
        <p v-for="member in members" :key="'m-' + member.userId" class="chat-agent-card__member">
          {{ member.nickname }}
          <em>{{ member.status === 'UNKNOWN' ? '未授权' : '已核验' }}</em>
        </p>
        <p v-for="(conflict, i) in conflicts" :key="'c-' + i" class="chat-agent-card__conflict">
          ⚠ {{ conflict.nickname }} {{ fmtClock(conflict.startTime) }}–{{ fmtClock(conflict.endTime) }}
          与计划冲突<template v-if="conflict.severity === 'BLOCKING'">（重叠 {{ conflict.overlapMinutes }} 分钟）</template>；
          建议：{{ conflict.suggestion }}
        </p>
        <p v-for="(warning, i) in (view.warnings || [])" :key="'w-' + i" class="chat-agent-card__warn">{{ warning }}</p>
      </template>
    </div>
    <div v-if="view.reason" class="chat-agent-card__reason">
      <AppIcon name="sparkles" :size="11" />
      <MarkdownText :text="view.reason" />
    </div>
    <div v-if="showActions" class="chat-agent-card__actions">
      <el-button v-if="showViewAction" size="small" @click="emit('view', card)">查看完整方案</el-button>
      <el-button
        v-if="card.askerId === me"
        size="small"
        type="primary"
        @click="emit('continue', card)"
      >继续规划</el-button>
      <span v-else class="chat-agent-card__hint">
        由 {{ card.askerNickname || '发起人' }} 发起，仅发起人可保存
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import MarkdownText from './MarkdownText.vue'
import AppIcon from './ui/AppIcon.vue'
import { fmtClock } from '../utils/format'
import { openAmapNavigation } from '../utils/amapNavigation'

const props = defineProps({
  card: { type: Object, required: true },
  me: { type: Number, default: null },
  showActions: { type: Boolean, default: true },
  showViewAction: { type: Boolean, default: true }
})
const emit = defineEmits(['view', 'continue'])

const peopleOpen = ref(false)
const view = computed(() => props.card?.plan || props.card || null)
const members = computed(() => view.value?.members || [])
const conflicts = computed(() => view.value?.conflicts || [])
const knownMemberCount = computed(() => members.value.filter((m) => m.status === 'KNOWN').length)
const coverageNote = computed(() => {
  const list = members.value
  if (!list.length) return ''
  const unknown = list.filter((m) => m.status === 'UNKNOWN').length
  if (!unknown) return `已核验 ${list.length} 位成员忙闲`
  return `忙闲已核验 ${list.length - unknown} 人，${unknown} 人未授权（方案暂定）`
})

function modeLabel(mode) {
  if (mode === 'RIDE') return '骑行'
  if (mode === 'DRIVE') return '驾车'
  if (mode === 'TAXI') return '打车'
  if (mode === 'TRANSIT') return '公交'
  return '步行'
}

function navigateStop(stop) {
  openAmapNavigation({
    to: { lng: Number(stop.longitude), lat: Number(stop.latitude), name: stop.name },
    mode: stop.transportMode
  })
}
</script>

<style scoped>
.chat-agent-card {
  margin: 8px 0;
  padding: 12px;
  border: 0;
  border-radius: var(--ui-radius-lg);
  background: var(--ui-surface-2);
  color: var(--ui-ink-2);
}
.chat-agent-card h4 {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 0 7px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ui-ink);
  overflow-wrap: anywhere;
}
.chat-agent-card h4 :deep(svg) {
  flex: none;
  color: var(--ui-accent);
}
.chat-agent-card__ver {
  margin-left: 2px;
  color: var(--ui-ink-3);
  font-size: 11px;
  font-weight: 400;
}
.chat-agent-card__badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  margin: 0 6px 6px 0;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 500;
}
.chat-agent-card__badge.ok { background: var(--ui-success-tint); color: var(--ui-success); }
.chat-agent-card__badge.tentative { background: var(--ui-warning-tint); color: var(--ui-warning); }
.chat-agent-card__badge.conflict { background: var(--ui-danger-tint); color: var(--ui-danger); }
.chat-agent-card__stop { margin: 5px 0; font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.chat-agent-card__nav {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 5px;
  padding: 2px 7px;
  border: 0;
  border-radius: 999px;
  background: var(--ui-surface);
  color: var(--ui-accent);
  cursor: pointer;
  font-size: 10.5px;
}
.chat-agent-card__nav:hover { background: var(--ui-surface-3); }
.chat-agent-card__nav:focus-visible,
.chat-agent-card__people-toggle:focus-visible {
  outline: 2px solid var(--ui-accent);
  outline-offset: 2px;
}
.chat-agent-card__hidden {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 5px;
  color: var(--ui-ink-3);
  font-size: 10.5px;
}
.chat-agent-card__seg { margin: 2px 0 2px 12px; color: var(--ui-ink-3); font-size: 11px; overflow-wrap: anywhere; }
.chat-agent-card__seg--total {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-left: 0;
  color: var(--ui-ink-2);
}
.chat-agent-card__seg--total :deep(svg) {
  color: var(--ui-accent);
}
.chat-agent-card__routebadge { margin-left: 5px; color: var(--ui-ink-3); font-size: 10.5px; }
.chat-agent-card__routebadge.ok { color: var(--ui-success); }
.chat-agent-card__cov { margin: 5px 0; color: var(--ui-ink-2); font-size: 11px; }
.chat-agent-card__people { margin-top: 8px; border-top: 1px solid var(--ui-line); padding-top: 8px; }
.chat-agent-card__people-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 32px;
  padding: 2px 0;
  border: 0;
  background: transparent;
  color: var(--ui-ink-2);
  cursor: pointer;
  font-size: 11px;
  text-align: left;
}
.chat-agent-card__people-toggle > span {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.chat-agent-card__people-conflict { color: var(--ui-danger); }
.chat-agent-card__people-chevron { margin-left: auto; color: var(--ui-accent); }
.chat-agent-card__member { margin: 3px 0; font-size: 11px; color: var(--ui-ink-2); }
.chat-agent-card__member em { margin-left: 3px; font-style: normal; opacity: 0.65; }
.chat-agent-card__conflict {
  padding: 6px 8px;
  margin-top: 5px;
  border-radius: var(--ui-radius-sm);
  background: var(--ui-warning-tint);
  color: var(--ui-warning);
  font-size: 11px;
  line-height: 1.55;
}
.chat-agent-card__warn { color: var(--ui-warning); font-size: 11px; }
.chat-agent-card__reason {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--ui-line);
  font-size: 11px;
  color: var(--ui-ink-2);
}
.chat-agent-card__reason :deep(svg) {
  flex: none;
  margin-top: 3px;
  color: var(--ui-accent);
}
.chat-agent-card__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 10px;
}
.chat-agent-card__actions .el-button {
  flex: 1 1 auto;
  margin-left: 0;
  min-height: 34px;
}
.chat-agent-card__hint { color: var(--ui-ink-3); font-size: 11px; line-height: 1.5; }
@media (max-width: 600px) {
  .chat-agent-card__nav,
  .chat-agent-card__people-toggle {
    min-height: 40px;
  }
}
</style>
