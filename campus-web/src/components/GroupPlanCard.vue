<template>
  <div v-if="view" class="chat-agent-card">
    <h4>
      🧭 {{ view.title || 'AI 规划（未确认）' }}
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
      <span v-if="!s.locationVisible" class="chat-agent-card__hidden">🔒 地点详情仅发起人可见</span>
      <button
        v-if="s.longitude != null && s.latitude != null"
        type="button"
        class="chat-agent-card__nav"
        title="在高德中导航到这里"
        @click.stop="navigateStop(s)"
      >🧭 导航</button>
    </p>
    <p v-for="(seg, i) in (view.routeSegments || []).slice(0, 8)" :key="'seg' + i" class="chat-agent-card__seg">
      ↳ {{ modeLabel(seg.transportMode) }} {{ seg.durationMinutes }} 分钟
    </p>
    <p v-if="view.totalTravelMinutes">🚦 全程交通约 {{ view.totalTravelMinutes }} 分钟
      <span v-if="view.routeConfidence === 'ESTIMATED'" class="chat-agent-card__routebadge">估算（未验证）</span>
      <span v-else-if="view.routeConfidence === 'AMAP'" class="chat-agent-card__routebadge ok">高德实测</span>
      <span v-else-if="view.routeConfidence === 'PARTIAL'" class="chat-agent-card__routebadge">部分未核验</span>
    </p>
    <p v-if="coverageNote" class="chat-agent-card__cov">{{ coverageNote }}</p>
    <div v-if="members.length || conflicts.length || view.warnings?.length" class="chat-agent-card__people">
      <button type="button" class="chat-agent-card__people-toggle" @click="peopleOpen = !peopleOpen">
        <span>👥 成员 {{ knownMemberCount }}/{{ members.length }}</span>
        <span v-if="conflicts.length" class="chat-agent-card__people-conflict">⚠ {{ conflicts.length }} 个冲突</span>
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
    <div v-if="view.reason" class="chat-agent-card__reason">💡 <MarkdownText :text="view.reason" /></div>
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
  padding: 10px;
  border: 1px solid #d9ecff;
  border-radius: 8px;
  background: #f5faff;
}
.chat-agent-card h4 {
  margin: 0 0 6px;
  font-size: 13px;
}
.chat-agent-card__ver {
  margin-left: 4px;
  color: #909399;
  font-size: 11px;
  font-weight: normal;
}
.chat-agent-card__badge {
  display: inline-block;
  padding: 1px 8px;
  margin: 0 6px 6px 0;
  border-radius: 10px;
  font-size: 11px;
}
.chat-agent-card__badge.ok { background: #f0f9eb; color: #529b2e; }
.chat-agent-card__badge.tentative { background: #fdf6ec; color: #b88230; }
.chat-agent-card__badge.conflict { background: #fef0f0; color: #c45656; }
.chat-agent-card__stop { margin: 4px 0; font-size: 12px; }
.chat-agent-card__nav {
  margin-left: 6px;
  border: 0;
  background: transparent;
  color: #409eff;
  cursor: pointer;
  font-size: 11px;
}
.chat-agent-card__hidden { margin-left: 6px; color: #909399; font-size: 11px; }
.chat-agent-card__seg { margin: 2px 0 2px 12px; color: #909399; font-size: 11px; }
.chat-agent-card__routebadge { margin-left: 6px; color: #909399; font-size: 11px; }
.chat-agent-card__routebadge.ok { color: #529b2e; }
.chat-agent-card__cov { margin: 4px 0; color: #606266; font-size: 11px; }
.chat-agent-card__people { margin-top: 6px; }
.chat-agent-card__people-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: 0;
  background: transparent;
  color: #606266;
  cursor: pointer;
  font-size: 11px;
  text-align: left;
}
.chat-agent-card__people-conflict { color: #c45656; }
.chat-agent-card__people-chevron { margin-left: auto; color: #909399; }
.chat-agent-card__member { margin: 3px 0; font-size: 11px; }
.chat-agent-card__member em { margin-left: 3px; font-style: normal; opacity: 0.6; }
.chat-agent-card__conflict {
  padding: 4px 6px;
  margin-top: 4px;
  border-radius: 6px;
  background: #fff7e6;
  color: #ad4e00;
  font-size: 11px;
}
.chat-agent-card__warn { color: #c0781c; font-size: 11px; }
.chat-agent-card__reason { margin-top: 6px; font-size: 11px; color: #606266; }
.chat-agent-card__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.chat-agent-card__hint { color: #909399; font-size: 11px; }
@media (max-width: 600px) {
  .chat-agent-card__actions {
    flex-wrap: wrap;
  }
  .chat-agent-card__actions .el-button {
    flex: 1 1 auto;
  }
  .chat-agent-card__stop,
  .chat-agent-card__seg {
    word-break: break-word;
  }
}
</style>
